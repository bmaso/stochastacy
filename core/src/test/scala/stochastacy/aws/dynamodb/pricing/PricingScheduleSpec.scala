package stochastacy.aws.dynamodb.pricing

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class PricingScheduleSpec extends AnyWordSpec with should.Matchers:

  private val usEast1Rates = DynamoDbPricingRates(
    standard = DynamoDbPricingRates.RateSet(
      readCapacityUnitPrice    = BigDecimal("0.00000025"),
      writeCapacityUnitPrice   = BigDecimal("0.00000125"),
      storagePricePerGiBSecond = BigDecimal("0.000000096451")
    )
  )
  private val euWest1Rates = DynamoDbPricingRates(
    standard = DynamoDbPricingRates.RateSet(
      readCapacityUnitPrice    = BigDecimal("0.000000283"),
      writeCapacityUnitPrice   = BigDecimal("0.0000014"),
      storagePricePerGiBSecond = BigDecimal("0.000000108507")
    )
  )
  private val fallbackRates = DynamoDbPricingRates.phase1Default

  "PricingSchedule.uniform" should {
    "return the supplied rates for any region and any tick" in {
      val schedule = PricingSchedule.uniform(usEast1Rates)
      schedule.ratesAt("us-east-1",      1L)    shouldBe usEast1Rates
      schedule.ratesAt("eu-west-1",      9999L) shouldBe usEast1Rates
      schedule.ratesAt("ap-southeast-1", 0L)    shouldBe usEast1Rates
      schedule.defaultRates                     shouldBe usEast1Rates
    }
  }

  "PricingSchedule.byRegion" should {
    "return region-specific rates for a known region" in {
      val schedule = PricingSchedule.byRegion(
        Map("us-east-1" -> usEast1Rates, "eu-west-1" -> euWest1Rates),
        fallback = fallbackRates
      )
      schedule.ratesAt("us-east-1", 100L) shouldBe usEast1Rates
      schedule.ratesAt("eu-west-1", 100L) shouldBe euWest1Rates
    }

    "return the fallback rates for an unknown region" in {
      val schedule = PricingSchedule.byRegion(
        Map("us-east-1" -> usEast1Rates),
        fallback = fallbackRates
      )
      schedule.ratesAt("ap-southeast-1", 100L) shouldBe fallbackRates
      schedule.ratesAt("", 100L)               shouldBe fallbackRates
    }

    "expose the fallback via defaultRates" in {
      val schedule = PricingSchedule.byRegion(
        Map("us-east-1" -> usEast1Rates),
        fallback = fallbackRates
      )
      schedule.defaultRates shouldBe fallbackRates
    }

    "ignore the tick parameter — same result at tick 1 and tick 99999" in {
      val schedule = PricingSchedule.byRegion(
        Map("us-east-1" -> usEast1Rates),
        fallback = fallbackRates
      )
      schedule.ratesAt("us-east-1",      1L)    shouldBe schedule.ratesAt("us-east-1",      99999L)
      schedule.ratesAt("ap-southeast-1", 1L)    shouldBe schedule.ratesAt("ap-southeast-1", 99999L)
    }
  }
