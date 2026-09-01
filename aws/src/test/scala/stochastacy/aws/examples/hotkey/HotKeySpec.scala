package stochastacy.aws.examples.hotkey

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.HeatSplitPolicy

/**
 * Hot-key demo (Slice 4a): a concentrated key drives one physical partition to throttle while the table has
 * aggregate spare; a well-distributed workload does not; instant adaptive admits more than the fair-share
 * baseline; and sustained heat grows the effective partition count (split-for-heat). Deterministic per seed.
 */
class HotKeySpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("HotKeySpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val runner = new HotKeyTrialRunner()
  private def await[A](f: Future[A]): A = Await.result(f, 2.minutes)

  // Write-only, all traffic to one hot key → one partition; puts 3000/tick < the table's 4000 WCU, so any
  // throttling is per-partition (the table still has aggregate spare).
  private val hot = HotKeyConfig(
    simulationTicks = 12L, trialCount = 1, hotKeyCount = 1, hotFraction = 1.0,
    putsPerTick = 3000.0, getsPerTick = 0.0, initialItems = 1000L, coldKeySpace = 1000,
    heatSplitPolicy = Some(HeatSplitPolicy(windowTicks = 3, maxPartitionCount = 20))
  )

  "The hot-key scenario" should {

    "throttle a concentrated key while the table has aggregate spare" in {
      val r = await(runner.runTrial(hot, trialId = 0, seed = 1L))
      r.totalThrottled should be > 0L                    // the hot partition throttles
      r.totalOffered   should be > r.totalThrottled       // yet most requests are admitted (table not saturated)
    }

    "throttle far less when the workload is well-distributed" in {
      val distributed = hot.copy(hotFraction = 0.0)       // every request to a distinct cold key
      val hotR  = await(runner.runTrial(hot, 0, 1L))
      val distR = await(runner.runTrial(distributed, 0, 1L))
      distR.totalThrottled should be < hotR.totalThrottled
    }

    "admit more with instant adaptive on than with the fair-share baseline (off)" in {
      val onR  = await(runner.runTrial(hot, 0, 1L))                                                  // physical-max ceiling 1000
      val offR = await(runner.runTrial(hot.copy(adaptiveCapacity = false, heatSplitPolicy = None), 0, 1L)) // fair-share ceiling 800
      onR.totalThrottled should be < offR.totalThrottled
    }

    "grow the effective partition count under sustained heat (split-for-heat)" in {
      val r = await(runner.runTrial(hot, 0, 1L))
      r.finalPartitionCount should be > hot.basePartitionCount
    }

    "be deterministic for a fixed seed" in {
      val a = await(runner.runTrial(hot, 0, 7L))
      val b = await(runner.runTrial(hot, 0, 7L))
      a.totalThrottled     shouldBe b.totalThrottled
      a.totalOffered       shouldBe b.totalOffered
      a.finalPartitionCount shouldBe b.finalPartitionCount
    }
  }
