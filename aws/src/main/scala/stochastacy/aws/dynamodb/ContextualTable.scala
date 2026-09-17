package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.core.component.{FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, TickEmission}
import stochastacy.sim.SimInstant

/** A value travelling with **caller-chosen context** — whatever a client needs back with the answer (a request number,
 *  an attempt count, when a session started). The table never looks at the context. */
final case class Contextual[+C, +A](context: C, value: A)

/**
 * A DynamoDB table that answers every request **with the context it arrived with**: `Contextual(c, request)` in,
 * `Contextual(c, response)` out — for successes and throttles alike. Obtain one with [[DynamoDbTable.withContext]].
 *
 * The DynamoDB protocol is fixed, so a request cannot carry a client's own context the way a gate's caller-typed
 * `Req` can; this adapter carries it alongside instead. It exists for a client node in a **circuit** that reacts to the
 * table's responses — retrying a throttled request up to an attempt limit, timing a request end to end. Correlation is
 * exact because `sample` answers each request with exactly one response.
 *
 * Everything else is the wrapped table, unchanged: the same state (`TableState`, so `CircuitResult.stateOf` reads it),
 * consumption facts, taps, and response latency; `onTick` and `onFeedback` pass straight through, so replicated writes
 * still apply. A fed-back item has no request context to answer with, so a feedback **output** fails loudly — the table
 * never produces one.
 */
final class ContextualTableSampler[C](
  table: LoopbackComponentSampler[TableState, DynamoDbRequest, ReplicationWrite, DynamoDbResponse, DynamoDbConsumption, ReplicationWrite]
) extends LoopbackComponentSampler[
      TableState, Contextual[C, DynamoDbRequest], ReplicationWrite, Contextual[C, DynamoDbResponse], DynamoDbConsumption, ReplicationWrite]:

  def initialState: TableState = table.initialState

  def sample(in: Contextual[C, DynamoDbRequest], at: SimInstant, state: TableState, rng: UniformRandomProvider)
      : LoopbackEmission[TableState, Contextual[C, DynamoDbResponse], DynamoDbConsumption, ReplicationWrite] =
    val e = table.sample(in.value, at, state, rng)
    LoopbackEmission(e.newState, e.output.copy(event = Contextual(in.context, e.output.event)), e.consumption, e.taps)

  override def onFeedback(fb: ReplicationWrite, at: SimInstant, state: TableState, rng: UniformRandomProvider)
      : FeedbackEmission[TableState, Contextual[C, DynamoDbResponse], DynamoDbConsumption, ReplicationWrite] =
    val e = table.onFeedback(fb, at, state, rng)
    e.output.foreach { out =>
      throw new IllegalStateException(
        s"a contextual table cannot answer a fed-back item: onFeedback emitted ${out.event}, which has no request context")
    }
    FeedbackEmission(e.newState, None, e.consumption, e.taps)

  override def onTick(tick: Long, state: TableState): TickEmission[TableState, DynamoDbConsumption] =
    table.onTick(tick, state)
