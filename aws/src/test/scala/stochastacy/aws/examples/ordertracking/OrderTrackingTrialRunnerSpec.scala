package stochastacy.aws.examples.ordertracking

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class OrderTrackingTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("OrderTrackingTrialRunnerSpec")
  private given Materializer         = Materializer.matFromSystem
  private given scala.concurrent.ExecutionContext = system.dispatcher
  override def afterAll(): Unit = system.terminate()

  private val config = OrderTrackingConfig.phase1Default
  private def run(seed: Long) = Await.result(new OrderTrackingTrialRunner().runTrial(config, trialId = 0, seed = seed), 10.seconds)

  "OrderTrackingTrialRunner.runTrial" should {

    "produce one time-series point per simulated tick, labeled 1..N" in {
      val result = run(42L)
      result.timeSeries.map(_.tick) shouldBe (1L to config.simulationTicks).toVector
    }

    "reconcile the summary with the time series" in {
      val r = run(42L)
      r.timeSeries.map(_.readCapacityUnits).sum          shouldBe r.summary.totalReadCapacityUnits
      r.timeSeries.map(_.writeCapacityUnits).sum         shouldBe r.summary.totalWriteCapacityUnits
      r.timeSeries.map(p => BigInt(p.storageBytes)).sum  shouldBe r.summary.totalStorageByteTicks
      r.timeSeries.last.storageBytes                     shouldBe r.summary.finalStorageBytes
      r.timeSeries.last.cumulativeEstimatedCost          shouldBe r.summary.totalEstimatedCost
    }

    "consume capacity and accrue storage byte-ticks" in {
      val r = run(42L)
      r.summary.totalReadCapacityUnits  should be > BigDecimal(0)
      r.summary.totalWriteCapacityUnits should be > BigDecimal(0)
      r.summary.totalEstimatedCost      should be > BigDecimal(0)
      r.summary.totalStorageByteTicks   should be > BigInt(0)
      r.summary.finalStorageBytes       should be > 0L
      // (that the *initial* storage is counted — seeded, not started from 0 — is pinned in TrialAccountingSpec)
    }

    "be deterministic under a fixed seed" in {
      run(7L) shouldBe run(7L)
    }

    "run the indexed scenario end-to-end (query/scan handled, indexes maintained)" in {
      val indexed = OrderTrackingConfig.indexedDefault
      val r = Await.result(new OrderTrackingTrialRunner().runTrial(indexed, trialId = 0, seed = 5L), 10.seconds)
      r.timeSeries.map(_.tick)          shouldBe (1L to indexed.simulationTicks).toVector
      r.summary.totalReadCapacityUnits  should be > BigDecimal(0) // reads (query/scan) consume RCU
      r.summary.totalWriteCapacityUnits should be > BigDecimal(0) // writes + index maintenance consume WCU
    }
  }
