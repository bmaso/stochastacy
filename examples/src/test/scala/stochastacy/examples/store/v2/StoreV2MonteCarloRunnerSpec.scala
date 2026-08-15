package stochastacy.examples.store.v2

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.examples.store.{ApiWorkloadConfig, StoreConfig, StoreMonteCarloResult, StoreStatKey}

class StoreV2MonteCarloRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreV2MonteCarloRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(12), chaosProbability = 0.2)

  private def runMC(seed: Long, ticks: Long, trials: Int, parallelism: Int = 4): StoreMonteCarloResult =
    Await.result(
      StoreV2MonteCarloRunner.run(ApiWorkloadConfig.getOnly(18.0), StoreConfig(), edge, seed, ticks, trials, parallelism),
      60.seconds
    )

  private val throttledKey = StoreStatKey("get", "outcome.throttled")
  private val chaosKey      = StoreStatKey("get", "outcome.chaos")

  "StoreV2MonteCarloRunner" should {

    "aggregate per-gate reject rates across the ensemble" in {
      val r = runMC(seed = 1L, ticks = 40L, trials = 8)
      r.trialCount shouldBe 8
      r.pooled.get(throttledKey).map(_.mean).getOrElse(0.0) should be > 0.0
      r.pooled.get(chaosKey).map(_.mean).getOrElse(0.0) should be > 0.0
    }

    "expose run-to-run variance in a per-gate rate" in {
      val r = runMC(seed = 3L, ticks = 40L, trials = 12)
      r.acrossTrials(chaosKey, _.mean).stddev should be > 0.0
    }

    "be deterministic and independent of parallelism" in {
      runMC(seed = 5L, ticks = 30L, trials = 6, parallelism = 1) shouldBe
        runMC(seed = 5L, ticks = 30L, trials = 6, parallelism = 8)
    }
  }
