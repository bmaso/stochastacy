package stochastacy.examples.store.v2

import stochastacy.core.component.InterfaceSampler
import stochastacy.core.component.gate.{ChaosGate, FlatThrottleGate, LatencyGate, TokenBucketGate}
import stochastacy.core.sampler.{ConstantSampler, StatelessSampler}
import stochastacy.examples.store.{ErrorResult, StoreRequest, StoreResponse}

/** The rate-limiting policy for the Store Demo V2 edge — the swappable gate the burst experiment
 *  A/Bs. `FlatThrottle` is a hard per-tick cap; `TokenBucket` is the burst-tolerant variant. */
enum RateLimiter:
  case FlatThrottle(capacityPerTick: Int)
  case TokenBucket(capacity: Double, refillPerTick: Double)

/** Structured configuration for the V2 edge — a lean description the demo runner turns into a gate
 *  stack. Rejections are in-band `StoreResponse`s: the rate limiter emits `ErrorResult("throttled")`
 *  (429) and chaos emits `ErrorResult("unavailable")` (503). Experiments that need finer control
 *  (gate reordering, extra gates) use the runner's raw-`Seq[gate]` path instead. */
final case class EdgeConfig(
  latency:          StatelessSampler[Double] = ConstantSampler(0.0),
  rateLimiter:      RateLimiter              = RateLimiter.FlatThrottle(20),
  chaosProbability: Double                   = 0.0
)

object EdgeConfig:

  /** The gate stack, outermost-first: `latency → rate-limiter → chaos`. A request pays edge latency,
   *  is rate-gated, then faces the backend's random-failure draw before reaching the datastore. */
  def gates(cfg: EdgeConfig): Seq[InterfaceSampler[?, StoreRequest, StoreResponse]] =
    val rateLimiter: InterfaceSampler[?, StoreRequest, StoreResponse] = cfg.rateLimiter match
      case RateLimiter.FlatThrottle(c)   => new FlatThrottleGate[StoreRequest, StoreResponse](c, ErrorResult("throttled"))
      case RateLimiter.TokenBucket(c, r) => new TokenBucketGate[StoreRequest, StoreResponse](c, r, ErrorResult("throttled"))
    Seq(
      new LatencyGate[StoreRequest, StoreResponse](cfg.latency),
      rateLimiter,
      ChaosGate.constant[StoreRequest, StoreResponse](cfg.chaosProbability, ErrorResult("unavailable"))
    )
