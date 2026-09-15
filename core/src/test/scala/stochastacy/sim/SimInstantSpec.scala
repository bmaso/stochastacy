package stochastacy.sim

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.Timed

class SimInstantSpec extends AnyWordSpec with should.Matchers:

  "SimInstant" should {
    "order by tick, then intraTick" in {
      List(SimInstant(2L, 0.5), SimInstant(1L, 0.9), SimInstant(2L, 0.1)).sorted shouldBe
        List(SimInstant(1L, 0.9), SimInstant(2L, 0.1), SimInstant(2L, 0.5))
    }

    "convert to a single conceptual-time number" in {
      SimInstant(3L, 0.25).toDouble shouldBe 3.25
    }

    "take the conceptual time of a timed event" in {
      SimInstant.of(Timed("payload", SimTime.of(5L), 0.7, "uc")) shouldBe SimInstant(5L, 0.7)
      SimInstant.of(TimedControlEvent.Tick(SimTime.of(4L))) shouldBe SimInstant(4L, 0.0)
    }

    "advance by a fractional-tick delay using the rawOffset rule" in {
      val a = SimInstant(5L, 0.7).plus(2.0)
      a.tick shouldBe 7L
      a.intraTick shouldBe (0.7 +- 1e-9)

      val b = SimInstant(1L, 0.7).plus(0.5) // crosses into the next tick
      b.tick shouldBe 2L
      b.intraTick shouldBe (0.2 +- 1e-9)

      SimInstant(1L, 0.2).plus(0.0) shouldBe SimInstant(1L, 0.2)
    }

    "reject an intraTick outside [0, 1)" in {
      an[IllegalArgumentException] should be thrownBy SimInstant(1L, -0.1)
      an[IllegalArgumentException] should be thrownBy SimInstant(1L, 1.0)
    }
  }
