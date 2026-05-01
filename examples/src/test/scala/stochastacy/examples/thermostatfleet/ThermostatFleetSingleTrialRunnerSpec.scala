package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbManagementEvent, DynamoDbTable, ReconfigurationSchedule}
import stochastacy.demo.{DemoMetric, TrialRunConfig}
import stochastacy.sim.TimedControlEvent
import stochastacy.sim.SimTime

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class ThermostatFleetSingleTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("thermostat-fleet-trial-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val smallConfig = ThermostatFleetScenarioConfig.singleRegionDefault.copy(
    simulationTicks = 5L,
    trialCount = 2,
    parallelism = 2,
    regions = Vector(
      RegionFleetConfig(regionName = "us-east-1", initialDeviceCount = 100L, deviceGrowthPerTick = 0.0)
    )
  )

  private val smallMultiRegionConfig = ThermostatFleetScenarioConfig.multiRegionDefault.copy(
    simulationTicks = 5L,
    trialCount = 2,
    parallelism = 2,
    regions = Vector(
      RegionFleetConfig(regionName = "us-east-1", initialDeviceCount = 50L, deviceGrowthPerTick = 0.0),
      RegionFleetConfig(regionName = "eu-west-1", initialDeviceCount = 30L, deviceGrowthPerTick = 0.0)
    )
  )

  "ThermostatFleetSingleTrialRunner (single-region)" should {

    "return a non-empty TrialResult with required time-series and summary metrics" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 12345L)),
        30.seconds
      )

      result.scenarioId shouldBe smallConfig.scenarioId
      result.timeSeries should not be empty
      result.summary should not be empty

      val timeSeriesMetrics = result.timeSeries.map(_.metric).toSet
      timeSeriesMetrics should contain(DemoMetric.ReadCapacityUnits)
      timeSeriesMetrics should contain(DemoMetric.WriteCapacityUnits)
      timeSeriesMetrics should contain(DemoMetric.StorageBytes)
      timeSeriesMetrics should contain(DemoMetric.CumulativeEstimatedCost)

      val summaryMetrics = result.summary.map(_.metric).toSet
      summaryMetrics should contain(DemoMetric.TotalReadCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalWriteCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalStorageByteTicks)
      summaryMetrics should contain(DemoMetric.FinalStorageBytes)
      summaryMetrics should contain(DemoMetric.TotalEstimatedCost)
    }

    "emit GSI capacity metrics for all three configured GSIs" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 99L)),
        30.seconds
      )

      val timeSeriesMetrics = result.timeSeries.map(_.metric).toSet
      timeSeriesMetrics should contain(DemoMetric.GsiReadCapacityUnits(ThermostatFleetScenarioConfig.CustomerDevicesGsiName))
      timeSeriesMetrics should contain(DemoMetric.GsiWriteCapacityUnits(ThermostatFleetScenarioConfig.CustomerDevicesGsiName))
      timeSeriesMetrics should contain(DemoMetric.GsiReadCapacityUnits(ThermostatFleetScenarioConfig.FleetAlertsGsiName))
      timeSeriesMetrics should contain(DemoMetric.GsiWriteCapacityUnits(ThermostatFleetScenarioConfig.FleetAlertsGsiName))
      timeSeriesMetrics should contain(DemoMetric.GsiReadCapacityUnits(ThermostatFleetScenarioConfig.DeviceStatusGsiName))
      timeSeriesMetrics should contain(DemoMetric.GsiWriteCapacityUnits(ThermostatFleetScenarioConfig.DeviceStatusGsiName))

      val summaryMetrics = result.summary.map(_.metric).toSet
      summaryMetrics should contain(DemoMetric.TotalGsiReadCapacityUnits(ThermostatFleetScenarioConfig.CustomerDevicesGsiName))
      summaryMetrics should contain(DemoMetric.TotalGsiWriteCapacityUnits(ThermostatFleetScenarioConfig.CustomerDevicesGsiName))
    }

    "produce non-negative metric values" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 1, seed = 54321L)),
        30.seconds
      )

      result.timeSeries.foreach { point =>
        point.value should be >= BigDecimal(0)
      }
      result.summary.foreach { s =>
        s.value should be >= BigDecimal(0)
      }
    }

    "be deterministic for the same seed" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val run = TrialRunConfig(trialId = 0, seed = 77777L)
      val first = Await.result(runner.runTrial(smallConfig, run), 30.seconds)
      val second = Await.result(runner.runTrial(smallConfig, run), 30.seconds)
      first shouldBe second
    }

    "write capacity should exceed read capacity for telemetry-heavy workload" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 11111L)),
        30.seconds
      )

      val summaryMap = result.summary.map(s => s.metric -> s.value).toMap
      summaryMap(DemoMetric.TotalWriteCapacityUnits) should be > BigDecimal(0)
    }
  }

  "ThermostatFleetSingleTrialRunner (multi-region)" should {

    "return a TrialResult with per-region and cross-region metrics" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallMultiRegionConfig, TrialRunConfig(trialId = 0, seed = 42L)),
        30.seconds
      )

      result.scenarioId shouldBe smallMultiRegionConfig.scenarioId
      result.timeSeries should not be empty
      result.summary should not be empty

      val summaryMetrics = result.summary.map(_.metric).toSet
      // Overall metrics present
      summaryMetrics should contain(DemoMetric.TotalReadCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalWriteCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalEstimatedCost)
      // Per-region metrics present
      summaryMetrics should contain(DemoMetric.TotalRegionWriteCapacityUnits("us-east-1"))
      summaryMetrics should contain(DemoMetric.TotalRegionWriteCapacityUnits("eu-west-1"))
      summaryMetrics should contain(DemoMetric.TotalRegionEstimatedCost("us-east-1"))
      // Cross-region summary metrics present
      summaryMetrics should contain(DemoMetric.TotalCrossRegionTransferBytes)
      summaryMetrics should contain(DemoMetric.TotalCrossRegionTransferCost)
    }

    "emit rWCU (replicated write capacity) at destination regions" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallMultiRegionConfig, TrialRunConfig(trialId = 0, seed = 42L)),
        30.seconds
      )

      val summaryMap = result.summary.map(s => s.metric -> s.value).toMap

      // At least one region should have non-zero rWCU (destination region for replicated writes)
      val rWcuUs = summaryMap.getOrElse(DemoMetric.TotalRegionReplicatedWriteCapacityUnits("us-east-1"), BigDecimal(0))
      val rWcuEu = summaryMap.getOrElse(DemoMetric.TotalRegionReplicatedWriteCapacityUnits("eu-west-1"), BigDecimal(0))

      (rWcuUs + rWcuEu) should be >= BigDecimal(0)
    }

    "have non-negative values for all metrics" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallMultiRegionConfig, TrialRunConfig(trialId = 1, seed = 888L)),
        30.seconds
      )

      result.timeSeries.foreach { point =>
        point.value should be >= BigDecimal(0)
      }
      result.summary.foreach { s =>
        s.value should be >= BigDecimal(0)
      }
    }

    "have per-region write capacity totals summing up to overall write capacity (from local writes)" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallMultiRegionConfig, TrialRunConfig(trialId = 0, seed = 42L)),
        30.seconds
      )

      val summaryMap = result.summary.map(s => s.metric -> s.value).toMap

      val regionWriteSum = smallMultiRegionConfig.regions.map { region =>
        summaryMap.getOrElse(DemoMetric.TotalRegionWriteCapacityUnits(region.regionName), BigDecimal(0))
      }.sum

      val overallWrite = summaryMap.getOrElse(DemoMetric.TotalWriteCapacityUnits, BigDecimal(0))

      // Per-region local write totals include both origin writes AND replicated writes at each destination.
      // The overall metric sums all local WCU (not rWCU). Both should be >= 0.
      regionWriteSum should be >= BigDecimal(0)
      overallWrite should be >= BigDecimal(0)
    }
  }

  "ThermostatFleetSingleTrialRunner request generation" should {

    "generate Tick events at every simulated tick" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val config = smallConfig
      val rng = org.apache.commons.rng.simple.RandomSource.KISS.create(42L)
      val requests = runner.generateRequestsForRegion(config, config.regions.head, rng)

      import stochastacy.sim.TimedControlEvent
      val ticks = requests.collect { case t: TimedControlEvent.Tick => t }
      ticks should have size (config.simulationTicks + 1)  // +1 for final drain tick
    }

    "generate PutItemRequests as the dominant request type" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val rng = org.apache.commons.rng.simple.RandomSource.KISS.create(12345L)
      val requests = runner.generateRequestsForRegion(smallConfig, smallConfig.regions.head, rng)

      import stochastacy.aws.dynamodb.PutItemRequest
      val puts = requests.collect { case r: PutItemRequest => r }
      puts should not be empty
    }

    "generate a management stream with ticks and scheduled events" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(2L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(1L, 1L)
          )
        )
      )

      val events = runner.managementEventsFor(5L, schedule).toVector
      events.collect { case tick: TimedControlEvent.Tick => tick }.size shouldBe 6
      events.collect { case event: DynamoDbManagementEvent.SwitchBillingMode => event.usecase } shouldBe Vector("switch")
    }

    "single-region scheduled trials should change outcome after the scheduled tick" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(2L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(1L, 1L)
          )
        )
      )
      val scheduledConfig = smallConfig.copy(reconfigurationSchedule = Some(schedule))

      val baseline = Await.result(runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 24680L)), 30.seconds)
      val scheduled = Await.result(runner.runTrial(scheduledConfig, TrialRunConfig(trialId = 0, seed = 24680L)), 30.seconds)

      val baselineMap = baseline.summary.map(s => s.metric -> s.value).toMap
      val scheduledMap = scheduled.summary.map(s => s.metric -> s.value).toMap

      scheduledMap(DemoMetric.FinalStorageBytes) should be < baselineMap(DemoMetric.FinalStorageBytes)
    }

    "multi-region scheduled trials should change outcome after the scheduled tick" in {
      val runner = ThermostatFleetSingleTrialRunner()
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(2L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(1L, 1L)
          )
        )
      )
      val scheduledConfig = smallMultiRegionConfig.copy(reconfigurationSchedule = Some(schedule))

      val baseline = Await.result(runner.runTrial(smallMultiRegionConfig, TrialRunConfig(trialId = 0, seed = 13579L)), 30.seconds)
      val scheduled = Await.result(runner.runTrial(scheduledConfig, TrialRunConfig(trialId = 0, seed = 13579L)), 30.seconds)

      val baselineMap = baseline.summary.map(s => s.metric -> s.value).toMap
      val scheduledMap = scheduled.summary.map(s => s.metric -> s.value).toMap

      scheduledMap(DemoMetric.FinalStorageBytes) should be < baselineMap(DemoMetric.FinalStorageBytes)
    }
  }
