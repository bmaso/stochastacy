package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.funsuite.AsyncFunSuite
import stochastacy.demo.{DemoMetric, TrialResult, TrialRunConfig}

import scala.concurrent.{ExecutionContext, Future}

class ThermostatFleetCapstoneSingleTrialRunnerSpec extends AsyncFunSuite:
  given system: ActorSystem = ActorSystem("ThermostatFleetCapstoneSingleTrialRunnerSpec")
  given mat: Materializer   = Materializer.matFromSystem
  given ec: ExecutionContext = system.dispatcher

  private val config = ThermostatFleetCapstoneConfig.capstoneDefault.copy(
    trialCount      = 1,
    parallelism     = 1,
    simulationTicks = 60L,
    tables = ThermostatFleetCapstoneConfig.capstoneDefault.tables.map { entry =>
      entry.copy(config = entry.config.copy(
        simulationTicks = 60L,
        regions = entry.config.regions.map(_.copy(initialDeviceCount = 3000L, deviceGrowthPerTick = 0.0))
      ))
    }
  )
  private val runner = ThermostatFleetCapstoneSingleTrialRunner()

  // Run once; all tests share the same result Future.
  private val sharedResultF: Future[TrialResult] = runner.runTrial(config, TrialRunConfig(0, 42L))

  test("graph completes and returns a TrialResult") {
    sharedResultF.map { result =>
      assert(result.scenarioId == ThermostatFleetCapstoneConfig.ScenarioId)
      assert(result.trialId == 0)
      assert(result.timeSeries.nonEmpty)
    }
  }

  test("time-series contains Table:*:* metric names for all four tables") {
    sharedResultF.map { result =>
      val metricNames = result.timeSeries.map(_.metric.exportName).toSet
      val expectedTables = Seq(
        ThermostatFleetCapstoneConfig.DeviceRegistryTableName,
        ThermostatFleetCapstoneConfig.DeviceTelemetryTableName,
        ThermostatFleetCapstoneConfig.DeviceCommandsTableName,
        ThermostatFleetCapstoneConfig.DeviceAlertsTableName
      )
      val missing = expectedTables.filterNot(t => metricNames.exists(_.startsWith(s"Table:$t:")))
      assert(missing.isEmpty, s"no metrics found for tables: ${missing.mkString(", ")}")
    }
  }

  test("provisioned telemetry table emits ProvisionedReadCapacityUnits and ProvisionedWriteCapacityUnits") {
    sharedResultF.map { result =>
      val telemetryName = ThermostatFleetCapstoneConfig.DeviceTelemetryTableName
      val provRcu = result.timeSeries.filter(_.metric == DemoMetric.TableProvisionedReadCapacityUnits(telemetryName))
      val provWcu = result.timeSeries.filter(_.metric == DemoMetric.TableProvisionedWriteCapacityUnits(telemetryName))
      assert(provRcu.nonEmpty, "expected TableProvisionedReadCapacityUnits for device-telemetry")
      assert(provWcu.nonEmpty, "expected TableProvisionedWriteCapacityUnits for device-telemetry")
    }
  }

  test("TTL-enabled telemetry table emits TableEstimatedItemCount") {
    sharedResultF.map { result =>
      val telemetryName = ThermostatFleetCapstoneConfig.DeviceTelemetryTableName
      val estItemPts = result.timeSeries.filter(_.metric == DemoMetric.TableEstimatedItemCount(telemetryName))
      assert(estItemPts.nonEmpty, "expected TableEstimatedItemCount for device-telemetry (TTL-enabled)")
    }
  }
