package stochastacy.core.sampler

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class SamplerSpec extends AnyWordSpec with should.Matchers:

  private val rng = RandomSource.KISS.create(42L)

  "Sampler.stateless" should {

    "have initialState ()" in {
      val s = Sampler.stateless[Int]((_, _) => 0)
      s.initialState shouldBe ()
    }

    "return () as updated state on every call" in {
      val s = Sampler.stateless[Int]((tick, _) => tick.toInt)
      val (_, nextState) = s.sample(1L, rng, ())
      nextState shouldBe ()
    }

    "invoke the wrapped function with the given tick" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val s = Sampler.stateless[Unit] { (tick, _) => observed += tick }
      s.sample(7L, rng, ())
      s.sample(99L, rng, ())
      observed.toSeq shouldBe Seq(7L, 99L)
    }

    "return the value produced by the wrapped function" in {
      val s = Sampler.stateless[String]((tick, _) => s"tick=$tick")
      val (v, _) = s.sample(42L, rng, ())
      v shouldBe "tick=42"
    }
  }
