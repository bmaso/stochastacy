package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

class ThermostatFleetTrialSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatFleetTrialSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // A small, fast scenario with the read rates bumped so queries and scans reliably occur.
  private val config = ThermostatConfig(
    initialDeviceCount = 50L, deviceGrowthPerTick = 0.5, simulationTicks = 50L,
    trialCount = 2, parallelism = 2,
    customerSupportQueryRatePerTick = 2.0, fleetDashboardScanRatePerTick = 1.0
  )

  private def run(seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(config, seed), 60.seconds)

  private def mean(result: MonteCarloResult, metric: String): BigDecimal =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  "The Thermostat-fleet demo, end to end," should {

    "produce base and per-target consumption (query/scan RCU, maintenance WCU on every index)" in {
      val result = run(seed = 1L)
      mean(result, "TotalReadCapacityUnits")  should be > BigDecimal(0)
      mean(result, "TotalWriteCapacityUnits") should be > BigDecimal(0)
      // customer-devices is queried, fleet-alerts is scanned -> both consume GSI RCU
      mean(result, "GSI:customer-devices:TotalReadCapacityUnits") should be > BigDecimal(0)
      mean(result, "GSI:fleet-alerts:TotalReadCapacityUnits")     should be > BigDecimal(0)
      // device-status is only maintained (never read) -> WCU but no RCU
      mean(result, "GSI:device-status:TotalWriteCapacityUnits")   should be > BigDecimal(0)
      mean(result, "GSI:device-status:TotalReadCapacityUnits")    shouldBe BigDecimal(0)
    }

    "be reproducible under a fixed seed" in {
      run(seed = 7L) shouldBe run(seed = 7L)
    }
  }
