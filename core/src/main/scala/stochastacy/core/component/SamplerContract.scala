package stochastacy.core.component

import org.apache.commons.rng.UniformRandomProvider

/** A latency/offset expressed in **fractional ticks**. The transducer converts a delay into
 *  an absolute `(eventTime, intraTick)` via `rawOffset = triggeringIntraTick + delay`. Samplers
 *  speak only in delays; they never compute absolute simulation time. */
type Delay = Double

/** A timeless domain payload scheduled to be observed `delay` fractional ticks after the event
 *  that triggered it. The transducer stamps the timing and lifts `event` onto the wire. */
final case class Scheduled[E](event: E, delay: Delay)

/** The constellation a request/response component produces for one consumed request:
 *  the updated state, exactly one response (a success- or error-variant of `Resp`), and
 *  zero or more consumption facts. */
final case class Emission[S, Resp, Cons](
  newState:    S,
  response:    Scheduled[Resp],
  consumption: List[Scheduled[Cons]]
)

/** Request/response component behavior. Given a request and current state, produce an
 *  `Emission`. This is the domain-specific production function; the schedule-and-release
 *  transducer is the generic machinery that runs it and owns all timing and ordering.
 *
 *  It is intentionally distinct from the workload `stochastacy.core.sampler.Sampler[S, T]`:
 *  that samples values against a tick; this samples an outcome constellation against a request. */
trait RequestResponseSampler[S, Req, Resp, Cons]:
  def initialState: S
  def sample(req: Req, state: S, rng: UniformRandomProvider): Emission[S, Resp, Cons]
