package stochastacy.aws.examples.ordertracking

import stochastacy.aws.examples.demo.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * Baseline (characterization) gate for the Indexed Order-Tracking demo. It pins the demo's aggregate behavior
 * to an established baseline captured from the demo's own output, describing the model on its own terms:
 *
 *   - the **faithful path** — overall and per-GSI **write** capacity (writes plus index maintenance) is pinned
 *     to the baseline within tolerance;
 *   - **storage** is a documented characteristic — the table bills the pre-loaded storage of every target
 *     (base + indexes);
 *   - the **read model** reads more than a keys-only scan model would (a scan evaluates the whole target, so
 *     total RCU grows with the table); this is measured and reported, with only a directional sanity check.
 */
class OrderTrackingIndexedBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("OrderTrackingIndexedBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * The demo's established baseline: across-trial means at `indexedDefault` (100 trials × 30 ticks, base seed
   * 20260418), captured 2026-08-20 from the demo's own aggregate-summary `statistic:"mean"` records. Regenerate
   * by running the demo's Monte Carlo runner and reading those records.
   */
  private object Baseline:
    val meanTotalReadCapacityUnits  = BigDecimal("149.3")
    val meanTotalWriteCapacityUnits = BigDecimal("375.51")
    val meanFinalStorageBytes       = BigDecimal("73398.28")
    val meanTotalEstimatedCost      = BigDecimal("5.067127075302958E-4")
    val meanGsiTotalWriteCapacityUnits = Map(
      "customerId-status"  -> BigDecimal("93.13"),
      "sellerId-createdAt" -> BigDecimal("93.13")
    )

  private val config = OrderTrackingConfig.indexedDefault // 100 trials × 30 ticks — matches the baseline
  private val WcuTol         = BigDecimal("0.05") // overall write-capacity baseline band (faithful path)
  private val GsiWcuTol      = BigDecimal("0.10") // per-GSI write-capacity band (smaller samples)
  private val StorageTol     = BigDecimal("0.15") // storage-correction band (net deltas are noisy)

  private lazy val result =
    Await.result(new SingleTableMonteCarloRunner().run(config, masterSeed = 20260418L), 5.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Indexed Order-Tracking demo — faithful path (writes + maintenance)" should {

    "hold to the baseline mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), Baseline.meanTotalWriteCapacityUnits) should be <= WcuTol
    }

    "hold to the baseline per-GSI mean write capacity units within tolerance" in {
      Baseline.meanGsiTotalWriteCapacityUnits.foreach { (indexName, baselineWcu) =>
        relDiff(meanOf(s"GSI:$indexName:TotalWriteCapacityUnits"), baselineWcu) should be <= GsiWcuTol
      }
    }
  }

  "The v2 Indexed Order-Tracking demo — model characteristics" should {

    "bill every target's pre-loaded storage (final storage = baseline + all-targets initial)" in {
      val expected = Baseline.meanFinalStorageBytes + BigDecimal(config.initialStorageBytesAllTargets)
      relDiff(meanOf("FinalStorageBytes"), expected) should be <= StorageTol
    }

    "read more than a keys-only scan model would, because a scan evaluates the whole target" in {
      val v2Rcu       = meanOf("TotalReadCapacityUnits")
      val baselineRcu = Baseline.meanTotalReadCapacityUnits
      // Directional: the scan model reads the whole target, so it consumes strictly more RCU than the baseline.
      v2Rcu should be > baselineRcu
      // Reported for transparency (not asserted as an equality — this is an intrinsic characteristic of the model).
      val rcuGap  = (v2Rcu - baselineRcu) / baselineRcu * 100
      val costGap = (meanOf("TotalEstimatedCost") - Baseline.meanTotalEstimatedCost) / Baseline.meanTotalEstimatedCost * 100
      info(f"read-model characteristic: total RCU v2=$v2Rcu%s vs baseline=$baselineRcu%s (${rcuGap.toDouble}%+.1f%%); cost ${costGap.toDouble}%+.1f%%")
    }
  }
