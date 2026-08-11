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
  }
