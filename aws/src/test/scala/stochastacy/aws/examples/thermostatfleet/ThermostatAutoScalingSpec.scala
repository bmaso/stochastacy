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

/** The Thermostat-fleet auto-scaling demo end to end: burst + reactive auto-scaling throttles a growing
 *  telemetry load far less than the same reservation held fixed, by scaling the base write capacity up. */
class ThermostatAutoScalingSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatAutoScalingSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // A small, fast policy: react within a few ticks so scaling happens inside a short horizon.
  private val smallPolicy = AutoScalingPolicy(
    targetUtilization = 0.7, evaluationWindowTicks = 3,
    scaleUpReactionDelayTicks = 2, scaleDownReactionDelayTicks = 2,
    scaleUpCooldownTicks = 2, scaleDownCooldownTicks = 2,
    minReadCapacityUnits = 10, maxReadCapacityUnits = 5000,
    minWriteCapacityUnits = 10, maxWriteCapacityUnits = 5000
  )

  // Write-only telemetry (no queries/scans) at a constant, over-ceiling rate: a fixed 30-WCU table
  // throttles every tick; burst + auto-scaling raises capacity to meet it.
  private val autoScaled = ThermostatConfig(
    scenarioId = "autoscale-test", simulationTicks = 40L, trialCount = 3, parallelism = 2,
    initialDeviceCount = 1000L, deviceGrowthPerTick = 0.0,
    customerSupportQueryRatePerTick = 0.0, fleetDashboardScanRatePerTick = 0.0,
    alertStormProbabilityPerTick = 0.0, systemErrorRate = 0.0,
    billingMode = BillingMode.Provisioned(readCapacityUnits = 100L, writeCapacityUnits = 30L),
    autoScalingPolicy = Some(smallPolicy), burstWindowTicks = 5
  )
  private val fixed = autoScaled.copy(scenarioId = "fixed-test", autoScalingPolicy = None, burstWindowTicks = 0)

  private def run(scenario: ThermostatConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(scenario, seed), 120.seconds)

  private def mean(result: MonteCarloResult, metric: String): BigDecimal =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  "The Thermostat-fleet auto-scaling demo, end to end," should {

    "throttle far less than a fixed reservation on the identical workload" in {
      val auto  = run(autoScaled, seed = 1L)
      val fix   = run(fixed, seed = 1L)
      mean(fix, "TotalThrottledRequests")  should be > BigDecimal(0)                    // the fixed table throttles
      mean(auto, "TotalThrottledRequests") should be < mean(fix, "TotalThrottledRequests") // burst + scaling relieve it
    }

    "reserve more write capacity than the fixed baseline (it scaled up under load)" in {
      val auto = run(autoScaled, seed = 2L)
      val fix  = run(fixed, seed = 2L)
      mean(auto, "TotalProvisionedWriteCapacityUnitTicks") should be > mean(fix, "TotalProvisionedWriteCapacityUnitTicks")
    }

    "be reproducible under a fixed seed" in {
      run(autoScaled, seed = 7L) shouldBe run(autoScaled, seed = 7L)
    }
  }
