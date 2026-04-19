package stochastacy.aws.dynamodb.pricing

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.usage.{DynamoDbTargetTimeBasedUsageTotals, DynamoDbTargetUsageTotals, DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}

class DynamoDbPricingSpec extends AnyWordSpec with should.Matchers:

  private val rates = DynamoDbPricingRates(
    readCapacityUnitPrice = BigDecimal(2.0),
    writeCapacityUnitPrice = BigDecimal(5.0),
    storagePricePerGiBSecond = BigDecimal(4.0)
  )

  "DynamoDbCostBreakdown" should {
    "price read-only usage from aggregate totals" in {
      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(
            overall = DynamoDbTargetUsageTotals(
              readCapacityUnits = BigDecimal(1.5)
            )
          ),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals()
        ),
        rates = rates
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(3.0),
        writeCapacityCost = BigDecimal(0),
        storageCost = BigDecimal(0),
        totalCost = BigDecimal(3.0)
      )
    }

    "price write-only usage from aggregate totals" in {
      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(
            overall = DynamoDbTargetUsageTotals(
              writeCapacityUnits = BigDecimal(2.0)
            )
          ),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals()
        ),
        rates = rates
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(0),
        writeCapacityCost = BigDecimal(10.0),
        storageCost = BigDecimal(0),
        totalCost = BigDecimal(10.0)
      )
    }

    "price storage-only usage from time-based totals" in {
      val storageByteTicks = BigInt(1024).pow(3) * 3

      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(
            overallStorageByteTicks = storageByteTicks,
            endingOverallStorageBytes = 0L,
            byTarget = Map.empty
          )
        ),
        rates = rates
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(0),
        writeCapacityCost = BigDecimal(0),
        storageCost = BigDecimal(12.0),
        totalCost = BigDecimal(12.0)
      )
    }

    "price mixed usage across request-priced and duration-priced inputs" in {
      val storageByteTicks = BigInt(1024).pow(3) * 2

      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(
            overall = DynamoDbTargetUsageTotals(
              readCapacityUnits = BigDecimal(0.5),
              writeCapacityUnits = BigDecimal(3.0)
            )
          ),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(
            overallStorageByteTicks = storageByteTicks,
            endingOverallStorageBytes = 0L,
            byTarget = Map.empty
          )
        ),
        rates = rates
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(1.0),
        writeCapacityCost = BigDecimal(15.0),
        storageCost = BigDecimal(8.0),
        totalCost = BigDecimal(24.0)
      )
    }

    "return zero cost for zero usage" in {
      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(
            byTarget = Map.empty,
            overallStorageByteTicks = BigInt(0),
            endingOverallStorageBytes = 0L
          )
        ),
        rates = rates
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(0),
        writeCapacityCost = BigDecimal(0),
        storageCost = BigDecimal(0),
        totalCost = BigDecimal(0)
      )
    }
  }
