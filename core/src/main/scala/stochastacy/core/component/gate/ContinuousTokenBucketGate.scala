package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{Admit, Emission, InterfaceSampler, Reject, Scheduled, TickEmission}
import stochastacy.sim.SimInstant

/**
 * A token-bucket rate gate refilled in **continuous time**: tokens accrue at `refillPerTick` per tick of *elapsed
 * conceptual time* — `at.toDouble` — rather than once per tick boundary, capped at `capacity`. Admitting spends one
 * token; a request arriving with less than a whole token is rejected.
 *
 * This is the form a **feedback loop needs**. [[TokenBucketGate]] tops up only at tick boundaries, so a bucket drained
 * early in a tick stays empty for the rest of it however much simulated time passes — inside a circuit, where a loop
 * closes within a tick, that is the tick masquerading as the rate limit. Here a request half a tick after a drain sees
 * half a tick's worth of refill.
 *
 * The defining property: over **any** interval of length `T`, admissions never exceed `capacity + refillPerTick × T`.
 * The bucket starts full, tokens are fractional, and nothing accrues before the first request (there is no earlier
 * time to accrue from). The domain supplies the response a rejection returns; the rejection also carries the request,
 * so a client in a feedback loop can retry it.
 */
final class ContinuousTokenBucketGate[Req, Resp](
  capacity:       Double,
  refillPerTick:  Double,
  rejectResponse: Resp,
  latencyTicks:   Double = 0.0
) extends InterfaceSampler[ContinuousTokenBucketGate.State, Req, Resp]:

  require(capacity >= 0.0, s"capacity must be non-negative, got $capacity")
  require(refillPerTick >= 0.0, s"refillPerTick must be non-negative, got $refillPerTick")

  def initialState: ContinuousTokenBucketGate.State = ContinuousTokenBucketGate.State(capacity, None)

  def sample(req: Req, at: SimInstant, state: ContinuousTokenBucketGate.State, rng: UniformRandomProvider) =
    val now     = at.toDouble
    val elapsed = state.lastTime.fold(0.0)(last => math.max(0.0, now - last))
    val tokens  = math.min(capacity, state.tokens + elapsed * refillPerTick)
    if tokens >= 1.0 then
      Emission(ContinuousTokenBucketGate.State(tokens - 1.0, Some(now)), Scheduled(Admit(req), latencyTicks), Nil)
    else
      Emission(ContinuousTokenBucketGate.State(tokens, Some(now)), Scheduled(Reject(req, rejectResponse), latencyTicks), Nil)

object ContinuousTokenBucketGate:
  /** `tokens` as of `lastTime` (the conceptual time of the last request seen; `None` before the first). */
  final case class State(tokens: Double, lastTime: Option[Double])
