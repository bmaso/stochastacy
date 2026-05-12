package stochastacy.workload

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class RandomBurstSamplerSpec extends AnyWordSpec with should.Matchers:

  private def freshRng() = RandomSource.KISS.create(99L)

  private def sampleN(s: RandomBurstSampler[Unit], n: Int): Vector[Int] =
    var state = s.initialState
    val rng   = freshRng()
    (1 to n).toVector.map { tick =>
      val (v, ns) = s.sample(tick.toLong, rng, state)
      state = ns
      v
    }

  "RandomBurstSampler" should {

    "never produce burst traffic when probability is 0.0" in {
      val base = ConstantSampler(0.0)
      val s    = RandomBurstSampler.constant(base, probability = 0.0, durationTicks = 10, burstAmount = 1000.0)
      // With base lambda = 0 and no burst, all counts must be 0
      sampleN(s, 50).forall(_ == 0) shouldBe true
    }

    "trigger a burst on the first tick when probability is 1.0" in {
      val base = ConstantSampler(0.0)
      // probability=1.0 means a burst triggers at tick 1 (if not already active)
      val s    = RandomBurstSampler.constant(base, probability = 1.0, durationTicks = 3, burstAmount = 1000.0)
      // ticks 1,2,3 are in burst (durationTicks=3 => remaining becomes 2,1,0)
      // tick 4 is not in burst (triggers again since probability=1.0)
      val counts = sampleN(s, 6)
      // All counts should be well above 0 since burstAmount=1000 and probability=1.0
      counts.forall(_ > 0) shouldBe true
    }

    "add burstAmount to base lambda during active burst ticks" in {
      // Use a large burstAmount so the difference is statistically obvious
      val base = ConstantSampler(1.0)
      val s    = RandomBurstSampler.constant(base, probability = 1.0, durationTicks = 100, burstAmount = 10000.0)
      val rng  = freshRng()
      val (count, _) = s.sample(1L, rng, s.initialState)
      // Poisson(10001) should produce a count far above Poisson(1)
      count should be > 1000
    }

    "apply tick-dependent burstAmount at the correct tick" in {
      val observed = collection.mutable.ArrayBuffer.empty[Long]
      val base = ConstantSampler(0.0)
      // burst always active (probability=1), capture which tick burstAmount sees
      val s = RandomBurstSampler(base, probability = 1.0, durationTicks = 999, tick => { observed += tick; 0.0 })
      var state = s.initialState
      val rng   = freshRng()
      (1L to 3L).foreach { tick =>
        val (_, ns) = s.sample(tick, rng, state)
        state = ns
      }
      observed.toVector shouldBe Vector(1L, 2L, 3L)
    }

    "have initialState with ticksRemaining == 0 (no burst active)" in {
      val s = RandomBurstSampler.constant(ConstantSampler(5.0), 0.5, 10, 100.0)
      s.initialState._1 shouldBe 0
    }
  }
