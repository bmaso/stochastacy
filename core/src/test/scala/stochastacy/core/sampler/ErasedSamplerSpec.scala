package stochastacy.core.sampler

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ErasedSamplerSpec extends AnyWordSpec with should.Matchers:

  private def freshRng() = RandomSource.KISS.create(55L)

  "ErasedSampler" should {

    "produce the same output as the underlying stateless sampler" in {
      val base   = ConstantSampler(42)
      val erased = ErasedSampler.of(base)
      val rng    = freshRng()
      erased.sample(1L, rng, ())._1 shouldBe 42
    }

    "thread stateful sampler state correctly across successive calls" in {
      // A sampler that counts how many times it has been called
      var callCount = 0
      val countingSampler = new Sampler[Unit, Int]:
        def initialState: Unit = ()
        def sample(tick: Long, rng: org.apache.commons.rng.UniformRandomProvider, state: Unit): (Int, Unit) =
          callCount += 1
          (callCount, ())

      val erased = ErasedSampler.of(countingSampler)
      val rng    = freshRng()
      erased.sample(1L, rng, ())._1 shouldBe 1
      erased.sample(2L, rng, ())._1 shouldBe 2
      erased.sample(3L, rng, ())._1 shouldBe 3
    }

    "have initialState of Unit" in {
      ErasedSampler.of(ConstantSampler(0)).initialState shouldBe ()
    }
  }
