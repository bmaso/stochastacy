package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.demo.{DemoExportRecord, DemoJsonlExporter, DemoMetric, DemoReportBuilder, FutureMultiTrialExecutor, TrialExecutionConfig}
import stochastacy.examples.ordertracking.{BatchMetadata, OrderTrackingPostgresBridge}

import java.nio.file.Files
import java.sql.DriverManager
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class ThermostatFleetDemoIntegrationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("thermostat-fleet-integration-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 60.seconds)
    super.afterAll()

  private val tinyConfig = ThermostatFleetScenarioConfig.singleRegionDefault.copy(
    simulationTicks = 4L,
    trialCount = 3,
    parallelism = 1,
    regions = Vector(
      RegionFleetConfig(regionName = "us-east-1", initialDeviceCount = 20L, deviceGrowthPerTick = 0.0)
    )
  )

  private val tinyMultiRegionConfig = ThermostatFleetScenarioConfig.multiRegionDefault.copy(
    simulationTicks = 4L,
    trialCount = 2,
    parallelism = 1,
    regions = Vector(
      RegionFleetConfig(regionName = "us-east-1", initialDeviceCount = 20L, deviceGrowthPerTick = 0.0),
      RegionFleetConfig(regionName = "eu-west-1", initialDeviceCount = 10L, deviceGrowthPerTick = 0.0)
    )
  )

  "ThermostatFleet single-region integration" should {

    "run multi-trial generate and produce a DemoExportBundle with expected record families" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val executor = FutureMultiTrialExecutor[ThermostatFleetScenarioConfig](runner)

      val trials = Await.result(
        executor.runTrials(
          config = tinyConfig,
          exec = TrialExecutionConfig(
            trialCount = tinyConfig.trialCount,
            parallelism = tinyConfig.parallelism,
            baseSeed = ThermostatFleetDemoRunner.BaseSeed
          )
        ),
        60.seconds
      )

      trials should have size tinyConfig.trialCount
      trials.foreach { trial =>
        trial.scenarioId shouldBe tinyConfig.scenarioId
        trial.timeSeries should not be empty
        trial.summary should not be empty
      }

      val bundle = DemoReportBuilder.build(trials)
      bundle.records should not be empty

      val metricNames = bundle.records.collect {
        case r: DemoExportRecord.TrialTimeSeriesRecord => r.metric
        case r: DemoExportRecord.AggregateTimeSeriesRecord => r.metric
        case r: DemoExportRecord.TrialSummaryRecord => r.metric
        case r: DemoExportRecord.AggregateSummaryRecord => r.metric
      }.toSet
      metricNames should contain("WriteCapacityUnits")
      metricNames should contain("ReadCapacityUnits")
      metricNames should contain(s"GSI:${ThermostatFleetScenarioConfig.CustomerDevicesGsiName}:WriteCapacityUnits")
      metricNames should contain(s"GSI:${ThermostatFleetScenarioConfig.DeviceStatusGsiName}:WriteCapacityUnits")
    }

    "stage generated records into H2 and verify record counts" in {
      val tempFile = Files.createTempFile("thermostat-fleet-integration-", ".jsonl")
      val bundle = Await.result(
        ThermostatFleetDemoRunner.run(
          trialCount = 2,
          parallelism = 1,
          simulationTicks = 4L,
          mode = "single-region"
        ),
        60.seconds
      )
      DemoJsonlExporter.write(tempFile, bundle.records)

      val dbUrl = "jdbc:h2:mem:thermostat_fleet_stage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
      val connection = DriverManager.getConnection(dbUrl, "sa", "")
      try
        OrderTrackingPostgresBridge.loadSchema(connection)
      finally
        connection.close()

      val count = OrderTrackingPostgresBridge.stage(
        inputPath = tempFile,
        metadata = BatchMetadata(
          batchId = "thermostat-fleet-test-batch",
          scenarioId = ThermostatFleetScenarioConfig.singleRegionDefault.scenarioId,
          trialCount = 2,
          parallelism = 1,
          simulationTicks = 4L,
          baseSeed = ThermostatFleetDemoRunner.BaseSeed,
          readConsistency = ThermostatFleetScenarioConfig.singleRegionDefault.readConsistency.toString,
          tableName = ThermostatFleetScenarioConfig.singleRegionDefault.tableName,
          sourceJsonlPath = Some(tempFile.toString)
        ),
        dbUrl = dbUrl,
        dbUser = "sa",
        dbPassword = ""
      )

      count should be > 0
    }
  }

  "ThermostatFleet multi-region integration" should {

    "run multi-trial multi-region generate and produce records with per-region and cross-region metrics" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val executor = FutureMultiTrialExecutor[ThermostatFleetScenarioConfig](runner)

      val trials = Await.result(
        executor.runTrials(
          config = tinyMultiRegionConfig,
          exec = TrialExecutionConfig(
            trialCount = tinyMultiRegionConfig.trialCount,
            parallelism = tinyMultiRegionConfig.parallelism,
            baseSeed = ThermostatFleetDemoRunner.BaseSeed
          )
        ),
        120.seconds
      )

      trials should have size tinyMultiRegionConfig.trialCount
      trials.foreach { trial =>
        trial.scenarioId shouldBe tinyMultiRegionConfig.scenarioId
        trial.timeSeries should not be empty
        trial.summary should not be empty

        val summaryMetrics = trial.summary.map(_.metric).toSet
        summaryMetrics should contain(DemoMetric.TotalCrossRegionTransferBytes)
        summaryMetrics should contain(DemoMetric.TotalCrossRegionTransferCost)
        summaryMetrics should contain(DemoMetric.TotalRegionWriteCapacityUnits("us-east-1"))
        summaryMetrics should contain(DemoMetric.TotalRegionWriteCapacityUnits("eu-west-1"))
      }
    }
  }
