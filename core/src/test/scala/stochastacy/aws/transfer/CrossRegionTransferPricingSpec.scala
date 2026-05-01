package stochastacy.aws.transfer

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.SimTime

class CrossRegionTransferPricingSpec extends AnyWordSpec with should.Matchers:

  private val OneGiB = 1024L * 1024L * 1024L

  "CrossRegionTransferCostBreakdown.price" should {

    "return zero cost for empty totals" in {
      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(CrossRegionTransferUsageTotals(), rates)

      breakdown.totalCost shouldBe BigDecimal(0)
      breakdown.costByDirectionalPair shouldBe empty
    }

    "price 1 GiB at $0.02 from us-east-1 as $0.02" in {
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB)
      )
      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)

      breakdown.totalCost shouldBe BigDecimal("0.02")
      breakdown.costByDirectionalPair(("us-east-1", "eu-west-1")) shouldBe BigDecimal("0.02")
    }

    "apply per-source-region rates correctly across multiple sources" in {
      val totals = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB),
        CrossRegionTransferEvent(SimTime.of(2L), "u", "ap-southeast-2", "us-east-1", "DynamoDB", OneGiB)
      ).foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)

      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map(
          "us-east-1"     -> BigDecimal("0.02"),
          "ap-southeast-2" -> BigDecimal("0.114")
        )
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)

      breakdown.costByDirectionalPair(("us-east-1", "eu-west-1")) shouldBe BigDecimal("0.02")
      breakdown.costByDirectionalPair(("ap-southeast-2", "us-east-1")) shouldBe BigDecimal("0.114")
      breakdown.totalCost shouldBe BigDecimal("0.134")
    }

    "use defaultPricePerGiB when source region is not in the map" in {
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "unknown-region", "us-east-1", "DynamoDB", OneGiB)
      )
      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02")),
        defaultPricePerGiB = Some(BigDecimal("0.05"))
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)

      breakdown.totalCost shouldBe BigDecimal("0.05")
    }

    "throw when source region is missing from rates and no default is set" in {
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "unknown-region", "us-east-1", "DynamoDB", OneGiB)
      )
      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferCostBreakdown.price(totals, rates)
    }

    "scale linearly with bytes — 0.5 GiB at $0.02 is $0.01" in {
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB / 2)
      )
      val rates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      breakdown.totalCost shouldBe BigDecimal("0.01")
    }

    // --- tiered pricing ---

    "tiered: bytes wholly within first tier are priced at tier-1 rate" in {
      // Tier 1: first 1 GiB at $0.10; Tier 2: remainder at $0.05
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.10")),
        TransferPricingTier(None,         BigDecimal("0.05"))
      )
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB / 2)
      )
      val rates = CrossRegionTransferPricingRates(
        tiersBySourceRegion = Map("us-east-1" -> tiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      // 0.5 GiB × $0.10 = $0.05
      breakdown.totalCost shouldBe BigDecimal("0.05")
    }

    "tiered: bytes exactly filling first tier pay only tier-1 rate" in {
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.10")),
        TransferPricingTier(None,         BigDecimal("0.05"))
      )
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB)
      )
      val rates = CrossRegionTransferPricingRates(
        tiersBySourceRegion = Map("us-east-1" -> tiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      // 1 GiB × $0.10 = $0.10
      breakdown.totalCost shouldBe BigDecimal("0.10")
    }

    "tiered: bytes straddling tier boundary split cost across both tiers" in {
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.10")),
        TransferPricingTier(None,         BigDecimal("0.05"))
      )
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB + OneGiB / 2)
      )
      val rates = CrossRegionTransferPricingRates(
        tiersBySourceRegion = Map("us-east-1" -> tiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      // 1 GiB × $0.10 + 0.5 GiB × $0.05 = $0.10 + $0.025 = $0.125
      breakdown.totalCost shouldBe BigDecimal("0.125")
    }

    "tiered: bytes exceeding all bounded tiers fall into the unbounded tail tier" in {
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB),     BigDecimal("0.10")),
        TransferPricingTier(Some(OneGiB * 4), BigDecimal("0.08")),
        TransferPricingTier(None,             BigDecimal("0.05"))
      )
      // 6 GiB total: 1 GiB in tier-1, 4 GiB in tier-2, 1 GiB in tier-3
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB * 6)
      )
      val rates = CrossRegionTransferPricingRates(
        tiersBySourceRegion = Map("us-east-1" -> tiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      // 1 × $0.10 + 4 × $0.08 + 1 × $0.05 = $0.10 + $0.32 + $0.05 = $0.47
      breakdown.totalCost shouldBe BigDecimal("0.47")
    }

    "tiered: defaultTiers applies when source region has no explicit schedule" in {
      val defaultTiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.09")),
        TransferPricingTier(None,         BigDecimal("0.06"))
      )
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "sa-east-1", "us-east-1", "DynamoDB", OneGiB * 2)
      )
      val rates = CrossRegionTransferPricingRates(
        defaultTiers = Some(defaultTiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      // 1 GiB × $0.09 + 1 GiB × $0.06 = $0.15
      breakdown.totalCost shouldBe BigDecimal("0.15")
    }

    "tiered: single source with two destinations aggregates bytes across pairs before applying tiers" in {
      // Tier: first 1 GiB at $0.10, remainder at $0.05
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.10")),
        TransferPricingTier(None,         BigDecimal("0.05"))
      )
      // us-east-1 sends 3/4 GiB to each of two destinations = 3/2 GiB total, crossing 1 GiB boundary.
      // Per-pair (wrong) would see 0.75 GiB each, both within tier-1 → $0.15.
      // Per-source (correct): 1 GiB × $0.10 + 0.5 GiB × $0.05 = $0.125.
      val totals = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1",      "DynamoDB", OneGiB * 3 / 4),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "ap-southeast-1", "DynamoDB", OneGiB * 3 / 4)
      ).foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)
      val rates = CrossRegionTransferPricingRates(tiersBySourceRegion = Map("us-east-1" -> tiers))
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)

      breakdown.totalCost shouldBe BigDecimal("0.125")
      // Pair costs distribute proportionally (equal halves here) and must sum back to totalCost
      breakdown.costByDirectionalPair.values.foldLeft(BigDecimal(0))(_ + _) shouldBe BigDecimal("0.125")
    }

    "tiered: two source regions apply tier schedules independently of each other" in {
      val tiers = Vector(
        TransferPricingTier(Some(OneGiB), BigDecimal("0.10")),
        TransferPricingTier(None,         BigDecimal("0.05"))
      )
      // us-east-1 → eu-west-1: 1.5 GiB → $0.10 + 0.025 = $0.125
      // eu-west-1 → us-east-1: 0.5 GiB → $0.05
      val totals = Seq(
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB + OneGiB / 2),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "eu-west-1", "us-east-1", "DynamoDB", OneGiB / 2)
      ).foldLeft(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)
      val rates = CrossRegionTransferPricingRates(
        defaultTiers = Some(tiers)
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)

      breakdown.totalCost shouldBe BigDecimal("0.175")
      breakdown.costByDirectionalPair(("us-east-1", "eu-west-1")) shouldBe BigDecimal("0.125")
      breakdown.costByDirectionalPair(("eu-west-1", "us-east-1")) shouldBe BigDecimal("0.05")
    }
  }

  "CrossRegionTransferPricingRates" should {
    "reject tier schedule whose last tier is bounded" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates(
          tiersBySourceRegion = Map("us-east-1" -> Vector(TransferPricingTier(Some(OneGiB), BigDecimal("0.10"))))
        )
    }

    "reject empty tier schedule" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates(
          tiersBySourceRegion = Map("us-east-1" -> Vector.empty)
        )
    }

    "reject defaultTiers that ends with a bounded tier" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates(
          defaultTiers = Some(Vector(TransferPricingTier(Some(OneGiB), BigDecimal("0.10"))))
        )
    }
  }

  "TransferPricingTier" should {
    "reject negative pricePerGiB" in {
      an[IllegalArgumentException] should be thrownBy
        TransferPricingTier(None, BigDecimal("-0.01"))
    }

    "reject tierBytes of zero" in {
      an[IllegalArgumentException] should be thrownBy
        TransferPricingTier(Some(0L), BigDecimal("0.02"))
    }

    "allow tierBytes of None (unbounded)" in {
      noException should be thrownBy TransferPricingTier(None, BigDecimal("0.02"))
    }

    "allow pricePerGiB of zero (free tier)" in {
      noException should be thrownBy TransferPricingTier(None, BigDecimal(0))
    }
  }

  "CrossRegionTransferPricingRates.flat" should {
    "build a single-tier schedule per region equivalent to tiered with one unbounded tier" in {
      val flatRates = CrossRegionTransferPricingRates.flat(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      val tieredRates = CrossRegionTransferPricingRates(
        tiersBySourceRegion = Map("us-east-1" -> Vector(TransferPricingTier(None, BigDecimal("0.02"))))
      )
      val totals = CrossRegionTransferUsageTotals.accumulate(
        CrossRegionTransferUsageTotals(),
        CrossRegionTransferEvent(SimTime.of(1L), "u", "us-east-1", "eu-west-1", "DynamoDB", OneGiB * 3)
      )
      CrossRegionTransferCostBreakdown.price(totals, flatRates).totalCost shouldBe
        CrossRegionTransferCostBreakdown.price(totals, tieredRates).totalCost
    }

    "reject negative flat rate (propagates to tier validation)" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates.flat(pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("-0.01")))
    }

    "reject negative default flat rate" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates.flat(defaultPricePerGiB = Some(BigDecimal("-0.01")))
    }
  }
