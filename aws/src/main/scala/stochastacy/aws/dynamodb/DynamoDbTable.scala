package stochastacy.aws.dynamodb

import scala.concurrent.Future

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{FanOutShape2, Graph}

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.{ComponentResult, ComponentSampler, Emission, Scheduled, ScheduleReleaseTransducer, TickEmission, Timed}
import stochastacy.core.sampler.StatelessSampler
import stochastacy.sim.TimedElement

/**
 * A single DynamoDB table as a v2 component. The generic table mechanics live here; a demo supplies its
 * domain through a [[TableBehavior]], and declares any secondary indexes **on** the table via [[Config]].
 * Scope: a single on-demand table (no throttling, no advanced models).
 *
 * For each request the table asks its behavior for the operation's stochastic [[TableMechanics.OperationOutcome]],
 * resolves it through the pure mechanics, and — for a base write — maintains each secondary index
 * ([[SecondaryIndexMechanics]]). It emits the response after a per-op service latency and the consumption
 * facts at execution time (index maintenance at its own delay: GSI async, LSI synchronous). Its state is
 * the whole-table [[TableState]] (base summary + one per index), threaded by the transducer; the
 * materialized value carries the final state.
 */
object DynamoDbTable:

  /** The table's configuration. Read consistency is a domain decision the behavior bakes into each read
   *  outcome, so the generic table config does not carry it. Secondary indexes are declared here — never
   *  wired as graph components. */
  final case class Config(
    initialState:           TableSummaryState,
    behavior:               TableBehavior,
    latency:                StatelessSampler[Double], // per-op service latency, in fractional ticks
    globalSecondaryIndexes: Vector[GlobalSecondaryIndex] = Vector.empty,
    localSecondaryIndexes:  Vector[LocalSecondaryIndex]  = Vector.empty,
    billingMode:            BillingMode                  = BillingMode.OnDemand, // the initial mode
    reconfigurationSchedule: ReconfigurationSchedule     = ReconfigurationSchedule.empty,
    ttlPeriodTicks:         Option[Int]                  = None, // item TTL, in ticks (None = TTL off)
    burstWindowTicks:       Int                          = 0, // ticks-of-ceiling of burst capacity to bank (0 = off)
    autoScalingPolicy:      Option[AutoScalingPolicy]    = None // reactive auto-scaling (None = off)
  ):
    require(burstWindowTicks >= 0, s"burstWindowTicks must be non-negative, got $burstWindowTicks")
    // Auto-scaling drives capacity reactively, so it is mutually exclusive with a static reconfiguration
    // schedule and requires the table to start provisioned.
    require(autoScalingPolicy.isEmpty || billingMode.isInstanceOf[BillingMode.Provisioned],
            "autoScalingPolicy requires an initial Provisioned billing mode")
    require(autoScalingPolicy.isEmpty || reconfigurationSchedule.entries.isEmpty,
            "autoScalingPolicy and a reconfigurationSchedule are mutually exclusive")

    def withGlobalSecondaryIndex(index: GlobalSecondaryIndex): Config =
      copy(globalSecondaryIndexes = globalSecondaryIndexes :+ index)

    def withLocalSecondaryIndex(index: LocalSecondaryIndex): Config =
      copy(localSecondaryIndexes = localSecondaryIndexes :+ index)

    /** All secondary indexes, GSIs then LSIs. */
    def secondaryIndexes: Vector[SecondaryIndex] = globalSecondaryIndexes ++ localSecondaryIndexes

  /**
   * The table's per-request production function. Resolves the base operation, maintains the secondary
   * indexes for base writes, and packages the response (delayed by the drawn latency) with the
   * consumption facts (base + index maintenance) — base and LSI facts at execution time, GSI facts after
   * the index's propagation delay.
   */
  final class DynamoDbTableSampler(config: Config)
      extends ComponentSampler[TableState, DynamoDbRequest, DynamoDbResponse, DynamoDbConsumption]:

    private val indexes: Vector[SecondaryIndex] = config.secondaryIndexes

    def initialState: TableState = TableState.initial(config.initialState, indexes, config.billingMode, config.ttlPeriodTicks)

    def sample(
      in:    DynamoDbRequest,
      state: TableState,
      rng:   UniformRandomProvider
    ): Emission[TableState, DynamoDbResponse, DynamoDbConsumption] =
      val outcome      = config.behavior.outcomeFor(in, readTargetState(in, state), rng, state.currentTick)
      val resolution   = TableMechanics.resolve(outcome, state.base)
      val (latency, _) = config.latency.sample(0L, rng, ())

      // The base write's (new, previous) item bytes drive index maintenance; reads maintain nothing. A
      // transactional write contributes one footprint per sub-item, so index + TTL maintenance iterate.
      val writeFootprints: List[(Option[Long], Option[Long])] = outcome match
        case OperationOutcome.Put(written, previous)    => List((Some(written), previous))
        case OperationOutcome.Update(written, previous) => List((Some(written), previous))
        case OperationOutcome.Delete(deleted)           => List((None, deleted))
        case OperationOutcome.TransactWrite(items)      => items.map(i => (Some(i.writtenItemBytes), i.previousItemBytes)).toList
        case _                                          => Nil

      // Transactional writes bill LSI maintenance 2× and GSI maintenance 1×; a normal write bills 1×.
      val transactional = outcome match
        case _: OperationOutcome.TransactWrite | _: OperationOutcome.TransactGet => true
        case _                                                                   => false

      // Maintain each index for every sub-write, threading index state across the sub-writes.
      val (nextIndexes, indexScheduled) =
        writeFootprints.foldLeft((state.indexes, List.empty[Scheduled[DynamoDbConsumption]])) {
          case ((states0, scheduled0), (newBytes, prevBytes)) =>
            indexes.foldLeft((states0, scheduled0)) {
              case ((states, scheduled), idx) =>
                val m = SecondaryIndexMechanics.maintain(idx, newBytes, prevBytes, states.getOrElse(idx.indexName, TableSummaryState.empty), transactional)
                (states.updated(idx.indexName, m.state), scheduled ++ m.consumption.map(c => Scheduled(c, idx.maintenanceDelay)))
            }
        }

      // Record each sub-write into the TTL ring buffer (if TTL is on) so it expires `ttlPeriodTicks` later.
      // An insert records a write; an overwrite re-ages the item (delete the old, write the new); a delete
      // removes one; reads and delete-of-absent leave it untouched. Applied only when the op is admitted.
      val nextTtl: Option[TtlRingBuffer] = state.ttl.map { rb0 =>
        writeFootprints.foldLeft(rb0) {
          case (rb, (Some(nb), None))    => rb.recordWrite(nb, state.currentTick)
          case (rb, (Some(nb), Some(_))) => rb.recordDelete(state.currentTick).recordWrite(nb, state.currentTick)
          case (rb, (None, Some(_)))     => rb.recordDelete(state.currentTick)
          case (rb, _)                   => rb
        }
      }

      // The operation's total capacity demand (base + index maintenance), grouped per budget target.
      val allConsumption = resolution.consumption ++ indexScheduled.map(_.event)
      val readDemand     = demandBy(allConsumption) { case ReadCapacityConsumed(u, _, t) => (ThrottleBudget.budgetKey(t), u) }
      val writeDemand    = demandBy(allConsumption) { case WriteCapacityConsumed(u, t)   => (ThrottleBudget.budgetKey(t), u) }

      state.billingMode match
        case p: BillingMode.Provisioned if state.perTickBudget.overBudget(readDemand, writeDemand, p) =>
          // Throttle: reject the whole operation — no capacity consumed, no state mutated, budget untouched.
          Emission(
            newState    = state, // base / indexes / currentTick / budget all preserved
            output      = Scheduled(ThrottledResponse, math.max(0.0, latency)),
            consumption = List(Scheduled(RequestThrottled(firstOverTarget(allConsumption, state.perTickBudget, p)), 0.0))
          )
        case p: BillingMode.Provisioned =>
          // Admit and charge the demand against this tick's provisioned budget.
          Emission(
            newState    = state.copy(base = resolution.state, indexes = nextIndexes, perTickBudget = state.perTickBudget.add(readDemand, writeDemand), ttl = nextTtl),
            output      = Scheduled(resolution.response, math.max(0.0, latency)),
            consumption = resolution.consumption.map(Scheduled(_, 0.0)) ++ indexScheduled
          )
        case BillingMode.OnDemand =>
          // Uncapped: admit unchanged (no budget), exactly as before provisioned billing existed.
          Emission(
            newState    = state.copy(base = resolution.state, indexes = nextIndexes, ttl = nextTtl), // copy preserves currentTick
            output      = Scheduled(resolution.response, math.max(0.0, latency)),
            consumption = resolution.consumption.map(Scheduled(_, 0.0)) ++ indexScheduled
          )

    /** Sum per-budget-target capacity demand from the operation's consumption facts. */
    private def demandBy(
      consumption: List[DynamoDbConsumption]
    )(extract: PartialFunction[DynamoDbConsumption, (String, BigDecimal)]): Map[String, BigDecimal] =
      consumption.collect(extract).groupMapReduce(_._1)(_._2)(_ + _)

    /** The first budget target whose admitted-plus-demand exceeds its ceiling — labels the throttle marker. */
    private def firstOverTarget(consumption: List[DynamoDbConsumption], budget: ThrottleBudget, p: BillingMode.Provisioned): DynamoDbTarget =
      consumption.collectFirst {
        case c if budget.overBudget(
                    demandBy(List(c)) { case ReadCapacityConsumed(u, _, t) => (ThrottleBudget.budgetKey(t), u) },
                    demandBy(List(c)) { case WriteCapacityConsumed(u, t)   => (ThrottleBudget.budgetKey(t), u) },
                    p) => c.target
      }.getOrElse(DynamoDbTarget.Table)

    /** At each tick boundary: advance the tick, reset the per-tick provisioned budget, apply any scheduled
     *  reconfiguration, and — when TTL is on — expire the cohort written `ttlPeriodTicks` ago, freeing base
     *  and per-index storage. The freeing is emitted as negative, target-tagged `StorageBytesDelta` facts
     *  (stamped at the boundary, released first in this tick's window) and consumes no capacity — TTL
     *  deletes are free. */
    override def onTick(tick: Long, state: TableState): TickEmission[TableState, DynamoDbConsumption] =
      // Reactive auto-scaling drives the new tick's capacity from the just-completed tick's utilization
      // (read from the budget + the capacity that was in force); otherwise the static schedule applies.
      val (nextBillingMode, nextAutoScaling) = (config.autoScalingPolicy, state.billingMode) match
        case (Some(policy), p: BillingMode.Provisioned) =>
          AutoScaler.step(policy, tick, p, state.perTickBudget, state.autoScaling)
        case _ =>
          (config.reconfigurationSchedule.billingModeAt(tick, config.billingMode), state.autoScaling)

      // Burst: bank the just-completed tick's unused capacity (using its provisioned ceilings, before we
      // advance the mode). Off / on-demand → a plain reset, exactly as before.
      val rolledBudget = state.billingMode match
        case p: BillingMode.Provisioned if config.burstWindowTicks > 0 =>
          state.perTickBudget.rollForward(p, config.globalSecondaryIndexes.map(_.indexName), config.burstWindowTicks)
        case _ => ThrottleBudget.empty
      val advanced = state.copy(
        currentTick   = tick,
        perTickBudget = rolledBudget,
        billingMode   = nextBillingMode,
        autoScaling   = nextAutoScaling
      )

      // When auto-scaling drives the capacity, emit the tick's reserved capacity so the accounting bills the
      // runtime trace instead of the static schedule. No policy → nothing emitted (byte-identical).
      val snapshotFacts: List[Scheduled[DynamoDbConsumption]] =
        if config.autoScalingPolicy.isDefined then
          nextBillingMode match
            case p: BillingMode.Provisioned => List(Scheduled(ProvisionedCapacitySnapshot(p.totalReadCapacity, p.totalWriteCapacity), 0.0))
            case _                          => Nil
        else Nil

      state.ttl match
        case None => TickEmission(advanced, snapshotFacts)
        case Some(rb) =>
          val (count, freedBase, nextRb) = rb.expire(tick)
          val withRb                     = advanced.copy(ttl = Some(nextRb))
          if count <= 0L then TickEmission(withRb, snapshotFacts)
          else
            // The expired cohort's average item size drives each index's projected freed bytes — the exact
            // inverse of the maintenance a write performs.
            val avgBytes = freedBase / count
            val (shrunkIndexes, indexFacts) =
              indexes.foldLeft((withRb.indexes, List.empty[DynamoDbConsumption])) {
                case ((states, facts), idx) =>
                  val freed = count * SecondaryIndexMechanics.projectedEntryBytes(Some(avgBytes), idx.projection).getOrElse(0L)
                  val cur   = states.getOrElse(idx.indexName, TableSummaryState.empty)
                  val next  = cur.applyExpiry(count, freed)
                  val facts2 = if freed != 0L then facts :+ StorageBytesDelta(-freed, idx.target) else facts
                  (states.updated(idx.indexName, next), facts2)
              }
            val nextState = withRb.copy(base = withRb.base.applyExpiry(count, freedBase), indexes = shrunkIndexes)
            val allFacts  = StorageBytesDelta(-freedBase, DynamoDbTarget.Table) :: indexFacts
            TickEmission(nextState, snapshotFacts ++ allFacts.map(Scheduled(_, 0.0)))

    /** The state a request reads/decides against: an index's own summary for a GSI/LSI query or scan,
     *  the base summary for a table read and for every write/get. */
    private def readTargetState(in: DynamoDbRequest, state: TableState): TableSummaryState = in match
      case q: QueryRequest => targetState(q.target, state)
      case s: ScanRequest  => targetState(s.target, state)
      case _               => state.base

    private def targetState(target: DynamoDbTarget, state: TableState): TableSummaryState = target match
      case DynamoDbTarget.Table     => state.base
      case DynamoDbTarget.Gsi(name) => state.index(name)
      case DynamoDbTarget.Lsi(name) => state.index(name)

  /** Materialize the table into a running stage: requests in, responses and consumption facts out. */
  def componentOf(config: Config, rng: UniformRandomProvider): Graph[
    FanOutShape2[
      TimedElement[Timed[DynamoDbRequest]],
      TimedElement[Timed[DynamoDbResponse]],
      TimedElement[Timed[DynamoDbConsumption]]
    ],
    Future[ComponentResult[TableState]]
  ] =
    ScheduleReleaseTransducer.componentOf(new DynamoDbTableSampler(config), rng)
