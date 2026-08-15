package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{Admit, Emission, InterfaceSampler, Reject, Scheduled}

/** A token-bucket rate gate: admits a request when a token is available (spending one) and rejects
 *  otherwise. `refillPerTick` tokens are added at each tick boundary, capped at `capacity` — so the
 *  bucket **banks unused capacity during quiet ticks and spends it on a later burst**, up to
 *  `capacity`. Same average admission ceiling as a flat per-tick cap of `refillPerTick`, but with
 *  burst tolerance: under bursty load it throttles far less; under *sustained* overload it is still
 *  refill-limited and throttles like the flat cap.
 *
 *  Tokens are fractional (real rate limiters run at fractional rates), so a `refillPerTick < 1`
 *  accumulates across ticks until a whole token is available. The bucket starts full. The domain
 *  supplies the response a rejection returns (e.g. a 429). Third use of the `onTick` hook (after the
 *  flat throttle's reset and the latency gate's tick threading). */
final class TokenBucketGate[Req, Resp](
  capacity:       Double,
  refillPerTick:  Double,
  rejectResponse: Resp,
  latencyTicks:   Double = 0.0
) extends InterfaceSampler[TokenBucketGate.State, Req, Resp]:

  def initialState: TokenBucketGate.State = TokenBucketGate.State(capacity)

  override def onTick(tick: Long, state: TokenBucketGate.State): TokenBucketGate.State =
    TokenBucketGate.State(math.min(capacity, state.tokens + refillPerTick))

  def sample(req: Req, state: TokenBucketGate.State, rng: UniformRandomProvider) =
    if state.tokens >= 1.0 then
      Emission(TokenBucketGate.State(state.tokens - 1.0), Scheduled(Admit(req), latencyTicks), Nil)
    else
      Emission(state, Scheduled(Reject(rejectResponse), latencyTicks), Nil)

object TokenBucketGate:
  final case class State(tokens: Double)
