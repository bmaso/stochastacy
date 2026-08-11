package stochastacy.core.sampler

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TemporalShapeFunctionsSpec extends AnyWordSpec with should.Matchers:

  import TemporalShapeFunctions.*

  "sinusoid" should {

    "return max at peakTick" in {
      sinusoid(10.0, 200.0, 1440L, 720L)(720L) shouldBe 200.0 +- 0.001
    }

    "return min at peakTick + periodTicks / 2" in {
      sinusoid(10.0, 200.0, 1440L, 720L)(1440L) shouldBe 10.0 +- 0.001
    }

    "be periodic: same value at tick t and t + periodTicks" in {
      val f = sinusoid(10.0, 200.0, 1440L, 720L)
      f(300L) shouldBe f(1740L) +- 0.001
    }

    "always produce values in [min, max]" in {
      val f = sinusoid(10.0, 200.0, 1440L, 720L)
      (0L to 1440L).foreach { t => f(t) should (be >= 10.0 and be <= 200.0) }
    }
  }

  "linearFactor" should {

    "return 1.0 at tick 0" in {
      linearFactor(0.5)(0L) shouldBe 1.0 +- 0.001
    }

    "grow by ratePerTick each tick" in {
      val f = linearFactor(0.1)
      f(10L) shouldBe 2.0 +- 0.001
      f(20L) shouldBe 3.0 +- 0.001
    }
  }

  "triangularFactor" should {

    "return 1.0 before start" in {
      triangularFactor(100L, 300L, 3.0)(50L) shouldBe 1.0 +- 0.001
    }

    "return 1.0 after end" in {
      triangularFactor(100L, 300L, 3.0)(350L) shouldBe 1.0 +- 0.001
    }

    "return peakMultiplier at the midpoint" in {
      triangularFactor(100L, 300L, 3.0)(200L) shouldBe 3.0 +- 0.001
    }

    "return peakMultiplier when start == end" in {
      triangularFactor(200L, 200L, 4.0)(200L) shouldBe 4.0 +- 0.001
    }

    "ramp up to midpoint and back down to end" in {
      val f = triangularFactor(100L, 300L, 3.0)
      f(100L) shouldBe 1.0 +- 0.001
      f(150L) should (be > 1.0 and be < 3.0)
      f(200L) shouldBe 3.0 +- 0.001
      f(250L) should (be > 1.0 and be < 3.0)
      f(300L) shouldBe 1.0 +- 0.001
    }
  }

  "weekdays" should {

    val ticksPerDay = 60L

    "return true on Monday (day 0)" in { weekdays(ticksPerDay)(0L) shouldBe true }
    "return true on Friday (day 4)"  in { weekdays(ticksPerDay)(ticksPerDay * 4) shouldBe true }
    "return false on Saturday (day 5)" in { weekdays(ticksPerDay)(ticksPerDay * 5) shouldBe false }
    "return false on Sunday (day 6)"   in { weekdays(ticksPerDay)(ticksPerDay * 6) shouldBe false }

    "wrap correctly into the following Monday" in {
      weekdays(ticksPerDay)(ticksPerDay * 7) shouldBe true
    }
  }
