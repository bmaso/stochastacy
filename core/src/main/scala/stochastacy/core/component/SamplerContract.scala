package stochastacy.core.component

import org.apache.commons.rng.UniformRandomProvider

/** A latency/offset expressed in **fractional ticks**. The transducer converts a delay into
 *  an absolute `(eventTime, intraTick)` via `rawOffset = triggeringIntraTick + delay`. Samplers
 *  speak only in delays; they never compute absolute simulation time. */
type Delay = Double

/** A timeless domain payload scheduled to be observed `delay` fractional ticks after the event
 *  that triggered it. The transducer stamps the timing and lifts `event` onto the wire. */
final case class Scheduled[E](event: E, delay: Delay)

/** The constellation a component produces for one consumed input: the updated state, exactly one
 *  **forward output** (a response for a leaf, a downstream request for a forwarder — a success- or
 *  error-variant of `Out`), and zero or more consumption facts. */
final case class Emission[S, Out, Cons](
  newState:    S,
  output:      Scheduled[Out],
  consumption: List[Scheduled[Cons]]
)

/** What a component produces at a **tick boundary**: the advanced state plus zero or more scheduled
 *  consumption facts. Consumption **only** — a tick boundary has no request to answer, so it never emits a
 *  forward output, and the 1:1 request/response invariant holds by construction. Used e.g. by a table that
 *  frees storage on TTL expiry. */
final case class TickEmission[S, Cons](newState: S, consumption: List[Scheduled[Cons]])

/** The `usecase` stamped on a fact a component emits at a tick boundary — there is no triggering request. */
case object TickBoundaryUsecase

/** A component's behavior: given one timeless input payload and current state, produce an
 *  `Emission` — the updated state plus a scheduled forward output and consumption facts. This is
 *  the domain-specific production function; the schedule-and-release transducer is the generic
 *  machinery that runs it (unwrapping `Timed[In]`, stamping outputs) and owns all timing/ordering.
 *
 *  `In`/`Out` are timeless payloads — the wire carries `Timed[In]` / `Timed[Out]`. `In` for a leaf
 *  is a request and `Out` a response; for a forwarding component `Out` is the downstream request it
 *  issues (hence "forward output", not "response").
 *
 *  Intentionally distinct from the workload `stochastacy.core.sampler.Sampler[S, T]`: that samples
 *  values against a tick; this samples an outcome constellation against an input event. */
trait ComponentSampler[S, In, Out, Cons]:
  def initialState: S
  def sample(in: In, state: S, rng: UniformRandomProvider): Emission[S, Out, Cons]

  /** Advance state at a tick boundary, before that tick's inputs are sampled, and optionally emit
   *  consumption facts produced by the boundary itself (e.g. a storage delta for TTL expiry). The default
   *  advances nothing and emits nothing; load- or time-dependent components override it to reset/decay
   *  accumulated state, and boundary-effecting components additionally return scheduled facts. Called once
   *  per `Tick`, including empty ticks. The emitted facts are released in the tick's own window. */
  def onTick(tick: Long, state: S): TickEmission[S, Cons] = TickEmission(state, Nil)
