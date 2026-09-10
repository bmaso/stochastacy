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
 * Baseline (characterization) gate for the single-region Thermostat-fleet demo: the demo's aggregate behavior is
 * pinned to an established baseline captured from the demo's own output.
 *
 * Every dimension — writes, reads, storage, cost — holds within tolerance. The reads consult each GSI's
 * *projected* state (KeysOnly ≈128 B, Include ≈192 B) rather than the base item's bytes, but the read sizes here
 * are small enough that RCU rounding (4 KB blocks, halved for eventual consistency) absorbs the projected-vs-base
 * byte gap, so total and per-GSI RCU still hold to the baseline within ~2 %. Writes + index maintenance hold on
 * the faithful path, and the Slice-6a system-error gate runs at `systemErrorRate = 0.001`.
 *
 * One immaterial modeling detail is documented, not asserted: telemetry item sizes are drawn ±25 % uniform
 * around their mean (both sub-1 KB ⇒ 1 WCU/item and the same expected storage); and the (off) polar-vortex
 * `affectedFraction` default is inert while the multiplier is 1.0.
 */
class ThermostatFleetBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatFleetBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * The demo's established baseline: across-trial means at `singleRegionDefault` (100 trials × 1200 ticks),
   * captured 2026-08-25 from the demo's own aggregate-summary `statistic:"mean"` records. Regenerate by running
   * the demo's Monte Carlo runner and reading those records.
   */
  private object Baseline:
    val meanTotalReadCapacityUnits  = BigDecimal("744.37")
    val meanTotalWriteCapacityUnits = BigDecimal("496942.21")
    val meanFinalStorageBytes       = BigDecimal("4017063.05")
    val meanTotalEstimatedCost      = BigDecimal("0.6213646629269166")
    val meanGsiTotalReadCapacityUnits = Map(
      "customer-devices" -> BigDecimal("299.565"),
      "fleet-alerts"     -> BigDecimal("444.805"),
      "device-status"    -> BigDecimal("0.0")
    )
    val meanGsiTotalWriteCapacityUnits = Map(
      "customer-devices" -> BigDecimal("3292.84"),
      "fleet-alerts"     -> BigDecimal("3292.84"),
      "device-status"    -> BigDecimal("163095.98")
    )

  private val config = ThermostatConfig.singleRegionDefault // 100 trials × 1200 ticks — matches the baseline
  private val WcuTol     = BigDecimal("0.03") // write-capacity band (the faithful path — writes + maintenance)
  private val RcuTol     = BigDecimal("0.05") // read-capacity band (smaller samples, projected-byte rounding)
  private val StorageTol = BigDecimal("0.03") // final-storage band
  private val CostTol    = BigDecimal("0.03") // total-cost band (writes dominate, so it tracks WCU)

  private lazy val result =
    Await.result(new SingleTableMonteCarloRunner().run(config, masterSeed = 20260418L), 20.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Thermostat-fleet demo — writes + index maintenance" should {

    "hold to the baseline mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), Baseline.meanTotalWriteCapacityUnits) should be <= WcuTol
    }

    "hold to the baseline per-GSI mean write capacity units within tolerance (mixed projections)" in {
      Baseline.meanGsiTotalWriteCapacityUnits.foreach { (indexName, baselineWcu) =>
        relDiff(meanOf(s"GSI:$indexName:TotalWriteCapacityUnits"), baselineWcu) should be <= WcuTol
      }
    }
  }

  "The v2 Thermostat-fleet demo — reads (projection-correct)" should {

    "hold to the baseline mean total read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), Baseline.meanTotalReadCapacityUnits) should be <= RcuTol
    }

    "hold to the baseline per-GSI mean read capacity units within tolerance for the read GSIs" in {
      // customer-devices (queried) and fleet-alerts (scanned) both hold despite reading projected
      // bytes — the reads are small enough that RCU rounding absorbs the projected-vs-base difference.
      Vector("customer-devices", "fleet-alerts").foreach { indexName =>
        relDiff(meanOf(s"GSI:$indexName:TotalReadCapacityUnits"), Baseline.meanGsiTotalReadCapacityUnits(indexName)) should be <= RcuTol
      }
    }

    "never read device-status (maintained only)" in {
      meanOf("GSI:device-status:TotalReadCapacityUnits") shouldBe BigDecimal(0)
    }
  }

  "The v2 Thermostat-fleet demo — storage and cost" should {

    "hold to the baseline mean final storage bytes within tolerance" in {
      relDiff(meanOf("FinalStorageBytes"), Baseline.meanFinalStorageBytes) should be <= StorageTol
    }

    "hold to the baseline mean total estimated cost within tolerance" in {
      relDiff(meanOf("TotalEstimatedCost"), Baseline.meanTotalEstimatedCost) should be <= CostTol
    }

    "report the measured gaps for transparency" in {
      def gap(metric: String, baseline: BigDecimal): Double = ((meanOf(metric) - baseline) / baseline * 100).toDouble
      info(f"baseline gaps: RCU ${gap("TotalReadCapacityUnits", Baseline.meanTotalReadCapacityUnits)}%+.2f%%, "
        + f"WCU ${gap("TotalWriteCapacityUnits", Baseline.meanTotalWriteCapacityUnits)}%+.2f%%, "
        + f"storage ${gap("FinalStorageBytes", Baseline.meanFinalStorageBytes)}%+.2f%%, "
        + f"cost ${gap("TotalEstimatedCost", Baseline.meanTotalEstimatedCost)}%+.2f%%")
    }
  }
