package stochastacy.core.component.circuit

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.core.component.{LoopbackComponentSampler, Scheduled}
import stochastacy.sim.SimInstant

/** What any node dispatch produces, erased and unified across `sample` (always one output), `onFeedback` (an optional
 *  output), and `onTick` (no output, no taps). */
private[stochastacy] final case class ErasedEmission(
  newState:    Any,
  output:      Option[Scheduled[Any]],
  consumption: List[Scheduled[Any]],
  taps:        List[Scheduled[Any]]
)

/**
 * A circuit node: one [[LoopbackComponentSampler]] (plain `ComponentSampler`s and gates are subtypes) behind a
 * type-erased interface, with its own RNG. Stateless — the circuit stage owns node state and threads it through
 * each call. Type safety is the typed builder's job; a mistyped dispatch surfaces as an exception inside the
 * sampler, which the stage reports with the node's name.
 */
private[stochastacy] final class ErasedNode private (
  val name:         String,
  val initialState: Any,
  val rng:          UniformRandomProvider,
  sampleFn:         (Any, SimInstant, Any, UniformRandomProvider) => ErasedEmission,
  feedbackFn:       (Any, SimInstant, Any, UniformRandomProvider) => ErasedEmission,
  tickFn:           (Long, Any) => ErasedEmission
):
  def sample(in: Any, at: SimInstant, state: Any): ErasedEmission   = sampleFn(in, at, state, rng)
  def feedback(fb: Any, at: SimInstant, state: Any): ErasedEmission = feedbackFn(fb, at, state, rng)
  def tick(tick: Long, state: Any): ErasedEmission                  = tickFn(tick, state)

private[stochastacy] object ErasedNode:

  def of[S, In, Fb, Out, Cons, Tap](
    name:    String,
    sampler: LoopbackComponentSampler[S, In, Fb, Out, Cons, Tap],
    rng:     UniformRandomProvider
  ): ErasedNode =
    new ErasedNode(
      name,
      sampler.initialState,
      rng,
      sampleFn = (in, at, s, r) =>
        val e = sampler.sample(in.asInstanceOf[In], at, s.asInstanceOf[S], r)
        ErasedEmission(e.newState, Some(e.output.asInstanceOf[Scheduled[Any]]),
          e.consumption.asInstanceOf[List[Scheduled[Any]]], e.taps.asInstanceOf[List[Scheduled[Any]]]),
      feedbackFn = (fb, at, s, r) =>
        val e = sampler.onFeedback(fb.asInstanceOf[Fb], at, s.asInstanceOf[S], r)
        ErasedEmission(e.newState, e.output.asInstanceOf[Option[Scheduled[Any]]],
          e.consumption.asInstanceOf[List[Scheduled[Any]]], e.taps.asInstanceOf[List[Scheduled[Any]]]),
      tickFn = (t, s) =>
        val e = sampler.onTick(t, s.asInstanceOf[S])
        ErasedEmission(e.newState, None, e.consumption.asInstanceOf[List[Scheduled[Any]]], Nil)
    )
