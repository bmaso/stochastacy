package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TtlRingBufferSpec extends AnyWordSpec with should.Matchers:

  "TtlRingBuffer" should {

    "expire a write exactly ttlPeriodTicks after it was recorded, then clear that slot" in {
      val rb = TtlRingBuffer.empty(3).recordWrite(500L, tick = 10L)

      // nothing expires before the period elapses
      (11L to 12L).foreach { t =>
        val (count, bytes, _) = rb.expire(t)
        (count, bytes) shouldBe (0L, 0L)
      }
      // the cohort written at tick 10 expires at tick 13 (10 + 3)
      val (count, bytes, drained) = rb.expire(13L)
      (count, bytes) shouldBe (1L, 500L)
      // the slot is cleared: expiring the same slot again yields nothing
      drained.expire(13L) shouldBe ((0L, 0L, drained))
    }

    "accumulate multiple writes into the same tick's slot" in {
      val rb = TtlRingBuffer.empty(2).recordWrite(100L, 5L).recordWrite(250L, 5L)
      val (count, bytes, _) = rb.expire(7L) // 5 + 2
      (count, bytes) shouldBe (2L, 350L)
    }

    "wrap around the circular buffer for ticks beyond its size" in {
      // size = ttlPeriodTicks + 1 = 5; ticks 3 and 8 map to the same slot (8 % 5 == 3).
      val rb = TtlRingBuffer.empty(4).recordWrite(400L, 3L)
      // a write at tick 8 lands in the same slot as tick 3 — they accumulate
      val (count, bytes, _) = rb.recordWrite(600L, 8L).expire(12L) // slot(12 - 4) = slot(8) = 3
      (count, bytes) shouldBe (2L, 1000L)
    }

    "approximate an intermediate delete by removing one item from the soonest-to-expire non-empty slot" in {
      // two cohorts: tick 5 (soonest to expire), tick 6. A delete at tick 6 removes from tick 5's slot.
      val rb = TtlRingBuffer.empty(3)
        .recordWrite(200L, 5L)
        .recordWrite(400L, 6L)
        .recordDelete(6L)

      val (c5, b5, _) = rb.expire(8L) // 5 + 3 → tick-5 cohort, one item removed
      (c5, b5) shouldBe (0L, 0L)
      val (c6, b6, _) = rb.expire(9L) // 6 + 3 → tick-6 cohort, untouched
      (c6, b6) shouldBe (1L, 400L)
    }

    "leave an overwrite (delete + re-write in the same tick) count-neutral but re-aged" in {
      // write at tick 2, then at tick 4 overwrite it (delete oldest + write new).
      val rb = TtlRingBuffer.empty(5)
        .recordWrite(300L, 2L)
        .recordDelete(4L).recordWrite(320L, 4L)

      val (c2, _, _) = rb.expire(7L) // 2 + 5 → original cohort was deleted
      c2 shouldBe 0L
      val (c4, b4, _) = rb.expire(9L) // 4 + 5 → re-aged item expires here
      (c4, b4) shouldBe (1L, 320L)
    }

    "treat a delete against an all-empty buffer as a no-op" in {
      val rb = TtlRingBuffer.empty(2)
      rb.recordDelete(10L) shouldBe rb
    }

    "reject a ttlPeriodTicks below 1" in {
      an[IllegalArgumentException] should be thrownBy TtlRingBuffer.empty(0)
    }
  }
