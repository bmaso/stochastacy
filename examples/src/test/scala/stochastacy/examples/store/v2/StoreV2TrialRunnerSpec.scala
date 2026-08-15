package stochastacy.examples.store.v2

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.ResidueSummary
import stochastacy.core.component.gate.{ChaosGate, FlatThrottleGate, LatencyGate}
import stochastacy.examples.store.{ApiWorkload, ApiWorkloadConfig, ErrorResult, StoreConfig, StoreRequest, StoreResponse}

class StoreV2TrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreV2TrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private def run(api: ApiWorkloadConfig, edge: EdgeConfig, seed: Long, ticks: Long, reqTicks: Long = -1L): StoreV2TrialResult =
    Await.result(StoreV2TrialRunner.run(api, StoreConfig(), edge, seed, ticks, reqTicks), 30.seconds)

  /** Pooled mean of an outcome metric across all keys = its overall rate. */
  private def rate(r: StoreV2TrialResult, metric: String): Double =
    r.stats.keys.filter(_.metric == metric).flatMap(r.stats.get).reduceOption(_ combine _).map(_.mean).getOrElse(0.0)

  /** Each response emits one `outcome.throttled` 0/1 observation, so its total count is the response count. */
  private def responseCount(r: StoreV2TrialResult): Long =
    r.stats.keys.filter(_.metric == "outcome.throttled").flatMap(r.stats.get).map(_.count).sum

  private def requestCount(api: ApiWorkloadConfig, seed: Long, reqTicks: Long): Int =
    val master = RandomSource.KISS.create(seed)
    ApiWorkload.requests(api, RandomSource.KISS.create(master.nextLong()), reqTicks).size

  "StoreV2TrialRunner (datastore behind a full gate stack)" should {

    "compose latency + throttle + chaos and report each terminal outcome as a rate" in {
      val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(12), chaosProbability = 0.3)
      val r    = run(ApiWorkloadConfig.getOnly(18.0), edge, seed = 1L, ticks = 60L, reqTicks = 40L)
      rate(r, "outcome.throttled") should be > 0.0
      rate(r, "outcome.chaos") should be > 0.0
      rate(r, "outcome.served") should be > 0.0
      // Every request has exactly one terminal outcome, so the three rates sum to 1.
      (rate(r, "outcome.served") + rate(r, "outcome.throttled") + rate(r, "outcome.chaos")) shouldBe (1.0 +- 1e-9)
    }

    "preserve exact 1:1 integrity — one terminal outcome per request, rejections included" in {
      val api  = ApiWorkloadConfig.getOnly(18.0)
      val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(12), chaosProbability = 0.3)
      val r    = run(api, edge, seed = 1L, ticks = 60L, reqTicks = 40L)
      r.residue shouldBe ResidueSummary(0L, 0L)
      responseCount(r) shouldBe requestCount(api, seed = 1L, reqTicks = 40L)
    }

    "produce no rejections under ample capacity and zero chaos" in {
      val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(200), chaosProbability = 0.0)
      val r    = run(ApiWorkloadConfig.getOnly(5.0), edge, seed = 1L, ticks = 40L)
      rate(r, "outcome.throttled") shouldBe 0.0
      rate(r, "outcome.chaos") shouldBe 0.0
      rate(r, "outcome.served") shouldBe 1.0
    }

    "accept a token-bucket rate limiter" in {
      val edge = EdgeConfig(rateLimiter = RateLimiter.TokenBucket(capacity = 20, refillPerTick = 8), chaosProbability = 0.0)
      val r    = run(ApiWorkloadConfig.getOnly(18.0), edge, seed = 1L, ticks = 60L, reqTicks = 40L)
      rate(r, "outcome.throttled") should be > 0.0            // sustained 18 > refill 8 → throttles
      rate(r, "outcome.served") should be > 0.0
    }

    "run the raw gate-Seq path (experiments)" in {
      val api   = ApiWorkloadConfig.getOnly(18.0)
      val gates = Seq(
        LatencyGate.constant[StoreRequest, StoreResponse](0.0),
        new FlatThrottleGate[StoreRequest, StoreResponse](12, ErrorResult("throttled")),
        ChaosGate.constant[StoreRequest, StoreResponse](0.0, ErrorResult("unavailable"))
      )
      val r = Await.result(StoreV2TrialRunner.runGates(api, StoreConfig(), gates, seed = 1L, simulationTicks = 60L, requestTicks = 40L), 30.seconds)
      rate(r, "outcome.throttled") should be > 0.0
      responseCount(r) shouldBe requestCount(api, seed = 1L, reqTicks = 40L)
    }

    "be deterministic given a fixed seed" in {
      val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(12), chaosProbability = 0.3)
      run(ApiWorkloadConfig.getOnly(18.0), edge, seed = 7L, ticks = 40L) shouldBe
        run(ApiWorkloadConfig.getOnly(18.0), edge, seed = 7L, ticks = 40L)
    }
  }
