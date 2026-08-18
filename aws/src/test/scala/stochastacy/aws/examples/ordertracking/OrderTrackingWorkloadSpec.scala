package stochastacy.aws.examples.ordertracking

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{DeleteItemRequest, DynamoDbRequest, GetItemRequest, PutItemRequest, UpdateItemRequest}
import stochastacy.core.component.Timed
import stochastacy.sim.ticks

class OrderTrackingWorkloadSpec extends AnyWordSpec with should.Matchers:

  private def run(config: OrderTrackingConfig, seed: Long): Vector[Timed[DynamoDbRequest]] =
    OrderTrackingWorkload.arrivals(config, RandomSource.KISS.create(seed))

  // A long run so per-flow empirical means are stable.
  private val longConfig = OrderTrackingConfig.phase1Default.copy(simulationTicks = 4000L)

  "OrderTrackingWorkload.arrivals" should {

    "produce per-flow counts whose per-tick means match the configured Poisson rates" in {
      val arrivals = run(longConfig, seed = 1L)
      val ticks    = longConfig.simulationTicks.toDouble

      def meanPerTick(pf: DynamoDbRequest => Boolean): Double = arrivals.count(a => pf(a.event)) / ticks

      meanPerTick(_ == GetItemRequest)               shouldBe (2.5 +- 0.1)
      meanPerTick(_.isInstanceOf[PutItemRequest])    shouldBe (0.8 +- 0.1)
      meanPerTick(_.isInstanceOf[UpdateItemRequest]) shouldBe (1.2 +- 0.1)
      meanPerTick(_ == DeleteItemRequest)            shouldBe (0.4 +- 0.1)
    }

    "size put and update items within their uniform byte ranges" in {
      val arrivals = run(longConfig, seed = 2L)

      val putBytes = arrivals.collect { case Timed(PutItemRequest(b), _, _, _) => b }
      putBytes should not be empty
      all(putBytes) should (be >= 672L and be <= 1120L)

      val updBytes = arrivals.collect { case Timed(UpdateItemRequest(b), _, _, _) => b }
      updBytes should not be empty
      all(updBytes) should (be >= 768L and be <= 1280L)
    }

    "land every event within [1, simulationTicks] with an intra-tick position in [0, 1)" in {
      val config   = OrderTrackingConfig.phase1Default.copy(simulationTicks = 50L)
      val arrivals = run(config, seed = 3L)
      arrivals should not be empty
      all(arrivals.map(_.eventTime.ticks)) should (be >= 1L and be <= 50L)
      all(arrivals.map(_.intraTick))       should (be >= 0.0 and be < 1.0)
    }

    "emit events in non-decreasing conceptual-time order" in {
      val arrivals = run(longConfig, seed = 4L)
      val times    = arrivals.map(a => a.eventTime.ticks.toDouble + a.intraTick)
      times shouldBe times.sorted
    }

    "tag every event with the scenario id" in {
      val arrivals = run(longConfig.copy(simulationTicks = 20L), seed = 5L)
      all(arrivals.map(_.usecase)) shouldBe longConfig.scenarioId
    }

    "be deterministic under a fixed seed" in {
      run(longConfig, seed = 7L) shouldBe run(longConfig, seed = 7L)
    }
  }
