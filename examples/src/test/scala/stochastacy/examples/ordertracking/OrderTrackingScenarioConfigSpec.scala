package stochastacy.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class OrderTrackingScenarioConfigSpec extends AnyWordSpec with should.Matchers:

  "OrderTrackingScenarioConfig" should {
    "provide a coherent phase-1 default" in {
      val config = OrderTrackingScenarioConfig.phase1Default

      config.scenarioId shouldBe "order-tracking-phase1"
      config.simulationTicks should be > 0L
      config.trialCount should be > 0
      config.parallelism should be > 0
      config.createRatePerTick should be > 0.0
      config.fetchRatePerTick should be > 0.0
      config.updateRatePerTick should be > 0.0
      config.deleteRatePerTick should be > 0.0
      config.tableName shouldBe "orders"
    }

    "reject invalid obvious values" in {
      val thrown = the[IllegalArgumentException] thrownBy {
        OrderTrackingScenarioConfig.phase1Default.copy(
          simulationTicks = 0L
        )
      }

      thrown.getMessage should include("simulationTicks")
    }
  }
