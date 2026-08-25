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
 * Reconciliation gate for the Indexed Order-Tracking demo. Unlike the phase-1 equivalence gate, this is
 * **not** a blind match: the v2 read model was deliberately improved (a scan evaluates the whole target,
 * not the legacy's capped few items), so reads legitimately diverge. The gate therefore:
 *
 *   - asserts **equivalence on the faithful path** — overall and per-GSI **write** capacity (writes plus
 *     index maintenance replicate the legacy math);
 *   - treats **storage** as a documented correction — v2 bills the pre-loaded storage of every target
 *     (base + indexes) that the legacy dropped;
 *   - **quantifies the read-model divergence** — total RCU is higher (scans grow with the table); measured
 *     and reported, with only a directional sanity check.
 *
 * The legacy code cannot be referenced from this module, so the baseline is captured and pinned.
 */
class OrderTrackingIndexedReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("OrderTrackingIndexedReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * Across-trial means from the legacy `order-tracking-phase2` demo at its default (100 trials × 30 ticks,
   * base seed 20260418). Captured 2026-08-20 via:
   *   sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge generate --output /tmp/legacy-ot-p2.jsonl'
   * then the aggregate-summary / statistic:"mean" records. Regenerate if the legacy Phase-2 demo changes
   * before it is deleted.
   */
  private object LegacyBaseline:
    val meanTotalReadCapacityUnits  = BigDecimal("149.3")
    val meanTotalWriteCapacityUnits = BigDecimal("375.51")
    val meanFinalStorageBytes       = BigDecimal("73398.28")
    val meanTotalEstimatedCost      = BigDecimal("5.067127075302958E-4")
    val meanGsiTotalWriteCapacityUnits = Map(
      "customerId-status"  -> BigDecimal("93.13"),
      "sellerId-createdAt" -> BigDecimal("93.13")
    )

  private val config = OrderTrackingConfig.indexedDefault // 100 trials × 30 ticks — matches the baseline
  private val WcuTol         = BigDecimal("0.05") // overall write-capacity equivalence band (faithful path)
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

    "match the legacy mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), LegacyBaseline.meanTotalWriteCapacityUnits) should be <= WcuTol
    }

    "match the legacy per-GSI mean write capacity units within tolerance" in {
      LegacyBaseline.meanGsiTotalWriteCapacityUnits.foreach { (indexName, legacyWcu) =>
        relDiff(meanOf(s"GSI:$indexName:TotalWriteCapacityUnits"), legacyWcu) should be <= GsiWcuTol
      }
    }
  }

  "The v2 Indexed Order-Tracking demo — deliberate corrections" should {

    "bill every target's pre-loaded storage the legacy dropped (final storage ~= legacy + all-targets initial)" in {
      val expected = LegacyBaseline.meanFinalStorageBytes + BigDecimal(config.initialStorageBytesAllTargets)
      relDiff(meanOf("FinalStorageBytes"), expected) should be <= StorageTol
    }

    "read more than the legacy did, because a scan now evaluates the whole target" in {
      val v2Rcu     = meanOf("TotalReadCapacityUnits")
      val legacyRcu = LegacyBaseline.meanTotalReadCapacityUnits
      // Directional: the improved scan model reads the whole target, so v2 consumes strictly more RCU.
      v2Rcu should be > legacyRcu
      // Reported for transparency (not asserted as an equality — this divergence is the intended improvement).
      val rcuGap  = (v2Rcu - legacyRcu) / legacyRcu * 100
      val costGap = (meanOf("TotalEstimatedCost") - LegacyBaseline.meanTotalEstimatedCost) / LegacyBaseline.meanTotalEstimatedCost * 100
      info(f"read-model divergence: total RCU v2=$v2Rcu%s vs legacy=$legacyRcu%s (${rcuGap.toDouble}%+.1f%%); cost ${costGap.toDouble}%+.1f%%")
    }
  }
