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
 * Baseline (characterization) gate: the v2 Order-Tracking Phase-1 demo's aggregate behavior is pinned to an
 * established baseline captured from the demo's own output.
 *
 * Storage is a deliberate exception: the table bills its pre-loaded bytes, so `FinalStorageBytes` is checked
 * against `baseline + initial storage`, and `TotalStorageByteTicks` is not compared at all.
 */
class OrderTrackingBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("OrderTrackingBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /**
   * The demo's established baseline: across-trial means at `phase1Default` (100 trials × 30 ticks, base seed
   * 20260418), captured 2026-08-17 from the demo's own aggregate-summary `statistic:"mean"` records. Regenerate
   * by running the demo's Monte Carlo runner and reading those records.
   */
  private object Baseline:
    val meanTotalReadCapacityUnits  = BigDecimal("74.04")
    val meanTotalWriteCapacityUnits = BigDecimal("95.25")
    val meanTotalEstimatedCost      = BigDecimal("1.3757252887937075E-4")
    val meanFinalStorageBytes       = BigDecimal("19816.75")

  private val config              = OrderTrackingConfig.phase1Default // 100 trials × 30 ticks — matches the baseline
  private val initialStorageBytes = BigDecimal(config.initialItemCount * config.initialAverageItemBytes) // 7680
  private val Tol                 = BigDecimal("0.05") // RCU/WCU/cost baseline band
  private val StorageTol          = BigDecimal("0.10") // storage-correction band (net delta has higher variance)

  private lazy val result =
    Await.result(new SingleTableMonteCarloRunner().run(config, masterSeed = 20260418L), 5.minutes)

  private def meanOf(metric: String): BigDecimal =
    result.aggregateSummary
      .collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }
      .getOrElse(fail(s"missing aggregate mean for $metric"))

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal = (actual - expected).abs / expected.abs

  "The v2 Order-Tracking Phase-1 demo" should {

    "hold to the baseline mean total read capacity units within tolerance" in {
      relDiff(meanOf("TotalReadCapacityUnits"), Baseline.meanTotalReadCapacityUnits) should be <= Tol
    }

    "hold to the baseline mean total write capacity units within tolerance" in {
      relDiff(meanOf("TotalWriteCapacityUnits"), Baseline.meanTotalWriteCapacityUnits) should be <= Tol
    }

    "hold to the baseline mean total estimated cost within tolerance" in {
      relDiff(meanOf("TotalEstimatedCost"), Baseline.meanTotalEstimatedCost) should be <= Tol
    }

    "bill the table's pre-loaded storage (final storage = baseline + initial bytes)" in {
      relDiff(meanOf("FinalStorageBytes"), Baseline.meanFinalStorageBytes + initialStorageBytes) should be <= StorageTol
    }
  }
