package stochastacy.core.component

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.sim.SimInstant

/** A latency/offset expressed in **fractional ticks**. The transducer converts a delay into
 *  an absolute `(eventTime, intraTick)` via `rawOffset = triggeringIntraTick + delay`. Samplers
 *  speak only in delays; they never compute absolute simulation time. */
type Delay = Double

/** A timeless domain payload scheduled to be observed `delay` fractional ticks after the event
 *  that triggered it. The transducer stamps the timing and lifts `event` onto the wire. */
final case class Scheduled[E](event: E, delay: Delay)

/**
 * The constellation a **loopback-capable** component produces for one consumed input: the updated state,
 * exactly one **forward output** (a response for a leaf, a downstream request for a forwarder), zero or
 * more consumption facts, and zero or more **taps** — effects published on the loopback (loop-out) plane
 * to be routed, after some external delay, back into a peer component's feedback (loop-in) input. A plain
 * component pins `Tap = Nothing` and emits no taps; see the [[Emission]] alias.
 */
final case class LoopbackEmission[S, Out, Cons, Tap](
  newState:    S,
  output:      Scheduled[Out],
  consumption: List[Scheduled[Cons]],
  taps:        List[Scheduled[Tap]] = Nil
)

/** The constellation a component with **no loopback** produces for one input — the common case: state, one
 *  forward output, and consumption facts, with the tap plane pinned to `Nothing`. This is the simplified
 *  view of [[LoopbackEmission]]; its `apply`/`unapply` keep the familiar three-argument form working. */
type Emission[S, Out, Cons] = LoopbackEmission[S, Out, Cons, Nothing]

object Emission:
  def apply[S, Out, Cons](newState: S, output: Scheduled[Out], consumption: List[Scheduled[Cons]]): Emission[S, Out, Cons] =
    LoopbackEmission(newState, output, consumption, Nil)
  def unapply[S, Out, Cons](e: Emission[S, Out, Cons]): Some[(S, Scheduled[Out], List[Scheduled[Cons]])] =
    Some((e.newState, e.output, e.consumption))

/** What a component produces at a **tick boundary**: the advanced state plus zero or more scheduled consumption
 *  facts. Consumption **only** — a tick boundary has no request to answer, so it never emits a forward output,
 *  and the 1:1 request/response invariant holds by construction. */
final case class TickEmission[S, Cons](newState: S, consumption: List[Scheduled[Cons]])

/**
 * What a loopback component produces when **absorbing a fed-back (loop-in) item** via `onFeedback`: the advanced
 * state, an **optional** forward output, consumption facts, and taps. Unlike `sample` — which answers every primary
 * input with exactly one output — a fed-back item may answer nothing (a replicated write: `output = None`) or
 * trigger a request (a retry, a next page: `output = Some(…)`).
 *
 * A tap emitted from feedback must be stamped at a **later tick** than the fed-back item: the Pekko loopback stage
 * releases each tap window as soon as the primary input reaches the next tick, which can precede the window's
 * remaining feedback (see `ScheduleReleaseTransducer.loopbackComponentOf`; the stage fails on a violation).
 */
final case class FeedbackEmission[S, Out, Cons, Tap](
  newState:    S,
  output:      Option[Scheduled[Out]] = None,
  consumption: List[Scheduled[Cons]]  = Nil,
  taps:        List[Scheduled[Tap]]   = Nil
)

/** The `usecase` stamped on a fact a component emits at a tick boundary — there is no triggering request. */
case object TickBoundaryUsecase

/**
 * A component's behavior, in its **loopback-capable** general form: given one timeless input payload, the input's
 * conceptual time, and current state, produce a [[LoopbackEmission]] (forward output + consumption + taps). A
 * component wired into a feedback loop additionally consumes fed-back items on its loop-in input via
 * [[onFeedback]] and publishes loop-out effects as the `taps` of its emissions; an external stage carries taps back
 * to feedback inputs after a delay. The schedule-and-release transducer is the generic machinery that runs it and
 * owns all timing/ordering.
 *
 * `In`/`Fb`/`Out`/`Tap` are timeless payloads — the wire carries `Timed[…]`. `at` is the input's conceptual time
 * (`eventTime + intraTick`), for behavior that depends on *when* an input happened; outputs are still scheduled by
 * delay. The common case with **no loop** is [[ComponentSampler]], the pinned-`Nothing` subtype.
 */
trait LoopbackComponentSampler[S, In, Fb, Out, Cons, Tap]:
  def initialState: S

  /** Produce the outcome constellation for one primary input at conceptual time `at` — state, forward output,
   *  consumption, taps. */
  def sample(in: In, at: SimInstant, state: S, rng: UniformRandomProvider): LoopbackEmission[S, Out, Cons, Tap]

  /** Absorb one fed-back (loop-in) item at conceptual time `at`: advance state and optionally emit a forward output,
   *  consumption facts, and taps (see [[FeedbackEmission]] for the tap timing rule). */
  def onFeedback(fb: Fb, at: SimInstant, state: S, rng: UniformRandomProvider): FeedbackEmission[S, Out, Cons, Tap]

  /** Advance state at a tick boundary, before that tick's inputs are sampled, and optionally emit boundary
   *  consumption facts (e.g. a storage delta for TTL expiry). Defaulted to a no-op. Called once per `Tick`. */
  def onTick(tick: Long, state: S): TickEmission[S, Cons] = TickEmission(state, Nil)

/**
 * A component with **no feedback loop** — the common case: `Fb`/`Tap` pinned to `Nothing`. `sample` returns
 * an [[Emission]] (the tap-less view of [[LoopbackEmission]]); `onFeedback` is never called (there are no
 * `Nothing` values).
 */
trait ComponentSampler[S, In, Out, Cons] extends LoopbackComponentSampler[S, In, Nothing, Out, Cons, Nothing]:
  def onFeedback(fb: Nothing, at: SimInstant, state: S, rng: UniformRandomProvider): FeedbackEmission[S, Out, Cons, Nothing] = fb
