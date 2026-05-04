package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.pricing.{DynamoDbPricingRates, ReservedCapacity}
import stochastacy.demo.{DemoMetric, TrialRunConfig}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class ThermostatFleetMixedModeSingleTrialRunnerSpec
    extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem    = ActorSystem("mixed-mode-trial-test")
  given Materializer   = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val smallConfig = ThermostatFleetMixedModeConfig(
    simulationTicks    = 10L,
    trialCount         = 2,
    parallelism        = 2,
    modeSwitchTick     = 4L,
    capacityAdjustTick = 8L
  )

  "ThermostatFleetMixedModeSingleTrialRunner" should {

    "return a non-empty TrialResult with core time-series and summary metrics" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 12345L)),
        30.seconds
      )

      result.scenarioId shouldBe smallConfig.scenarioId
      result.timeSeries should not be empty
      result.summary    should not be empty

      val tsMetrics = result.timeSeries.map(_.metric).toSet
      tsMetrics should contain(DemoMetric.ReadCapacityUnits)
      tsMetrics should contain(DemoMetric.WriteCapacityUnits)
      tsMetrics should contain(DemoMetric.StorageBytes)
      tsMetrics should contain(DemoMetric.CumulativeEstimatedCost)

      val summaryMetrics = result.summary.map(_.metric).toSet
      summaryMetrics should contain(DemoMetric.TotalReadCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalWriteCapacityUnits)
      summaryMetrics should contain(DemoMetric.TotalEstimatedCost)
    }

    "emit ProvisionedReadCapacityUnits and ProvisionedWriteCapacityUnits after mode switch" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 42L)),
        30.seconds
      )

      val provWcuPoints = result.timeSeries.filter(_.metric == DemoMetric.ProvisionedWriteCapacityUnits)
      val provRcuPoints = result.timeSeries.filter(_.metric == DemoMetric.ProvisionedReadCapacityUnits)

      provWcuPoints should not be empty
      provRcuPoints should not be empty
      // Provisioned points must appear well before the end of the simulation (i.e. the switch took effect)
      provWcuPoints.map(_.tick).min should be < smallConfig.simulationTicks
    }

    "emit no ProvisionedReadCapacityUnits in pure on-demand phase (first 2 ticks)" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 99L)),
        30.seconds
      )

      // On-demand mode runs before the switch; the very earliest ticks must be free of provisioned metrics.
      // modeSwitchTick=4, so ticks 1-2 are definitely in on-demand mode regardless of any off-by-one timing.
      val earlyProvPoints = result.timeSeries.filter { p =>
        p.metric == DemoMetric.ProvisionedReadCapacityUnits && p.tick <= 2L
      }
      earlyProvPoints shouldBe empty
    }

    "emit BillingModeIndicator points" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 77L)),
        30.seconds
      )

      val modePoints = result.timeSeries.filter(_.metric == DemoMetric.BillingModeIndicator)
      modePoints should not be empty
    }

    "BillingModeIndicator contains code 1 (provisioned) after the mode switch" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 55L)),
        30.seconds
      )

      val modeCodes = result.timeSeries
        .filter(_.metric == DemoMetric.BillingModeIndicator)
        .map(_.value)
        .toSet

      // BillingModeIndicator is derived from the config schedule (not stream events),
      // so both on-demand (0) and provisioned (1) codes must appear.
      modeCodes should contain(BigDecimal(0))
      modeCodes should contain(BigDecimal(1))
      modeCodes.foreach { code => code should (be(BigDecimal(0)) or be(BigDecimal(1))) }
    }

    "emit ThrottleCount points for ticks that have metric data" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 0, seed = 33L)),
        30.seconds
      )

      // ThrottleCount is emitted for ticks that have at least one admission metric event
      // (ProvisionedCapacityUtilization or RequestThrottled).
      val throttleTicks = result.timeSeries
        .filter(_.metric == DemoMetric.ThrottleCount)
        .map(_.tick)
        .toSet

      throttleTicks should not be empty
      throttleTicks.foreach { t => t should be >= 1L }
    }

    "produce non-negative values for all metrics" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallConfig, TrialRunConfig(trialId = 1, seed = 54321L)),
        30.seconds
      )

      result.timeSeries.foreach { p =>
        p.value should be >= BigDecimal(0)
      }
      result.summary.foreach { s =>
        s.value should be >= BigDecimal(0)
      }
    }

    "be deterministic for the same seed" in {
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val run    = TrialRunConfig(trialId = 0, seed = 77777L)
      val first  = Await.result(runner.runTrial(smallConfig, run), 30.seconds)
      val second = Await.result(runner.runTrial(smallConfig, run), 30.seconds)
      first shouldBe second
    }

    "produce lower TotalEstimatedCost with reserved capacity covering all provisioned base-table capacity" in {
      val baseConfig = ThermostatFleetMixedModeConfig(
        simulationTicks    = 10L,
        trialCount         = 2,
        parallelism        = 2,
        modeSwitchTick     = 3L,
        capacityAdjustTick = 7L,
        initialProvisionedRcu  = 100L,
        initialProvisionedWcu  = 100L,
        adjustedProvisionedRcu = 100L,
        adjustedProvisionedWcu = 100L
      )
      val reservedRates = DynamoDbPricingRates.phase1Default.copy(
        reservedCapacity = Some(ReservedCapacity(
          reservedReadCapacityUnits       = 100L,
          reservedWriteCapacityUnits      = 100L,
          discountedReadCapacityUnitPrice  = DynamoDbPricingRates.awsDefaultStandard.readCapacityUnitPrice  / 2,
          discountedWriteCapacityUnitPrice = DynamoDbPricingRates.awsDefaultStandard.writeCapacityUnitPrice / 2
        ))
      )
      val runner = ThermostatFleetMixedModeSingleTrialRunner()
      val run    = TrialRunConfig(trialId = 1, seed = 42L)
      val withoutReserved = Await.result(runner.runTrial(baseConfig, run), 30.seconds)
      val withReserved    = Await.result(runner.runTrial(baseConfig.copy(pricingRates = reservedRates), run), 30.seconds)

      val costWithout = withoutReserved.summary.find(_.metric == DemoMetric.TotalEstimatedCost).get.value
      val costWith    = withReserved.summary.find(_.metric == DemoMetric.TotalEstimatedCost).get.value
      costWith should be < costWithout
    }
  }
