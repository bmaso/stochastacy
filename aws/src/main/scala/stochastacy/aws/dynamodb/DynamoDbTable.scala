package stochastacy.aws.dynamodb

import scala.concurrent.Future

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{FanOutShape2, Graph}

import stochastacy.core.component.{ComponentResult, ComponentSampler, Emission, Scheduled, ScheduleReleaseTransducer, Timed}
import stochastacy.core.sampler.StatelessSampler
import stochastacy.sim.TimedElement

/**
 * A single DynamoDB table as a v2 component. The generic table mechanics live here; a demo supplies its
 * domain through a [[TableBehavior]]. Phase-1 scope: a single on-demand table (no throttling, no
 * indexes, no advanced models).
 *
 * For each request the table asks its behavior for the operation's stochastic [[TableMechanics.OperationOutcome]],
 * resolves it through the pure mechanics, then emits the response after a per-op service latency and the
 * consumption facts at execution time. Its state is the immutable [[TableSummaryState]], threaded across
 * requests by the schedule-and-release transducer; the materialized value carries the final state.
 */
object DynamoDbTable:

  /** The table's configuration. */
  final case class Config(
    initialState:    TableSummaryState,
    behavior:        TableBehavior,
    latency:         StatelessSampler[Double], // per-op service latency, in fractional ticks
    readConsistency: ReadConsistency
  )

  /**
   * The table's per-request production function. Draws the operation outcome from the behavior, resolves
   * it against the current state, and packages the response (delayed by the drawn latency) and the
   * consumption facts (at execution time — capacity is consumed and storage changes when the op runs,
   * not when the client observes the response).
   */
  final class DynamoDbTableSampler(config: Config)
      extends ComponentSampler[TableSummaryState, DynamoDbRequest, DynamoDbResponse, DynamoDbConsumption]:

    def initialState: TableSummaryState = config.initialState

    def sample(
      in:    DynamoDbRequest,
      state: TableSummaryState,
      rng:   UniformRandomProvider
    ): Emission[TableSummaryState, DynamoDbResponse, DynamoDbConsumption] =
      val outcome    = config.behavior.outcomeFor(in, state, rng)
      val resolution = TableMechanics.resolve(outcome, config.readConsistency, state)
      val (latency, _) = config.latency.sample(0L, rng, ())
      Emission(
        newState    = resolution.state,
        output      = Scheduled(resolution.response, math.max(0.0, latency)),
        consumption = resolution.consumption.map(Scheduled(_, 0.0))
      )
    // onTick: inherited no-op — Phase-1 keeps no per-tick table state.

  /** Materialize the table into a running stage: requests in, responses and consumption facts out. */
  def componentOf(config: Config, rng: UniformRandomProvider): Graph[
    FanOutShape2[
      TimedElement[Timed[DynamoDbRequest]],
      TimedElement[Timed[DynamoDbResponse]],
      TimedElement[Timed[DynamoDbConsumption]]
    ],
    Future[ComponentResult[TableSummaryState]]
  ] =
    ScheduleReleaseTransducer.componentOf(new DynamoDbTableSampler(config), rng)
