package stochastacy.aws.dynamodb.pricing

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.DynamoDbTable
import stochastacy.aws.dynamodb.usage.{DynamoDbTargetTimeBasedUsageTotals, DynamoDbTargetUsageTotals, DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}

class DynamoDbPricingSpec extends AnyWordSpec with should.Matchers:

  private val rateSet = DynamoDbPricingRates.RateSet(
    readCapacityUnitPrice = BigDecimal(2.0),
    writeCapacityUnitPrice = BigDecimal(5.0),
    replicatedWriteCapacityUnitPrice = BigDecimal(3.0),
    storagePricePerGiBSecond = BigDecimal(4.0)
  )
  private val rates = DynamoDbPricingRates(standard = rateSet)

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

    "price replicated-write-only usage separately from WCU" in {
      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(
            overall = DynamoDbTargetUsageTotals(
              replicatedWriteCapacityUnits = BigDecimal(4.0)
            )
          ),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals()
        ),
        rates = rates
      )

      breakdown.writeCapacityCost shouldBe BigDecimal(0)
      breakdown.replicatedWriteCapacityCost shouldBe BigDecimal(12.0)
      breakdown.totalCost shouldBe BigDecimal(12.0)
    }

    "phase1Default rWCU price is lower than WCU price" in {
      val defaults = DynamoDbPricingRates.phase1Default
      defaults.standard.replicatedWriteCapacityUnitPrice should be < defaults.standard.writeCapacityUnitPrice
      defaults.standard.replicatedWriteCapacityUnitPrice shouldBe BigDecimal("0.000000975")
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

  "DynamoDbCostBreakdown (table class)" should {
    val defaultRates = DynamoDbPricingRates.phase1Default

    def writeOnlyInputs(writeUnits: BigDecimal): DynamoDbPricingInputs =
      DynamoDbPricingInputs(
        usage = DynamoDbUsageTotals(overall = DynamoDbTargetUsageTotals(writeCapacityUnits = writeUnits)),
        timeBasedUsage = DynamoDbTimeBasedUsageTotals()
      )

    def storageOnlyInputs(storageByteTicks: BigInt): DynamoDbPricingInputs =
      DynamoDbPricingInputs(
        usage = DynamoDbUsageTotals(),
        timeBasedUsage = DynamoDbTimeBasedUsageTotals(
          overallStorageByteTicks = storageByteTicks,
          endingOverallStorageBytes = 0L,
          byTarget = Map.empty
        )
      )

    "Standard-IA produces lower writeCapacityCost than Standard for identical write usage" in {
      val inputs = writeOnlyInputs(BigDecimal(1000))
      val standardCost = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.Standard)
      val iaCost       = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.StandardInfrequentAccess)
      iaCost.writeCapacityCost should be < standardCost.writeCapacityCost
    }

    "Standard-IA produces higher storageCost than Standard for identical storage" in {
      val inputs = storageOnlyInputs(BigInt(1024).pow(3) * 100)
      val standardCost = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.Standard)
      val iaCost       = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.StandardInfrequentAccess)
      iaCost.storageCost should be > standardCost.storageCost
    }

    "Standard-IA total cost is lower when throughput dominates (zero storage)" in {
      val inputs = writeOnlyInputs(BigDecimal(1_000_000))
      val standardCost = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.Standard)
      val iaCost       = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.StandardInfrequentAccess)
      iaCost.totalCost should be < standardCost.totalCost
    }

    "Standard-IA total cost is higher when storage dominates (zero throughput)" in {
      val inputs = storageOnlyInputs(BigInt(1024).pow(3) * 100_000)
      val standardCost = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.Standard)
      val iaCost       = DynamoDbCostBreakdown.price(inputs, defaultRates, DynamoDbTable.TableClass.StandardInfrequentAccess)
      iaCost.totalCost should be > standardCost.totalCost
    }
  }
