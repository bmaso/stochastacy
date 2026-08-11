package stochastacy.examples.store

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StoreTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreTrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private def run(wl: StoreWorkloadConfig, sc: StoreConfig, seed: Long, ticks: Long) =
    Await.result(StoreTrialRunner.run(wl, sc, seed, ticks), 10.seconds)

  "StoreTrialRunner" should {

    "run the store workload end-to-end and complete with a well-formed TrialResult" in {
      val result = run(StoreWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 50L)
      result.durationTicks shouldBe 50L
      result.finalState.entityCount should be >= 0L
      result.finalState.totalBytes should be >= 0L
      result.residue.total should be >= 0L
    }

    "grow entity count under create-only writes with no deletes" in {
      val wl = StoreWorkloadConfig(getPerTick = 0.0, listPerTick = 0.0, reportPerTick = 0.0, deletePerTick = 0.0, putPerTick = 5.0)
      val sc = StoreConfig(createRate = 1.0)
      val result = run(wl, sc, seed = 1L, ticks = 50L)
      result.finalState.entityCount should be > sc.initialEntities
    }

    "be deterministic given a fixed seed" in {
      val wl = StoreWorkloadConfig()
      val sc = StoreConfig()
      run(wl, sc, seed = 7L, ticks = 30L) shouldBe run(wl, sc, seed = 7L, ticks = 30L)
    }

    "record per-use-case latency statistics" in {
      val result = run(StoreWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 50L)
      val getLatency = result.stats.get(StoreStatKey("get", "latency"))
      getLatency.map(_.count) getOrElse 0L should be > 0L
      getLatency.map(_.p50) getOrElse 0.0 should be > 0.0
    }

    "show the emergent cost ordering: report latency p99 exceeds get latency p99" in {
      val result   = run(StoreWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 100L)
      val getP99    = result.stats.get(StoreStatKey("get", "latency")).map(_.p99)
      val reportP99 = result.stats.get(StoreStatKey("report", "latency")).map(_.p99)

      getP99 shouldBe defined
      reportP99 shouldBe defined
      reportP99.get should be > getP99.get // reports evaluate the whole set; gets are O(1)
    }
  }
