package stochastacy.examples.thermostatfleet

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbManagementEvent, DynamoDbTable, ReconfigurationSchedule}
import stochastacy.sim.SimTime

class ThermostatFleetScenarioConfigSpec extends AnyWordSpec with should.Matchers:

  "ThermostatFleetScenarioConfig" should {

    "provide a coherent singleRegionDefault preset" in {
      val config = ThermostatFleetScenarioConfig.singleRegionDefault

      config.scenarioId shouldBe "thermostat-fleet-single-region"
      config.simulationTicks should be > 0L
      config.trialCount should be > 0
      config.parallelism should be > 0
      config.tableName shouldBe "device-telemetry"
      config.regions should have size 1
      config.regions.head.regionName shouldBe "us-east-1"
      config.regions.head.initialDeviceCount should be > 0L
      config.telemetryReportsPerDevicePerTick should be > 0.0
      config.isMultiRegion shouldBe false
    }

    "provide a coherent multiRegionDefault preset" in {
      val config = ThermostatFleetScenarioConfig.multiRegionDefault

      config.scenarioId shouldBe "thermostat-fleet-multi-region"
      config.simulationTicks should be > 0L
      config.regions should have size 3
      config.regions.map(_.regionName) should contain allOf ("us-east-1", "eu-west-1", "ap-southeast-1")
      config.isMultiRegion shouldBe true
      config.totalInitialDeviceCount should be > 0L
    }

    "reject empty scenarioId" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(scenarioId = "")
      thrown.getMessage should include("scenarioId")
    }

    "reject zero simulationTicks" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(simulationTicks = 0L)
      thrown.getMessage should include("simulationTicks")
    }

    "reject zero trialCount" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(trialCount = 0)
      thrown.getMessage should include("trialCount")
    }

    "reject empty regions" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(regions = Vector.empty)
      thrown.getMessage should include("regions")
    }

    "reject duplicate region names" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(
          regions = Vector(
            RegionFleetConfig("us-east-1", 1000L, 0.0),
            RegionFleetConfig("us-east-1", 2000L, 0.0)
          )
        )
      thrown.getMessage should include("distinct")
    }

    "reject non-positive telemetryReportsPerDevicePerTick" in {
      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(telemetryReportsPerDevicePerTick = 0.0)
      thrown.getMessage should include("telemetryReportsPerDevicePerTick")
    }

    "reject alertStormProbabilityPerTick outside [0,1]" in {
      the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(alertStormProbabilityPerTick = 1.5)
    }

    "reject morningSpikePeakMultiplier below 1.0" in {
      the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(morningSpikePeakMultiplier = 0.5)
    }

    "accept itemCollectionSizeLimitBytes = None" in {
      noException shouldBe thrownBy {
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(itemCollectionSizeLimitBytes = None)
      }
    }

    "reject non-positive itemCollectionSizeLimitBytes when defined" in {
      the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(itemCollectionSizeLimitBytes = Some(0L))
    }

    "accept a valid reconfiguration schedule" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(10L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      noException shouldBe thrownBy {
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(
          simulationTicks = 100L,
          reconfigurationSchedule = Some(schedule)
        )
      }
    }

    "reject an invalid reconfiguration schedule" in {
      val invalidSchedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.UpdateProvisionedCapacity(
            SimTime.of(10L),
            "scale-up",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      val thrown = the[IllegalArgumentException] thrownBy
        ThermostatFleetScenarioConfig.singleRegionDefault.copy(
          simulationTicks = 100L,
          reconfigurationSchedule = Some(invalidSchedule)
        )
      thrown.getMessage should include("provisioned billing mode")
    }

    "accept the same shared schedule shape for multi-region configs" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(10L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      noException shouldBe thrownBy {
        ThermostatFleetScenarioConfig.multiRegionDefault.copy(
          simulationTicks = 100L,
          reconfigurationSchedule = Some(schedule)
        )
      }
    }
  }

  "RegionFleetConfig" should {

    "reject empty regionName" in {
      the[IllegalArgumentException] thrownBy RegionFleetConfig("", 1000L, 0.0)
    }

    "reject negative initialDeviceCount" in {
      the[IllegalArgumentException] thrownBy RegionFleetConfig("us-east-1", -1L, 0.0)
    }

    "reject negative deviceGrowthPerTick" in {
      the[IllegalArgumentException] thrownBy RegionFleetConfig("us-east-1", 1000L, -0.1)
    }

    "allow zero initialDeviceCount and zero growth" in {
      noException shouldBe thrownBy {
        RegionFleetConfig("us-east-1", 0L, 0.0)
      }
    }
  }
