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
