package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbManagementEvent, DynamoDbTable}
import stochastacy.sim.ticks

class ThermostatFleetMixedModeConfigSpec extends AnyWordSpec with should.Matchers:

  "ThermostatFleetMixedModeConfig" should {

    "produce default values matching the right-sizing trap scenario" in {
      val config = ThermostatFleetMixedModeConfig()
      config.initialProvisionedWcu  shouldBe 125L
      config.adjustedProvisionedWcu shouldBe 333L
      config.initialProvisionedRcu  shouldBe 250L
      config.adjustedProvisionedRcu shouldBe 100L
      config.modeSwitchTick         shouldBe 400L
      config.capacityAdjustTick     shouldBe 800L
      config.simulationTicks        shouldBe 1200L
    }

    "toScenarioConfig produces a schedule with two events at the correct ticks" in {
      val mmConfig   = ThermostatFleetMixedModeConfig()
      val scenario   = mmConfig.toScenarioConfig
      val schedule   = scenario.reconfigurationSchedule.get
      val events     = schedule.events

      events should have size 2

      val switch = events.head.asInstanceOf[DynamoDbManagementEvent.SwitchBillingMode]
      switch.eventTime.ticks shouldBe mmConfig.modeSwitchTick
      switch.newMode shouldBe DynamoDbTable.BillingMode.Provisioned(
        mmConfig.initialProvisionedRcu, mmConfig.initialProvisionedWcu
      )

      val adjust = events(1).asInstanceOf[DynamoDbManagementEvent.UpdateProvisionedCapacity]
      adjust.eventTime.ticks shouldBe mmConfig.capacityAdjustTick
      adjust.newCapacity shouldBe DynamoDbTable.BillingMode.Provisioned(
        mmConfig.adjustedProvisionedRcu, mmConfig.adjustedProvisionedWcu
      )
    }

    "toScenarioConfig starts in on-demand billing mode" in {
      val scenario = ThermostatFleetMixedModeConfig().toScenarioConfig
      scenario.billingMode shouldBe a[DynamoDbTable.BillingMode.OnDemand]
    }

    "toScenarioConfig copies simulation dimensions from the mixed-mode config" in {
      val mmConfig = ThermostatFleetMixedModeConfig(
        simulationTicks    = 600L,
        modeSwitchTick     = 200L,
        capacityAdjustTick = 400L,
        trialCount         = 10,
        parallelism        = 2
      )
      val scenario = mmConfig.toScenarioConfig
      scenario.simulationTicks shouldBe 600L
      scenario.trialCount      shouldBe 10
      scenario.parallelism     shouldBe 2
    }

    "reject modeSwitchTick >= simulationTicks" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetMixedModeConfig(simulationTicks = 100L, modeSwitchTick = 100L, capacityAdjustTick = 100L)
      thrown.getMessage should include("modeSwitchTick")
    }

    "reject capacityAdjustTick <= modeSwitchTick" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetMixedModeConfig(modeSwitchTick = 400L, capacityAdjustTick = 400L)
      thrown.getMessage should include("capacityAdjustTick")
    }

    "reject zero initialProvisionedRcu" in {
      the[IllegalArgumentException] thrownBy ThermostatFleetMixedModeConfig(initialProvisionedRcu = 0L)
    }

    "reject zero adjustedProvisionedWcu" in {
      the[IllegalArgumentException] thrownBy ThermostatFleetMixedModeConfig(adjustedProvisionedWcu = 0L)
    }

    "accept valid non-default capacity values" in {
      noException shouldBe thrownBy {
        ThermostatFleetMixedModeConfig(
          simulationTicks       = 120L,
          modeSwitchTick        = 40L,
          capacityAdjustTick    = 80L,
          initialProvisionedRcu = 5L,
          initialProvisionedWcu = 20L,
          adjustedProvisionedRcu = 5L,
          adjustedProvisionedWcu = 50L
        )
      }
    }
  }
