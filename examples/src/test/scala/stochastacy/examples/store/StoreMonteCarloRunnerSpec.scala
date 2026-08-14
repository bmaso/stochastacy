package stochastacy.examples.store

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StoreMonteCarloRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreMonteCarloRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private val svc = ServiceConfig(ingressLatencyTicks = 0.05, egressLatencyTicks = 0.05)

  /** A throttling-prone single-use-case workload: gets at a mean rate that Poisson-bursts over cap. */
  private def getOnly(rate: Double): ApiWorkloadConfig =
    ApiWorkloadConfig(getPerTick = rate, createPerTick = 0.0, updatePerTick = 0.0, deletePerTick = 0.0, listPerTick = 0.0, reportPerTick = 0.0)

  private def runMC(
    api: ApiWorkloadConfig, adm: AdmissionConfig, master: Long, ticks: Long, trials: Int, parallelism: Int = 4
  ): StoreMonteCarloResult =
    Await.result(
      StoreMonteCarloRunner.run(api, StoreConfig(), svc, master, ticks, trials, adm, parallelism),
      60.seconds
    )

  private val throttling = getOnly(18.0)
  private val cap12      = AdmissionConfig(capacityPerTick = 12)
  private val getLatency = StoreStatKey("get", "latency")
  private val getThrottled = StoreStatKey("get", "throttled")

  "StoreMonteCarloRunner" should {

    "complete an ensemble and produce non-empty pooled statistics" in {
      val r = runMC(throttling, cap12, master = 1L, ticks = 40L, trials = 12)
      r.trialCount shouldBe 12
      r.perTrial should have size 12
      r.pooled.keys should not be empty
    }

    "be deterministic given a fixed master seed" in {
      runMC(throttling, cap12, master = 5L, ticks = 30L, trials = 6) shouldBe
        runMC(throttling, cap12, master = 5L, ticks = 30L, trials = 6)
    }

    "be independent of parallelism" in {
      runMC(throttling, cap12, master = 5L, ticks = 30L, trials = 8, parallelism = 1) shouldBe
        runMC(throttling, cap12, master = 5L, ticks = 30L, trials = 8, parallelism = 8)
    }

    "expose real run-to-run variance in the per-trial throttle rate (the point of MC)" in {
      val r = runMC(throttling, cap12, master = 3L, ticks = 40L, trials = 16)
      // Per-trial throttle rate = that trial's mean of the 0/1 `throttled` observation.
      r.acrossTrials(getThrottled, _.mean).stddev should be > 0.0
    }

    "conserve every observation when pooling (pooled count == sum of per-trial counts)" in {
      val r = runMC(throttling, cap12, master = 7L, ticks = 40L, trials = 10)
      val pooledCount   = r.pooled.get(getThrottled).map(_.count).getOrElse(0L)
      val perTrialTotal = r.perTrial.flatMap(_.get(getThrottled)).map(_.count).sum
      pooledCount shouldBe perTrialTotal
    }

    "order across-trial p99 at or above across-trial mean for a spread metric" in {
      val r = runMC(throttling, cap12, master = 9L, ticks = 40L, trials = 12)
      r.acrossTrials(getLatency, _.p99).mean should be >= r.acrossTrials(getLatency, _.mean).mean
    }

    "collapse across-trial variance to zero for a single trial" in {
      val r = runMC(throttling, cap12, master = 11L, ticks = 30L, trials = 1)
      r.acrossTrials(getThrottled, _.mean).stddev shouldBe 0.0
    }
  }
