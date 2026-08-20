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
    localSecondaryIndexes:  Vector[LocalSecondaryIndex]  = Vector.empty
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

    def initialState: TableState = TableState.initial(config.initialState, indexes)

    def sample(
      in:    DynamoDbRequest,
      state: TableState,
      rng:   UniformRandomProvider
    ): Emission[TableState, DynamoDbResponse, DynamoDbConsumption] =
      val outcome      = config.behavior.outcomeFor(in, state.base, rng)
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

      Emission(
        newState    = TableState(resolution.state, nextIndexes),
        output      = Scheduled(resolution.response, math.max(0.0, latency)),
        consumption = resolution.consumption.map(Scheduled(_, 0.0)) ++ indexScheduled
      )
    // onTick: inherited no-op — the table keeps no per-tick state.

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
