package stochastacy.core.run

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.Future

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class MonteCarloSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("MonteCarloSpec")
  override def afterAll(): Unit = system.terminate()

  private def run[R](n: Int, master: Long, parallelism: Int)(f: Long => R): Vector[R] =
    Await.result(MonteCarlo.run(n, master, parallelism)(seed => Future.successful(f(seed))), 5.seconds)

  "SeedSequence" should {

    "derive exactly `count` seeds" in {
      SeedSequence.derive(42L, 10) should have size 10
      SeedSequence.derive(42L, 0) shouldBe empty
    }

    "be deterministic for the same master seed" in {
      SeedSequence.derive(42L, 16) shouldBe SeedSequence.derive(42L, 16)
    }

    "produce different seed sequences for different master seeds" in {
      SeedSequence.derive(1L, 16) should not be SeedSequence.derive(2L, 16)
    }

    "be a prefix-stable stream (first k of n == derive(k))" in {
      SeedSequence.derive(7L, 20).take(5) shouldBe SeedSequence.derive(7L, 5)
    }
  }

  "MonteCarlo" should {

    "run one trial per derived seed, in seed order" in {
      val seeds = SeedSequence.derive(99L, 8)
      run(8, 99L, parallelism = 4)(identity) shouldBe seeds
    }

    "be independent of parallelism (same master seed → identical results)" in {
      val f: Long => Long = s => s * 31L + 7L
      run(20, 5L, parallelism = 1)(f) shouldBe run(20, 5L, parallelism = 8)(f)
    }

    "preserve input order even when trials complete out of order" in {
      // Later seeds finish sooner; mapAsync must still emit in seed order.
      val seeds = SeedSequence.derive(3L, 6)
      val out = Await.result(
        MonteCarlo.run(6, 3L, parallelism = 6) { seed =>
          val idx = seeds.indexOf(seed)
          Future.successful((idx, seed))
        },
        5.seconds
      )
      out.map(_._1) shouldBe (0 until 6)
      out.map(_._2) shouldBe seeds
    }

    "handle the degenerate trial counts 0 and 1" in {
      run(0, 1L, parallelism = 4)(identity) shouldBe empty
      run(1, 1L, parallelism = 4)(identity) shouldBe SeedSequence.derive(1L, 1)
    }
  }
