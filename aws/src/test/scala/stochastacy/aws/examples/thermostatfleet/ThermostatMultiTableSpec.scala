package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

class ThermostatMultiTableSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatMultiTableSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // A small, fixed-fleet two-table scenario: registry is read-heavy / write-light, telemetry the reverse.
  private val ticks = 50L
  private def base(rate: Double, query: Double, scan: Double, sysErr: Double) = ThermostatConfig(
    scenarioId = "test-mt", simulationTicks = ticks, trialCount = 1, parallelism = 1,
    initialDeviceCount = 500L, deviceGrowthPerTick = 0.0,
    telemetryReportsPerDevicePerTick = rate, customerSupportQueryRatePerTick = query,
    fleetDashboardScanRatePerTick = scan, systemErrorRate = sysErr
  )
  private val config = ThermostatMultiTableConfig(
    scenarioId = "test-mt", simulationTicks = ticks, trialCount = 3, parallelism = 2,
    tableConfigs = Vector(
      "device-registry"  -> base(rate = 0.005, query = 2.0, scan = 0.2, sysErr = 0.0),
      "device-telemetry" -> base(rate = 0.033, query = 0.5, scan = 0.1, sysErr = 0.001)
    )
  )

  private def run(seed: Long): MultiTableMonteCarloResult =
    Await.result(new MultiTableMonteCarloRunner().run(config, seed), 90.seconds)

  private def mean(result: MultiTableMonteCarloResult, tableName: String, metric: String): BigDecimal =
    result.perTable.find(_.tableName == tableName)
      .flatMap(_.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v })
      .getOrElse(BigDecimal(0))

  "The Thermostat-fleet multi-table demo, end to end," should {

    "produce a non-empty per-table result for both tables, in table order" in {
      val result = run(seed = 1L)
      result.perTable.map(_.tableName) shouldBe Vector("device-registry", "device-telemetry")
      mean(result, "device-registry",  "TotalWriteCapacityUnits") should be > BigDecimal(0)
      mean(result, "device-telemetry", "TotalWriteCapacityUnits") should be > BigDecimal(0)
      mean(result, "device-registry",  "TotalReadCapacityUnits")  should be > BigDecimal(0)
      all(result.perTable.map(_.aggregateTimeSeries.map(_.tick).distinct.size)) shouldBe ticks.toInt
    }

    "reflect each table's shape — registry read-heavy, telemetry write-heavy" in {
      val result = run(seed = 4L)
      mean(result, "device-registry",  "TotalReadCapacityUnits")  should be > mean(result, "device-telemetry", "TotalReadCapacityUnits")
      mean(result, "device-telemetry", "TotalWriteCapacityUnits") should be > mean(result, "device-registry",  "TotalWriteCapacityUnits")
    }

    "be reproducible under a fixed seed" in {
      run(seed = 7L) shouldBe run(seed = 7L)
    }
  }
