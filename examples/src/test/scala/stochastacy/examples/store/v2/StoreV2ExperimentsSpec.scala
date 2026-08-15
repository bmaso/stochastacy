package stochastacy.examples.store.v2

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.InterfaceSampler
import stochastacy.core.component.gate.{ChaosGate, FlatThrottleGate, TokenBucketGate}
import stochastacy.examples.store.{ApiWorkloadConfig, ErrorResult, StoreConfig, StoreRequest, StoreResponse, StoreStatKey}

/** Demo-scale confirmations, in the real store edge, of the two gating results proven rigorously at the
 *  core level: token-bucket burst tolerance (Slice 3) and chaos/throttle orthogonality (Slice 4). */
class StoreV2ExperimentsSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreV2ExperimentsSpec")
  override def afterAll(): Unit = system.terminate()

  private def rate(r: StoreV2TrialResult, metric: String): Double =
    r.stats.get(StoreStatKey("get", metric)).map(_.mean).getOrElse(0.0)

  // --- burst experiment: identical spiky traffic through a flat cap vs. a token bucket ---
  // Every 10th tick spikes to 30 gets, else 2 (mean 4.8/tick); cap and refill are both 5.
  private val spike = SpikeWorkload.gets(t => if t % 10L == 0L then 30 else 2, simulationTicks = 100L)

  private def burstThrottleRate(rateLimiter: InterfaceSampler[?, StoreRequest, StoreResponse]): Double =
    val r = Await.result(
      StoreV2TrialRunner.runArrivals(StoreConfig(), Seq(rateLimiter), spike, seed = 1L, simulationTicks = 120L),
      30.seconds
    )
    rate(r, "outcome.throttled")

  // --- orthogonality experiment: chaos (outermost) + throttle, swept over load ---
  private def orthogonalityRates(load: Int): (Double, Double) =
    val gates = Seq[InterfaceSampler[?, StoreRequest, StoreResponse]](
      ChaosGate.constant[StoreRequest, StoreResponse](0.1, ErrorResult("unavailable")),
      new FlatThrottleGate[StoreRequest, StoreResponse](5, ErrorResult("throttled"))
    )
    val r = Await.result(
      StoreV2TrialRunner.runGates(ApiWorkloadConfig.getOnly(load.toDouble), StoreConfig(), gates, seed = 1L, simulationTicks = 200L),
      30.seconds
    )
    (rate(r, "outcome.chaos"), rate(r, "outcome.throttled"))

  "The burst experiment (flat cap vs. token bucket)" should {

    "throttle far less with a token bucket than a flat cap on the same bursts" in {
      val flatRate   = burstThrottleRate(new FlatThrottleGate[StoreRequest, StoreResponse](5, ErrorResult("throttled")))
      val bucketRate = burstThrottleRate(new TokenBucketGate[StoreRequest, StoreResponse](30, 5, ErrorResult("throttled")))
      flatRate   should be > 0.3          // the flat cap rejects every spike's excess
      bucketRate should be < flatRate
      bucketRate should be < 0.02         // the bucket banks quiet-tick slack and absorbs each spike
    }
  }

  "The orthogonality experiment (chaos vs. throttle)" should {

    "hold the 503 chaos rate ≈ constant while the 429 throttle rate climbs with load" in {
      val (chaosLo, throttleLo) = orthogonalityRates(3)
      val (chaosMd, throttleMd) = orthogonalityRates(8)
      val (chaosHi, throttleHi) = orthogonalityRates(20)

      val chaosRates = Seq(chaosLo, chaosMd, chaosHi)
      chaosRates.foreach { r => r shouldBe (0.1 +- 0.03) }         // load-independent
      (chaosRates.max - chaosRates.min) should be < 0.03

      throttleLo should be < throttleMd                            // load-driven
      throttleMd should be < throttleHi
      (throttleHi - throttleLo) should be > 0.4
    }
  }
