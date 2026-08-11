package stochastacy.core.sampler

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class SamplerCombinatorsSpec extends AnyWordSpec with should.Matchers:

  private def freshRng() = RandomSource.KISS.create(77L)

  private def sampleOnce[S, T](s: Sampler[S, T], tick: Long): T =
    s.sample(tick, freshRng(), s.initialState)._1

  // ── MappedSampler ──────────────────────────────────────────────────────────

  "MappedSampler" should {

    "pass the transformed tick to the base sampler" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val base = Sampler.deterministic[Unit] { tick => observed += tick }
      val mapped = MappedSampler(base, tick => tick * 2, (_, v) => v)
      mapped.sample(5L, freshRng(), ())
      observed.head shouldBe 10L
    }

    "pass the original (pre-transform) tick to outputTransform" in {
      val base   = ConstantSampler(0.0)
      val mapped = MappedSampler(base, tick => tick * 10, (origTick, _) => origTick.toDouble)
      sampleOnce(mapped, 7L) shouldBe 7.0
    }

    "return the value produced by outputTransform" in {
      val base   = ConstantSampler(4.0)
      val mapped = MappedSampler(base, identity, (_, v) => v * 3.0)
      sampleOnce(mapped, 1L) shouldBe 12.0
    }

    "thread base sampler state through unchanged" in {
      val base             = ConstantSampler(1.0)
      val mapped           = MappedSampler(base, identity, (_, v) => v)
      val (_, resultState) = mapped.sample(1L, freshRng(), ())
      resultState shouldBe ()
    }

    "initialState equals base sampler initialState" in {
      MappedSampler(ConstantSampler(0), identity, (_, v) => v).initialState shouldBe ()
    }
  }

  "MappedSampler.periodic" should {

    "apply tick % period before sampling" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val base     = Sampler.deterministic[Unit] { tick => observed += tick }
      val s        = MappedSampler.periodic(base, 100L)
      s.sample(350L, freshRng(), ())
      observed.head shouldBe 50L
    }

    "produce same output for tick T and tick T + period" in {
      val base = Sampler.deterministic(tick => tick.toDouble)
      val s    = MappedSampler.periodic(base, 200L)
      sampleOnce(s, 75L) shouldBe sampleOnce(s, 275L)
    }
  }

  "MappedSampler.shift" should {

    "subtract offset from tick before sampling" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val base     = Sampler.deterministic[Unit] { tick => observed += tick }
      val s        = MappedSampler.shift(base, 50L)
      s.sample(120L, freshRng(), ())
      observed.head shouldBe 70L
    }
  }

  "MappedSampler.stretch" should {

    "divide tick by factor before sampling" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val base     = Sampler.deterministic[Unit] { tick => observed += tick }
      val s        = MappedSampler.stretch(base, 4L)
      s.sample(100L, freshRng(), ())
      observed.head shouldBe 25L
    }
  }

  // ── CombiningSampler ──────────────────────────────────────────────────────────

  "CombiningSampler" should {

    "call combineOutput with the original tick and both base outputs" in {
      val observedTick  = collection.mutable.ArrayBuffer.empty[Long]
      val observedA     = collection.mutable.ArrayBuffer.empty[Double]
      val observedB     = collection.mutable.ArrayBuffer.empty[Double]
      val s = CombiningSampler(
        ConstantSampler(3.0),
        ConstantSampler(7.0),
        (tick: Long, a: Double, b: Double) => { observedTick += tick; observedA += a; observedB += b; a + b }
      )
      s.sample(42L, freshRng(), s.initialState)
      observedTick.head shouldBe 42L
      observedA.head    shouldBe 3.0
      observedB.head    shouldBe 7.0
    }

    "initialState is (baseA.initialState, baseB.initialState)" in {
      val s = CombiningSampler(ConstantSampler(1.0), ConstantSampler(2.0), (_, a: Double, b: Double) => a + b)
      s.initialState shouldBe ((), ())
    }

    "update both base states independently" in {
      val s              = CombiningSampler(ConstantSampler(1.0), ConstantSampler(2.0), (_, a: Double, b: Double) => a + b)
      val (_, newState)  = s.sample(1L, freshRng(), s.initialState)
      newState shouldBe ((), ())
    }
  }

  "CombiningSampler.sum" should {

    "return the sum of both base samples" in {
      val s = CombiningSampler.sum(ConstantSampler(3.0), ConstantSampler(7.0))
      sampleOnce(s, 1L) shouldBe 10.0
    }
  }

  "CombiningSampler.product" should {

    "return the product of both base samples" in {
      val s = CombiningSampler.product(ConstantSampler(4.0), ConstantSampler(2.5))
      sampleOnce(s, 1L) shouldBe 10.0
    }
  }

  "CombiningSampler.overlay" should {

    "return baseA output when condition is true" in {
      val s = CombiningSampler.overlay(ConstantSampler(10.0), ConstantSampler(99.0), _ => true)
      sampleOnce(s, 1L) shouldBe 10.0
    }

    "return baseB output when condition is false" in {
      val s = CombiningSampler.overlay(ConstantSampler(10.0), ConstantSampler(99.0), _ => false)
      sampleOnce(s, 1L) shouldBe 99.0
    }

    "switch between A and B based on tick" in {
      val s = CombiningSampler.overlay(ConstantSampler(1.0), ConstantSampler(0.0), tick => tick < 100L)
      sampleOnce(s, 50L)  shouldBe 1.0
      sampleOnce(s, 150L) shouldBe 0.0
    }
  }

  // ── Integration: temporal shape functions composed with combinators ─────────

  "CombiningSampler.product with Sampler.deterministic" should {

    "apply linear growth to a constant base" in {
      val s = CombiningSampler.product(
        ConstantSampler(10.0),
        Sampler.deterministic(TemporalShapeFunctions.linearFactor(1.0))
      )
      sampleOnce(s, 0L) shouldBe 10.0  // 10.0 * (1 + 1.0*0) = 10.0
      sampleOnce(s, 1L) shouldBe 20.0  // 10.0 * (1 + 1.0*1) = 20.0
      sampleOnce(s, 2L) shouldBe 30.0  // 10.0 * (1 + 1.0*2) = 30.0
    }

    "apply sinusoidal modulation to a constant base" in {
      val s = CombiningSampler.product(
        ConstantSampler(1.0),
        Sampler.deterministic(TemporalShapeFunctions.sinusoid(0.5, 1.5, 1440L, 720L))
      )
      sampleOnce(s, 720L)  shouldBe 1.5 +- 0.001   // at peak
      sampleOnce(s, 1440L) shouldBe 0.5 +- 0.001   // at trough
    }
  }

  "CombiningSampler.overlay with weekdays condition" should {

    "return active sampler on weekdays and identity on weekends" in {
      val s = CombiningSampler.overlay(
        ConstantSampler(5.0),
        ConstantSampler(0.0),
        TemporalShapeFunctions.weekdays(60L)
      )
      sampleOnce(s, 0L)          shouldBe 5.0   // Monday
      sampleOnce(s, 60L * 4)     shouldBe 5.0   // Friday
      sampleOnce(s, 60L * 5)     shouldBe 0.0   // Saturday
      sampleOnce(s, 60L * 6)     shouldBe 0.0   // Sunday
      sampleOnce(s, 60L * 7)     shouldBe 5.0   // Monday again
    }
  }
