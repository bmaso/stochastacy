package stochastacy.aws.examples.ordertracking

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * Behavior-equivalence gate: the v2 Order-Tracking Phase-1 demo must reproduce the legacy demo's
 * aggregate behavior. The legacy code is unreferenceable from this module (its package is shadowed), so
 * we compare against a **captured** legacy baseline rather than co-running it.
 *
 * Storage is a deliberate exception: v2 bills the table's pre-loaded bytes, which the legacy silently
 * dropped, so `FinalStorageBytes` is checked against `legacy + initial storage`, and `TotalStorageByteTicks`
 * is not compared at all (the legacy summary/time-series paths even disagree on their own accrual count).
 */
class OrderTrackingEquivalenceSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("OrderTrackingEquivalenceSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * Across-trial means from the legacy Phase-1 demo at `phase1Default` (100 trials × 30 ticks, base seed
   * 20260418). Captured 2026-08-17 at git 6cf6fe0 via:
   *   sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase1Bridge generate --output /tmp/legacy-ot-p1.jsonl'
   * then the aggregate-summary / statistic:"mean" records. Regenerate if the legacy Phase-1 demo changes
   * before it is deleted.
   */
  private object LegacyBaseline:
    val meanTotalReadCapacityUnits  = BigDecimal("74.04")
    val meanTotalWriteCapacityUnits = BigDecimal("95.25")
    val meanTotalEstimatedCost      = BigDecimal("1.3757252887937075E-4")
    val meanFinalStorageBytes       = BigDecimal("19816.75")

  private val config              = OrderTrackingConfig.phase1Default // 100 trials × 30 ticks — matches the baseline
  private val initialStorageBytes = BigDecimal(config.initialItemCount * config.initialAverageItemBytes) // 7680
  private val Tol                 = BigDecimal("0.05") // RCU/WCU/cost equivalence band
  private val StorageTol          = BigDecimal("0.10") // storage-correction band (net delta has higher variance)

  private lazy val result =
    Await.result(new OrderTrackingMonteCarloRunner().run(config, masterSeed = 20260418L), 5.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Order-Tracking Phase-1 demo" should {

    "match the legacy mean total read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), LegacyBaseline.meanTotalReadCapacityUnits) should be <= Tol
    }

    "match the legacy mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), LegacyBaseline.meanTotalWriteCapacityUnits) should be <= Tol
    }

    "match the legacy mean total estimated cost within tolerance" in {
      relDiff(meanOf("TotalEstimatedCost"), LegacyBaseline.meanTotalEstimatedCost) should be <= Tol
    }

    "bill the pre-loaded storage the legacy dropped (final storage ~= legacy + initial)" in {
      relDiff(meanOf("FinalStorageBytes"), LegacyBaseline.meanFinalStorageBytes + initialStorageBytes) should be <= StorageTol
    }
  }
