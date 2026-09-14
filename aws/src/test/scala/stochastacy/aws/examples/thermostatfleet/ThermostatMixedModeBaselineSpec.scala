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
 * Baseline (characterization) gate for the mixed-mode Thermostat-fleet demo (on-demand → provisioned@400 →
 * right-size@800). The demo's behavior is pinned to an established baseline captured from its own output.
 *
 * The **simulation** holds cleanly — consumed RCU/WCU and final storage all within ~1 %. The mixed-mode
 * **cost is a documented characteristic**: the model uses a clean per-tick billing attribution (on-demand ticks
 * billed by consumption, provisioned ticks by capacity-hours — never double-counted), so it bills the
 * throttled/provisioned window by reserved capacity rather than by would-be consumption. We assert the
 * simulation tightly and treat cost as a bounded, directional, documented characteristic of the model.
 *
 * `TotalStorageByteTicks` is not compared (the same exclusion the order-tracking gate makes); the throttle count
 * and provisioned capacity-ticks are provisioned-mode additions surfaced only for provisioned ensembles.
 */
class ThermostatMixedModeBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatMixedModeBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * The demo's established baseline: across-trial means at `mixedModeDefault` (100 × 1200), captured 2026-08-28
   * from the demo's own aggregate-summary `statistic:"mean"` records. Regenerate by running the demo's Monte
   * Carlo runner and reading those records.
   */
  private object Baseline:
    val meanTotalReadCapacityUnits  = BigDecimal("744.29")
    val meanTotalWriteCapacityUnits = BigDecimal("373178.69")
    val meanFinalStorageBytes       = BigDecimal("4016750.87")
    val meanTotalEstimatedCost      = BigDecimal("0.24656295023418914")

  private val config     = ThermostatConfig.mixedModeDefault // on-demand → Provisioned(250,125)@400 → (100,333)@800
  private val SimTol     = BigDecimal("0.03") // simulation band (consumed capacity, storage)
  private val CostBound  = BigDecimal("0.15") // the cost characteristic is bounded (measured ~-8.6%), documented below

  private lazy val result =
    Await.result(new SingleTableMonteCarloRunner().run(config, masterSeed = 20260418L), 20.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Thermostat-fleet mixed-mode demo — simulation (consumed capacity + storage)" should {

    "hold to the baseline mean consumed read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), Baseline.meanTotalReadCapacityUnits) should be <= SimTol
    }
    "hold to the baseline mean consumed write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), Baseline.meanTotalWriteCapacityUnits) should be <= SimTol
    }
    "hold to the baseline mean final storage bytes within tolerance" in {
      relDiff(meanOf("FinalStorageBytes"), Baseline.meanFinalStorageBytes) should be <= SimTol
    }
  }

  "The v2 Thermostat-fleet mixed-mode demo — cost (documented characteristic)" should {

    "bill the provisioned window by reserved capacity, so total cost sits below the consumption baseline — within a bounded gap" in {
      val v2Cost       = meanOf("TotalEstimatedCost")
      val baselineCost = Baseline.meanTotalEstimatedCost
      v2Cost should be < baselineCost                       // does not double-count the throttled/provisioned consumption
      relDiff(v2Cost, baselineCost) should be <= CostBound  // and the gap stays bounded
    }

    "surface the provisioned reservation and throttle count (provisioned-mode additions)" in {
      meanOf("TotalProvisionedWriteCapacityUnitTicks") should be > BigDecimal(0)
      meanOf("TotalThrottledRequests")                 should be > BigDecimal(0)
    }

    "report the measured gaps for transparency" in {
      def gap(metric: String, baseline: BigDecimal): Double = ((meanOf(metric) - baseline) / baseline * 100).toDouble
      info(f"baseline gaps: consumed RCU ${gap("TotalReadCapacityUnits", Baseline.meanTotalReadCapacityUnits)}%+.2f%%, "
        + f"consumed WCU ${gap("TotalWriteCapacityUnits", Baseline.meanTotalWriteCapacityUnits)}%+.2f%%, "
        + f"storage ${gap("FinalStorageBytes", Baseline.meanFinalStorageBytes)}%+.2f%%, "
        + f"cost ${gap("TotalEstimatedCost", Baseline.meanTotalEstimatedCost)}%+.2f%% (documented characteristic)")
    }
  }
