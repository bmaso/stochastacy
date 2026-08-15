package stochastacy.examples.store.v2

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.ResidueSummary
import stochastacy.core.sampler.{ConstantSampler, LogNormalSampler, StatelessSampler}
import stochastacy.examples.store.{ApiWorkload, ApiWorkloadConfig, ErrorResult, StoreConfig}

class StoreV2TrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreV2TrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private def run(
    api: ApiWorkloadConfig, cap: Int, seed: Long, ticks: Long, reqTicks: Long = -1L,
    edgeLatency: StatelessSampler[Double] = ConstantSampler(0.0)
  ): StoreV2TrialResult =
    Await.result(StoreV2TrialRunner.run(api, StoreConfig(), cap, seed, ticks, reqTicks, edgeLatency), 30.seconds)

  private def throttleCount(r: StoreV2TrialResult): Int =
    r.responses.count { case ErrorResult("throttled") => true; case _ => false }

  /** Reproduce the runner's workload seed split (first split off the master) to know the request count. */
  private def requestCount(api: ApiWorkloadConfig, seed: Long, reqTicks: Long): Int =
    val master = RandomSource.KISS.create(seed)
    ApiWorkload.requests(api, RandomSource.KISS.create(master.nextLong()), reqTicks).size

  "StoreV2TrialRunner (datastore behind a throttle interface)" should {

    "throttle when offered load exceeds the gate capacity" in {
      val r = run(ApiWorkloadConfig.getOnly(18.0), cap = 12, seed = 1L, ticks = 60L, reqTicks = 40L)
      throttleCount(r) should be > 0
    }

    "not throttle when capacity comfortably exceeds load" in {
      val r = run(ApiWorkloadConfig.getOnly(5.0), cap = 200, seed = 1L, ticks = 40L)
      throttleCount(r) shouldBe 0
    }

    "preserve exact 1:1 integrity — one response per request, rejections included" in {
      val api = ApiWorkloadConfig.getOnly(18.0)
      val r   = run(api, cap = 12, seed = 1L, ticks = 60L, reqTicks = 40L)
      r.residue shouldBe ResidueSummary(0L, 0L)                          // datastore fully drained
      r.responses.size shouldBe requestCount(api, seed = 1L, reqTicks = 40L)
      throttleCount(r) should be > 0                                     // and throttling really happened
    }

    "be deterministic given a fixed seed" in {
      run(ApiWorkloadConfig.getOnly(18.0), cap = 12, seed = 7L, ticks = 40L) shouldBe
        run(ApiWorkloadConfig.getOnly(18.0), cap = 12, seed = 7L, ticks = 40L)
    }

    "compose a distributional latency gate in front of the throttle without breaking throttling or 1:1" in {
      // A realistic log-normal edge latency, well within the drain pad; throttling and 1:1 must survive.
      val api     = ApiWorkloadConfig.getOnly(18.0)
      val latency = LogNormalSampler.constant(mu = math.log(0.2), sigma = 0.4)
      val r       = run(api, cap = 12, seed = 1L, ticks = 60L, reqTicks = 40L, edgeLatency = latency)
      throttleCount(r) should be > 0
      r.residue shouldBe ResidueSummary(0L, 0L)
      r.responses.size shouldBe requestCount(api, seed = 1L, reqTicks = 40L)
    }

    "be deterministic with a latency gate in the stack" in {
      val latency = LogNormalSampler.constant(mu = math.log(0.2), sigma = 0.4)
      run(ApiWorkloadConfig.getOnly(18.0), cap = 12, seed = 9L, ticks = 40L, edgeLatency = latency) shouldBe
        run(ApiWorkloadConfig.getOnly(18.0), cap = 12, seed = 9L, ticks = 40L, edgeLatency = latency)
    }
  }
