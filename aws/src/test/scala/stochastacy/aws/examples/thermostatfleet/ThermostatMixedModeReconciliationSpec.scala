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
 * Reconciliation gate for the mixed-mode Thermostat-fleet demo (on-demand → provisioned@400 → right-size@800).
 * The legacy code is unreferenceable from this module, so we compare against a **captured** legacy baseline.
 *
 * The **simulation** reconciles cleanly — consumed RCU/WCU and final storage all within ~1 %. The mixed-mode
 * **cost is a documented divergence**: v2 uses a clean per-tick billing attribution (on-demand ticks billed by
 * consumption, provisioned ticks by capacity-hours — never double-counted), whereas the legacy's mixed-cost
 * accounting is internally inconsistent (its per-tick capacity series does not even sum to its own summary
 * total), so it is not a clean cost reference. v2's cost is lower (it correctly bills the throttled/provisioned
 * window by reserved capacity, not by would-be consumption). We assert the simulation tightly and treat cost as
 * a bounded, directional, documented divergence — the phase-2/3 "we improved the model" pattern.
 *
 * `TotalStorageByteTicks` is not compared (the legacy's per-tick and summary storage-accrual paths disagree —
 * the same exclusion the order-tracking gate makes); the throttle count and provisioned capacity-ticks are v2
 * additions the legacy summary does not emit.
 */
class ThermostatMixedModeReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatMixedModeReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * Across-trial means from the legacy `thermostat-fleet-mixed-mode` demo at its default (100 × 1200).
   * Captured 2026-08-28 via:
   *   sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate \
   *          --output /tmp/legacy-thermo-mixed.jsonl --mode mixed-mode'
   * then the aggregate-summary / statistic:"mean" records.
   */
  private object LegacyBaseline:
    val meanTotalReadCapacityUnits  = BigDecimal("744.29")
    val meanTotalWriteCapacityUnits = BigDecimal("373178.69")
    val meanFinalStorageBytes       = BigDecimal("4016750.87")
    val meanTotalEstimatedCost      = BigDecimal("0.24656295023418914")

  private val config     = ThermostatConfig.mixedModeDefault // on-demand → Provisioned(250,125)@400 → (100,333)@800
  private val SimTol     = BigDecimal("0.03") // simulation band (consumed capacity, storage)
  private val CostBound  = BigDecimal("0.15") // the cost divergence is bounded (measured ~-8.6%), documented below

  private lazy val result =
    Await.result(new SingleTableMonteCarloRunner().run(config, masterSeed = 20260418L), 20.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Thermostat-fleet mixed-mode demo — simulation (consumed capacity + storage)" should {

    "match the legacy mean consumed read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), LegacyBaseline.meanTotalReadCapacityUnits) should be <= SimTol
    }
    "match the legacy mean consumed write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), LegacyBaseline.meanTotalWriteCapacityUnits) should be <= SimTol
    }
    "match the legacy mean final storage bytes within tolerance" in {
      relDiff(meanOf("FinalStorageBytes"), LegacyBaseline.meanFinalStorageBytes) should be <= SimTol
    }
  }

  "The v2 Thermostat-fleet mixed-mode demo — cost (documented divergence)" should {

    "bill the provisioned window by reserved capacity, so total cost is lower than the legacy's — within a bounded gap" in {
      val v2Cost     = meanOf("TotalEstimatedCost")
      val legacyCost = LegacyBaseline.meanTotalEstimatedCost
      v2Cost should be < legacyCost                       // v2 does not double-count the throttled/provisioned consumption
      relDiff(v2Cost, legacyCost) should be <= CostBound  // and the divergence stays bounded
    }

    "surface the provisioned reservation and throttle count (v2 additions the legacy summary omits)" in {
      meanOf("TotalProvisionedWriteCapacityUnitTicks") should be > BigDecimal(0)
      meanOf("TotalThrottledRequests")                 should be > BigDecimal(0)
    }

    "report the measured gaps for transparency" in {
      def gap(metric: String, legacy: BigDecimal): Double = ((meanOf(metric) - legacy) / legacy * 100).toDouble
      info(f"reconciliation gaps: consumed RCU ${gap("TotalReadCapacityUnits", LegacyBaseline.meanTotalReadCapacityUnits)}%+.2f%%, "
        + f"consumed WCU ${gap("TotalWriteCapacityUnits", LegacyBaseline.meanTotalWriteCapacityUnits)}%+.2f%%, "
        + f"storage ${gap("FinalStorageBytes", LegacyBaseline.meanFinalStorageBytes)}%+.2f%%, "
        + f"cost ${gap("TotalEstimatedCost", LegacyBaseline.meanTotalEstimatedCost)}%+.2f%% (documented divergence)")
    }
  }
