package stochastacy.aws.dynamodb

import scala.concurrent.Future

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{FanOutShape2, Graph}

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.{ComponentResult, ComponentSampler, Emission, Scheduled, ScheduleReleaseTransducer, Timed}
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
    reconfigurationSchedule: ReconfigurationSchedule     = ReconfigurationSchedule.empty
  ):
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

    def initialState: TableState = TableState.initial(config.initialState, indexes, config.billingMode)

    def sample(
      in:    DynamoDbRequest,
      state: TableState,
      rng:   UniformRandomProvider
    ): Emission[TableState, DynamoDbResponse, DynamoDbConsumption] =
      val outcome      = config.behavior.outcomeFor(in, readTargetState(in, state), rng, state.currentTick)
      val resolution   = TableMechanics.resolve(outcome, state.base)
      val (latency, _) = config.latency.sample(0L, rng, ())

      // The base write's (new, previous) item bytes drive index maintenance; reads maintain nothing.
      val writeFootprint: Option[(Option[Long], Option[Long])] = outcome match
        case OperationOutcome.Put(written, previous)    => Some((Some(written), previous))
        case OperationOutcome.Update(written, previous) => Some((Some(written), previous))
        case OperationOutcome.Delete(deleted)           => Some((None, deleted))
        case _                                          => None

      val (nextIndexes, indexScheduled) = writeFootprint match
        case None =>
          (state.indexes, List.empty[Scheduled[DynamoDbConsumption]])
        case Some((newBytes, prevBytes)) =>
          indexes.foldLeft((state.indexes, List.empty[Scheduled[DynamoDbConsumption]])) {
            case ((states, scheduled), idx) =>
              val m = SecondaryIndexMechanics.maintain(idx, newBytes, prevBytes, states.getOrElse(idx.indexName, TableSummaryState.empty))
              (states.updated(idx.indexName, m.state), scheduled ++ m.consumption.map(c => Scheduled(c, idx.maintenanceDelay)))
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
            newState    = state.copy(base = resolution.state, indexes = nextIndexes, perTickBudget = state.perTickBudget.add(readDemand, writeDemand)),
            output      = Scheduled(resolution.response, math.max(0.0, latency)),
            consumption = resolution.consumption.map(Scheduled(_, 0.0)) ++ indexScheduled
          )
        case BillingMode.OnDemand =>
          // Uncapped: admit unchanged (no budget), exactly as before provisioned billing existed.
          Emission(
            newState    = state.copy(base = resolution.state, indexes = nextIndexes), // copy preserves currentTick
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

    /** At each tick boundary: advance the tick, reset the per-tick provisioned budget, and apply any
     *  scheduled reconfiguration — so the mode/capacity in force this tick reflects the schedule. */
    override def onTick(tick: Long, state: TableState): TableState =
      state.copy(
        currentTick   = tick,
        perTickBudget = ThrottleBudget.empty,
        billingMode   = config.reconfigurationSchedule.billingModeAt(tick, config.billingMode)
      )

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
