package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.funsuite.AsyncFunSuite
import stochastacy.demo.{DemoMetric, TrialRunConfig}

import scala.concurrent.{ExecutionContext, Future}

class ThermostatFleetCapstoneTransactionSpec extends AsyncFunSuite:
  given system: ActorSystem = ActorSystem("ThermostatFleetCapstoneTransactionSpec")
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

  test("device-commands table config has transactWriteItemsPerItemBytes set") {
    val commandsEntry = config.tables.find(_.tableName == ThermostatFleetCapstoneConfig.DeviceCommandsTableName)
    assert(commandsEntry.isDefined, "device-commands entry not found")
    val commandsConfig = commandsEntry.get.config
    assert(commandsConfig.transactWriteItemsPerItemBytes.isDefined,
      "device-commands table should use transactional writes")
    assert(commandsConfig.transactWriteItemsPerItemBytes.get == Vector(200L, 150L),
      s"expected item bytes Vector(200, 150), got ${commandsConfig.transactWriteItemsPerItemBytes}")
  }

  test("device-commands simulation emits WriteCapacityConsumed metrics (transactions billed at 2× WCU)") {
    val runner = ThermostatFleetCapstoneSingleTrialRunner()
    runner.runTrial(config, TrialRunConfig(0, 42L)).map { result =>
      val commandsName = ThermostatFleetCapstoneConfig.DeviceCommandsTableName
      val wcuMetrics = result.timeSeries.filter(_.metric == DemoMetric.TableWriteCapacityUnits(commandsName))
      assert(wcuMetrics.nonEmpty,
        s"expected ConsumedWriteCapacityUnits metrics for $commandsName (transactional writes should produce WCU events)")
      val totalWcu = wcuMetrics.map(_.value).sum
      assert(totalWcu > BigDecimal(0), s"expected positive total WCU for $commandsName, got $totalWcu")
    }
  }
