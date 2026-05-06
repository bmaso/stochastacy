package stochastacy.examples.thermostatfleet

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.demo.{DemoMetric, TrialRunConfig}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class ThermostatFleetMultiTableSingleTrialRunnerSpec
    extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem     = ActorSystem("multi-table-trial-test")
  given Materializer    = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val run = TrialRunConfig(trialId = 0, seed = 42L)

  private val smallTwoTable = MultiTableScenarioConfig.twoTableDefault.copy(
    simulationTicks = 120L,
    trialCount      = 1,
    parallelism     = 1,
    tables          = MultiTableScenarioConfig.twoTableDefault.tables.map { entry =>
      entry.copy(config = entry.config.copy(simulationTicks = 120L))
    }
  )

  "ThermostatFleetMultiTableSingleTrialRunner" should {

    "complete a two-table trial and return a non-empty TrialResult" in {
      val runner = ThermostatFleetMultiTableSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallTwoTable, run),
        60.seconds
      )
      result.scenarioId shouldBe "thermostat-fleet-multi-table"
      result.timeSeries should not be empty
      result.summary    should not be empty
    }

    "namespace time-series metrics under Table:<name>:* with no unnamed metrics" in {
      val runner = ThermostatFleetMultiTableSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallTwoTable, run),
        60.seconds
      )
      val names = result.timeSeries.map(_.metric)
      names.foreach {
        case _: DemoMetric.TableReadCapacityUnits       =>
        case _: DemoMetric.TableWriteCapacityUnits      =>
        case _: DemoMetric.TableStorageBytes            =>
        case _: DemoMetric.TableCumulativeEstimatedCost =>
        case other => fail(s"unexpected metric in time series: $other")
      }
      // both table names appear
      val tableNames = names.collect { case DemoMetric.TableReadCapacityUnits(t) => t }.distinct
      tableNames should contain("device-registry")
      tableNames should contain("device-telemetry")
      // no bare (un-namespaced) ReadCapacityUnits
      names should not contain DemoMetric.ReadCapacityUnits
    }

    "produce non-zero RCU and WCU totals for both tables" in {
      val runner = ThermostatFleetMultiTableSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(smallTwoTable, run),
        60.seconds
      )
      val summary = result.summary.map(sv => sv.metric -> sv.value).toMap

      summary(DemoMetric.TableTotalReadCapacityUnits("device-registry"))   should be > BigDecimal(0)
      summary(DemoMetric.TableTotalReadCapacityUnits("device-telemetry"))  should be > BigDecimal(0)
      summary(DemoMetric.TableTotalWriteCapacityUnits("device-registry"))  should be > BigDecimal(0)
      summary(DemoMetric.TableTotalWriteCapacityUnits("device-telemetry")) should be > BigDecimal(0)
    }

    "handle a single-table config (Merge(1) degenerate case)" in {
      val singleTable = MultiTableScenarioConfig(
        scenarioId      = "thermostat-fleet-multi-table",
        simulationTicks = 60L,
        trialCount      = 1,
        parallelism     = 1,
        tables          = Vector(
          MultiTableEntry(
            tableName = "device-telemetry",
            config    = ThermostatFleetScenarioConfig.singleRegionDefault.copy(
              scenarioId      = "thermostat-fleet-multi-table",
              simulationTicks = 60L
            )
          )
        )
      )
      val runner = ThermostatFleetMultiTableSingleTrialRunner()
      val result = Await.result(
        runner.runTrial(singleTable, run),
        60.seconds
      )
      result.timeSeries should not be empty
      result.summary.map(_.metric) should contain(
        DemoMetric.TableTotalReadCapacityUnits("device-telemetry")
      )
    }
  }
