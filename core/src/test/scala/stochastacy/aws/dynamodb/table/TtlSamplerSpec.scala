package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TtlSamplerSpec extends AnyWordSpec with should.Matchers:

  "SimpleTtlSampler" should {

    "return empty expiry when no writes have occurred" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = 10)
      val result = sampler.expiryAt(TtlSamplerContext(tick = 10))
      result shouldBe TtlExpirySample.empty
    }

    "expire items written exactly ttlPeriodTicks ticks ago" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = 5)
      sampler.recordWrite(bytes = 100L, tick = 1L)
      sampler.recordWrite(bytes = 200L, tick = 1L)

      // Items written at tick 1 should expire when expiryAt(tick = 1 + 5 = 6) is called
      val result = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      result.expiredItemCount shouldBe 2L
      result.baseTableBytesFreed shouldBe 300L
    }

    "not expire items written at a different tick" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = 5)
      sampler.recordWrite(bytes = 100L, tick = 2L)

      // Expiry at tick 6 drains slot for tick 1, not tick 2
      val result = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      result shouldBe TtlExpirySample.empty
    }

    "drain each slot only once" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = 5)
      sampler.recordWrite(bytes = 100L, tick = 1L)

      val first = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      first.expiredItemCount shouldBe 1L

      val second = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      second shouldBe TtlExpirySample.empty
    }

    "account for intermediate deletes by removing from the soonest-to-expire slot" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = 5)
      sampler.recordWrite(bytes = 100L, tick = 1L)
      sampler.recordWrite(bytes = 100L, tick = 1L)

      // Delete one item before TTL fires — it should be removed from slot 1
      sampler.recordDelete(tick = 3L)

      val result = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      result.expiredItemCount shouldBe 1L
    }

    "include GSI and LSI freed bytes per expired item when configured" in {
      val sampler = SimpleTtlSampler(
        ttlPeriodTicks = 5,
        gsiFreedBytesPerItem = Map("status-index" -> 50L),
        lsiFreedBytesPerItem = Map("time-index" -> 30L)
      )
      sampler.recordWrite(bytes = 100L, tick = 1L)
      sampler.recordWrite(bytes = 100L, tick = 1L)

      val result = sampler.expiryAt(TtlSamplerContext(tick = 6L))
      result.expiredItemCount shouldBe 2L
      result.gsiStorageFreed shouldBe Map("status-index" -> 100L)
      result.lsiStorageFreed shouldBe Map("time-index" -> 60L)
    }

  }
