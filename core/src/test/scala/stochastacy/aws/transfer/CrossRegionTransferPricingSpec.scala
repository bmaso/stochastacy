package stochastacy.aws.transfer

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.SimTime

class CrossRegionTransferPricingSpec extends AnyWordSpec with should.Matchers:

  private val OneGiB = 1024L * 1024L * 1024L

  "CrossRegionTransferCostBreakdown.price" should {

    "return zero cost for empty totals" in {
      val rates = CrossRegionTransferPricingRates(
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
      val rates = CrossRegionTransferPricingRates(
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

      val rates = CrossRegionTransferPricingRates(
        pricePerGiBBySourceRegion = Map(
          "us-east-1" -> BigDecimal("0.02"),
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
      val rates = CrossRegionTransferPricingRates(
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
      val rates = CrossRegionTransferPricingRates(
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
      val rates = CrossRegionTransferPricingRates(
        pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("0.02"))
      )
      val breakdown = CrossRegionTransferCostBreakdown.price(totals, rates)
      breakdown.totalCost shouldBe BigDecimal("0.01")
    }
  }

  "CrossRegionTransferPricingRates" should {
    "reject negative rates" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates(pricePerGiBBySourceRegion = Map("us-east-1" -> BigDecimal("-0.01")))
    }

    "reject negative default rate" in {
      an[IllegalArgumentException] should be thrownBy
        CrossRegionTransferPricingRates(defaultPricePerGiB = Some(BigDecimal("-0.01")))
    }
  }
