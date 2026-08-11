package stochastacy.core.sampler

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class DistributionSamplersSpec extends AnyWordSpec with should.Matchers:

  private def freshRng() = RandomSource.KISS.create(99L)

  private def samples[T](s: StatelessSampler[T], n: Int, tick: Long = 1L): Seq[T] =
    val rng   = freshRng()
    var state = s.initialState
    (1 to n).map { _ =>
      val (v, ns) = s.sample(tick, rng, state)
      state = ns
      v
    }

  "PoissonSampler" should {

    "produce non-negative integers" in {
      samples(PoissonSampler.constant(5.0), 200).foreach(_ should be >= 0)
    }

    "have mean approximately equal to lambda" in {
      val vs = samples(PoissonSampler.constant(20.0), 2000)
      vs.sum.toDouble / vs.size shouldBe 20.0 +- 2.0
    }

    "return 0 when lambda is 0" in {
      samples(PoissonSampler.constant(0.0), 50).foreach(_ shouldBe 0)
    }

    "accept a tick-varying lambda" in {
      val s     = PoissonSampler(tick => tick.toDouble)
      val rng   = freshRng()
      val (at0, _) = s.sample(0L, rng, ())
      at0 shouldBe 0
    }
  }

  "NormalSampler" should {

    "have mean approximately equal to the mean parameter" in {
      val vs = samples(NormalSampler.constant(100.0, 5.0), 2000)
      vs.sum / vs.size shouldBe 100.0 +- 2.0
    }

    "produce values on both sides of the mean" in {
      val vs = samples(NormalSampler.constant(50.0, 10.0), 200)
      vs.exists(_ < 50.0) shouldBe true
      vs.exists(_ > 50.0) shouldBe true
    }
  }

  "LogNormalSampler" should {

    "produce only positive values" in {
      samples(LogNormalSampler.constant(0.0, 1.0), 200).foreach(_ should be > 0.0)
    }

    "have median approximately e^mu" in {
      val vs     = samples(LogNormalSampler.constant(2.0, 0.1), 2000).sorted
      val median = vs(vs.size / 2)
      median shouldBe math.exp(2.0) +- 0.5
    }
  }

  "BinomialSampler" should {

    "produce values in [0, n]" in {
      val n  = 10
      samples(BinomialSampler.constant(n, 0.4), 200).foreach { v =>
        v should be >= 0
        v should be <= n
      }
    }

    "have mean approximately n*p" in {
      val vs = samples(BinomialSampler.constant(20, 0.3), 2000)
      vs.sum.toDouble / vs.size shouldBe 6.0 +- 1.0
    }
  }

  "UniformSampler" should {

    "produce values within [min, max]" in {
      samples(UniformSampler.constant(2.0, 8.0), 200).foreach { v =>
        v should be >= 2.0
        v should be <= 8.0
      }
    }

    "have mean approximately (min + max) / 2" in {
      val vs = samples(UniformSampler.constant(0.0, 10.0), 2000)
      vs.sum / vs.size shouldBe 5.0 +- 0.5
    }
  }

  "BernoulliSampler" should {

    "always return false when p = 0" in {
      samples(BernoulliSampler.constant(0.0), 100).foreach(_ shouldBe false)
    }

    "always return true when p = 1" in {
      samples(BernoulliSampler.constant(1.0), 100).foreach(_ shouldBe true)
    }

    "produce true approximately p fraction of the time" in {
      val vs = samples(BernoulliSampler.constant(0.7), 2000)
      vs.count(identity).toDouble / vs.size shouldBe 0.7 +- 0.05
    }
  }

  "ConstantSampler" should {

    "always return the same value regardless of tick or rng" in {
      val s   = ConstantSampler(42)
      val rng = freshRng()
      (1 to 20).foreach { tick =>
        val (v, _) = s.sample(tick.toLong, rng, ())
        v shouldBe 42
      }
    }

    "work with any type" in {
      val s        = ConstantSampler("hello")
      val (v, _)   = s.sample(1L, freshRng(), ())
      v shouldBe "hello"
    }
  }

  "All StatelessSamplers" should {

    "have initialState ()" in {
      val samplers: Seq[StatelessSampler[?]] = Seq(
        PoissonSampler.constant(1.0),
        NormalSampler.constant(0.0, 1.0),
        LogNormalSampler.constant(0.0, 1.0),
        BinomialSampler.constant(5, 0.5),
        UniformSampler.constant(0.0, 1.0),
        BernoulliSampler.constant(0.5),
        ConstantSampler(0)
      )
      samplers.foreach(_.initialState shouldBe ())
    }

    "return () as the updated state" in {
      val rng = freshRng()
      val (_, s1) = PoissonSampler.constant(5.0).sample(1L, rng, ())
      val (_, s2) = NormalSampler.constant(0.0, 1.0).sample(1L, rng, ())
      val (_, s3) = ConstantSampler(0).sample(1L, rng, ())
      s1 shouldBe ()
      s2 shouldBe ()
      s3 shouldBe ()
    }
  }
