package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{AutoScalingPolicy, BillingMode}
import stochastacy.aws.examples.demo.*

/** The 4-table capstone smoke test: the ensemble runs to completion with per-table metrics, and each table's
 *  metric set reflects its own features — the provisioned + PITR Telemetry table surfaces provisioned,
 *  throttle, PITR and per-GSI metrics that the on-demand tables do not. */
class ThermostatCapstoneSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatCapstoneSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val ticks = 40L
  private val smallPolicy = AutoScalingPolicy(
    targetUtilization = 0.7, evaluationWindowTicks = 3,
    scaleUpReactionDelayTicks = 2, scaleDownReactionDelayTicks = 2,
    scaleUpCooldownTicks = 2, scaleDownCooldownTicks = 2,
    minReadCapacityUnits = 10, maxReadCapacityUnits = 5000, minWriteCapacityUnits = 10, maxWriteCapacityUnits = 5000
  )
  private def base = ThermostatConfig(
    scenarioId = "cap-test", simulationTicks = ticks, trialCount = 1, parallelism = 1,
    initialDeviceCount = 500L, deviceGrowthPerTick = 0.0,
    morningSpikePeakMultiplier = 1.0, eveningSpikePeakMultiplier = 1.0, systemErrorRate = 0.0
  )
  private val config = ThermostatMultiTableConfig(
    scenarioId = "cap-test", simulationTicks = ticks, trialCount = 3, parallelism = 2,
    tableConfigs = Vector(
      "device-registry"  -> base.copy(telemetryReportsPerDevicePerTick = 0.001, customerSupportQueryRatePerTick = 2.0, fleetDashboardScanRatePerTick = 0.2),
      "device-telemetry" -> base.copy(
        telemetryReportsPerDevicePerTick = 0.05, customerSupportQueryRatePerTick = 0.1, fleetDashboardScanRatePerTick = 0.05,
        billingMode = BillingMode.Provisioned(50L, 50L), burstWindowTicks = 10,
        autoScalingPolicy = Some(smallPolicy), ttlPeriodTicks = Some(20), pointInTimeRecoveryEnabled = true
      ),
      "device-commands"  -> base.copy(telemetryReportsPerDevicePerTick = 0.002, customerSupportQueryRatePerTick = 1.0, fleetDashboardScanRatePerTick = 0.0, transactWriteItemsPerItemBytes = Some(Vector(200L, 150L))),
      "device-alerts"    -> base.copy(telemetryReportsPerDevicePerTick = 0.01, customerSupportQueryRatePerTick = 0.5, fleetDashboardScanRatePerTick = 0.1)
    )
  )

  private def run(seed: Long): MultiTableMonteCarloResult =
    Await.result(new MultiTableMonteCarloRunner().run(config, seed), 120.seconds)

  private def table(result: MultiTableMonteCarloResult, name: String): Vector[AggregateSummaryValue] =
    result.perTable.find(_.tableName == name).map(_.aggregateSummary).getOrElse(Vector.empty)
  private def has(summary: Vector[AggregateSummaryValue], metric: String): Boolean = summary.exists(_.metric == metric)
  private def mean(summary: Vector[AggregateSummaryValue], metric: String): BigDecimal =
    summary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  "The Thermostat-fleet capstone, end to end," should {

    "run all four tables to completion with per-table metrics" in {
      val result = run(seed = 1L)
      result.perTable.map(_.tableName) should contain theSameElementsAs Vector("device-registry", "device-telemetry", "device-commands", "device-alerts")
      all(result.perTable.map(t => has(t.aggregateSummary, "TotalWriteCapacityUnits"))) shouldBe true
    }

    "surface the Telemetry table's provisioned, throttle, PITR and per-GSI metrics" in {
      val telemetry = table(run(seed = 1L), "device-telemetry")
      has(telemetry, "TotalProvisionedWriteCapacityUnitTicks")     shouldBe true
      has(telemetry, "TotalThrottledRequests")                     shouldBe true
      has(telemetry, "TotalPitrCost")                              shouldBe true
      has(telemetry, "GSI:customer-devices:TotalWriteCapacityUnits") shouldBe true
      mean(telemetry, "TotalPitrCost") should be > BigDecimal(0)   // PITR bills the accumulating telemetry storage
    }

    "keep provisioned/PITR metrics off the on-demand tables (per-table surfacing)" in {
      val registry = table(run(seed = 1L), "device-registry")
      has(registry, "TotalPitrCost")          shouldBe false
      has(registry, "TotalThrottledRequests") shouldBe false
      mean(table(run(seed = 1L), "device-commands"), "TotalWriteCapacityUnits") should be > BigDecimal(0) // transactions wrote
    }

    "be reproducible under a fixed seed" in {
      run(seed = 7L) shouldBe run(seed = 7L)
    }
  }
