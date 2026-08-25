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
 * Reconciliation gate for the single-region Thermostat-fleet demo: the v2 demo must reproduce the legacy
 * `thermostat-fleet-single-region` demo's aggregate behavior. The legacy code is unreferenceable from this
 * module (its package is shadowed), so we compare against a **captured** legacy baseline rather than
 * co-running it.
 *
 * Unlike the phase-3 Indexed Order-Tracking gate, this turns out to be a **clean equivalence**, not a
 * reconciliation-with-divergence. The v2 reads consult each GSI's *projected* state (KeysOnly ≈128 B,
 * Include ≈192 B) rather than the base item's bytes, so the read *bytes* differ from legacy — but the read
 * sizes here are small enough that RCU rounding (4 KB blocks, halved for eventual consistency) absorbs the
 * difference, so total and per-GSI RCU still match within ~2 %. Writes + index maintenance match on the
 * faithful path, and the Slice-6a system-error gate reproduces the legacy `systemErrorRate = 0.001`, so
 * there is no deferred gap. Every dimension — writes, reads, storage, cost — agrees within tolerance.
 *
 * Two immaterial modeling differences are documented, not asserted: the legacy writes a constant 300 B per
 * telemetry item while v2 draws ±25 % uniform (same mean, both sub-1 KB ⇒ 1 WCU/item and the same expected
 * storage); and the (off) polar-vortex `affectedFraction` default differs (inert while the multiplier is 1.0).
 */
class ThermostatFleetReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatFleetReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * Across-trial means from the legacy `thermostat-fleet-single-region` demo at its default (100 trials ×
   * 1200 ticks). Captured 2026-08-25 via:
   *   sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate \
   *          --output /tmp/legacy-thermo-sr.jsonl --mode single-region'
   * then the aggregate-summary / statistic:"mean" records. Regenerate if the legacy single-region demo
   * changes before it is deleted.
   */
  private object LegacyBaseline:
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

    "match the legacy mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), LegacyBaseline.meanTotalWriteCapacityUnits) should be <= WcuTol
    }

    "match the legacy per-GSI mean write capacity units within tolerance (mixed projections)" in {
      LegacyBaseline.meanGsiTotalWriteCapacityUnits.foreach { (indexName, legacyWcu) =>
        relDiff(meanOf(s"GSI:$indexName:TotalWriteCapacityUnits"), legacyWcu) should be <= WcuTol
      }
    }
  }

  "The v2 Thermostat-fleet demo — reads (projection-correct, yet equivalent)" should {

    "match the legacy mean total read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), LegacyBaseline.meanTotalReadCapacityUnits) should be <= RcuTol
    }

    "match the legacy per-GSI mean read capacity units within tolerance for the read GSIs" in {
      // customer-devices (queried) and fleet-alerts (scanned) both reconcile despite v2 reading projected
      // bytes — the reads are small enough that RCU rounding absorbs the projected-vs-base difference.
      Vector("customer-devices", "fleet-alerts").foreach { indexName =>
        relDiff(meanOf(s"GSI:$indexName:TotalReadCapacityUnits"), LegacyBaseline.meanGsiTotalReadCapacityUnits(indexName)) should be <= RcuTol
      }
    }

    "never read device-status (maintained only), exactly as the legacy" in {
      meanOf("GSI:device-status:TotalReadCapacityUnits") shouldBe BigDecimal(0)
    }
  }

  "The v2 Thermostat-fleet demo — storage and cost" should {

    "match the legacy mean final storage bytes within tolerance" in {
      relDiff(meanOf("FinalStorageBytes"), LegacyBaseline.meanFinalStorageBytes) should be <= StorageTol
    }

    "match the legacy mean total estimated cost within tolerance" in {
      relDiff(meanOf("TotalEstimatedCost"), LegacyBaseline.meanTotalEstimatedCost) should be <= CostTol
    }

    "report the measured gaps for transparency" in {
      def gap(metric: String, legacy: BigDecimal): Double = ((meanOf(metric) - legacy) / legacy * 100).toDouble
      info(f"reconciliation gaps: RCU ${gap("TotalReadCapacityUnits", LegacyBaseline.meanTotalReadCapacityUnits)}%+.2f%%, "
        + f"WCU ${gap("TotalWriteCapacityUnits", LegacyBaseline.meanTotalWriteCapacityUnits)}%+.2f%%, "
        + f"storage ${gap("FinalStorageBytes", LegacyBaseline.meanFinalStorageBytes)}%+.2f%%, "
        + f"cost ${gap("TotalEstimatedCost", LegacyBaseline.meanTotalEstimatedCost)}%+.2f%%")
    }
  }
