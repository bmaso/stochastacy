package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

/**
 * Baseline (characterization) gate for the multi-table Thermostat-fleet demo: the demo's aggregate behavior is
 * pinned **per table** to an established baseline captured from its own output.
 *
 * Each table (device-registry read-heavy, device-telemetry = the single-region default) holds within ~2 % on
 * every dimension. The projection-correct GSI reads do not meaningfully diverge (RCU rounding absorbs the
 * projected-vs-base byte gap), and the per-table system-error rates are registry 0.0, telemetry 0.001.
 */
class ThermostatMultiTableBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatMultiTableBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * The demo's established baseline: across-trial per-table means at `twoTableDefault` (100 trials × 1200 ticks),
   * captured 2026-08-25 from the demo's own aggregate-summary `statistic:"mean"` `Table:<name>:…` records.
   * Regenerate by running the demo's Monte Carlo runner and reading those records.
   */
  private val Baseline: Map[String, Map[String, BigDecimal]] = Map(
    "device-registry" -> Map(
      "TotalReadCapacityUnits"  -> BigDecimal("2087.1"),
      "TotalWriteCapacityUnits" -> BigDecimal("81926.8"),
      "FinalStorageBytes"       -> BigDecimal("3979527.72"),
      "TotalEstimatedCost"      -> BigDecimal("0.10293098224184359")
    ),
    "device-telemetry" -> Map(
      "TotalReadCapacityUnits"  -> BigDecimal("755.445"),
      "TotalWriteCapacityUnits" -> BigDecimal("502946.86"),
      "FinalStorageBytes"       -> BigDecimal("4017432.11"),
      "TotalEstimatedCost"      -> BigDecimal("0.6288732445539886")
    )
  )

  private val config = ThermostatMultiTableConfig.twoTableDefault // 100 trials × 1200 ticks — matches the baseline
  private val tables = Vector("device-registry", "device-telemetry")
  private val WcuTol     = BigDecimal("0.03") // write-capacity band (writes + maintenance)
  private val RcuTol     = BigDecimal("0.05") // read-capacity band (smaller samples, projected-byte rounding)
  private val StorageTol = BigDecimal("0.03")
  private val CostTol     = BigDecimal("0.03")

  private lazy val result =
    Await.result(new MultiTableMonteCarloRunner().run(config, masterSeed = 20260418L), 20.minutes)

  private def meanOf(tableName: String, metric: String): BigDecimal =
    result.perTable.find(_.tableName == tableName)
      .getOrElse(fail(s"missing table $tableName"))
      .aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $tableName / $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  private def checkAllTables(metric: String, tol: BigDecimal): Unit =
    tables.foreach { t =>
      relDiff(meanOf(t, metric), Baseline(t)(metric)) should be <= tol
    }

  "The v2 Thermostat-fleet multi-table demo — per-table baseline" should {

    "hold to the baseline per-table mean write capacity units within tolerance" in {
      checkAllTables("TotalWriteCapacityUnits", WcuTol)
    }

    "hold to the baseline per-table mean read capacity units within tolerance" in {
      checkAllTables("TotalReadCapacityUnits", RcuTol)
    }

    "hold to the baseline per-table mean final storage bytes within tolerance" in {
      checkAllTables("FinalStorageBytes", StorageTol)
    }

    "hold to the baseline per-table mean total estimated cost within tolerance" in {
      checkAllTables("TotalEstimatedCost", CostTol)
    }

    "report the measured per-table gaps for transparency" in {
      tables.foreach { t =>
        def gap(metric: String): Double = ((meanOf(t, metric) - Baseline(t)(metric)) / Baseline(t)(metric) * 100).toDouble
        info(f"$t: RCU ${gap("TotalReadCapacityUnits")}%+.2f%%, WCU ${gap("TotalWriteCapacityUnits")}%+.2f%%, "
          + f"storage ${gap("FinalStorageBytes")}%+.2f%%, cost ${gap("TotalEstimatedCost")}%+.2f%%")
      }
    }
  }
