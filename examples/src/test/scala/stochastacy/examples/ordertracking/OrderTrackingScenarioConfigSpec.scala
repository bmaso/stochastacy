package stochastacy.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class OrderTrackingScenarioConfigSpec extends AnyWordSpec with should.Matchers:

  "OrderTrackingScenarioConfig" should {
    "provide a coherent phase-1 default" in {
      val config = OrderTrackingScenarioConfig.phase1Default

      config.scenarioId               shouldBe "order-tracking-phase1"
      config.simulationTicks          should be > 0L
      config.trialCount               should be > 0
      config.parallelism              should be > 0
      config.globalSecondaryIndexNames shouldBe Vector.empty
      config.localSecondaryIndexNames  shouldBe Vector.empty
      config.tableName                shouldBe "orders"
    }

    "provide a coherent phase-2 default" in {
      val config = OrderTrackingScenarioConfig.phase2Default

      config.scenarioId      shouldBe "order-tracking-phase2"
      config.simulationTicks should be > 0L
      config.trialCount      should be > 0
      config.parallelism     should be > 0
      config.tableName       shouldBe "orders"
    }

    "reject invalid obvious values" in {
      val thrown = the[IllegalArgumentException] thrownBy {
        OrderTrackingScenarioConfig.phase2Default.copy(
          simulationTicks = 0L
        )
      }

      thrown.getMessage should include("simulationTicks")
    }

    "toWorkloadDefinition resolves the phase-1 workload to 4 flows" in {
      val defn = OrderTrackingScenarioConfig.phase1Default.toWorkloadDefinition()
      defn.tableName shouldBe "orders"
      defn.usecase   shouldBe "order-tracking-phase1"
      defn.flows     should have size 4
    }

    "toWorkloadDefinition resolves the phase-2 workload to 10 flows" in {
      val defn = OrderTrackingScenarioConfig.phase2Default.toWorkloadDefinition()
      defn.tableName shouldBe "orders"
      defn.usecase   shouldBe "order-tracking-phase2"
      defn.flows     should have size 10  // 4 CRUD (included) + 2 base-table + 4 GSI = 10
    }
  }
