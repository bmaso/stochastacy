package stochastacy.aws.dynamodb.pricing

import stochastacy.aws.dynamodb.usage.{DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}

final case class DynamoDbPricingInputs(
                                        usage: DynamoDbUsageTotals,
                                        timeBasedUsage: DynamoDbTimeBasedUsageTotals
                                      )

final case class DynamoDbPricingRates(
                                        readCapacityUnitPrice: BigDecimal,
                                        writeCapacityUnitPrice: BigDecimal,
                                        replicatedWriteCapacityUnitPrice: BigDecimal = BigDecimal("0.000000975"),
                                        storagePricePerGiBSecond: BigDecimal
                                      )

object DynamoDbPricingRates:
  private val SecondsPerHour = BigDecimal(3600)
  private val SecondsPerDay = SecondsPerHour * BigDecimal(24)
  private val SecondsPer30DayMonth = SecondsPerDay * BigDecimal(30)

  /**
   * Phase-1 default rates are intentionally simple and estimate-oriented.
   *
   * Storage pricing uses GiB-seconds:
   * one simulation tick is treated as one second, and 1024^3 bytes are treated as one GiB.
   */
  val phase1Default: DynamoDbPricingRates =
    DynamoDbPricingRates(
      readCapacityUnitPrice = BigDecimal("0.00000025"),
      writeCapacityUnitPrice = BigDecimal("0.00000125"),
      storagePricePerGiBSecond = BigDecimal("0.25") / SecondsPer30DayMonth
    )

final case class DynamoDbCostBreakdown(
                                        readCapacityCost: BigDecimal,
                                        writeCapacityCost: BigDecimal,
                                        replicatedWriteCapacityCost: BigDecimal = BigDecimal(0),
                                        storageCost: BigDecimal,
                                        totalCost: BigDecimal
                                      )

object DynamoDbCostBreakdown:
  private val BytesPerGiB = BigDecimal(1024).pow(3)

  def price(
             inputs: DynamoDbPricingInputs,
             rates: DynamoDbPricingRates = DynamoDbPricingRates.phase1Default
           ): DynamoDbCostBreakdown =
    val readCapacityCost =
      inputs.usage.overall.readCapacityUnits * rates.readCapacityUnitPrice

    val writeCapacityCost =
      inputs.usage.overall.writeCapacityUnits * rates.writeCapacityUnitPrice

    val replicatedWriteCapacityCost =
      inputs.usage.overall.replicatedWriteCapacityUnits * rates.replicatedWriteCapacityUnitPrice

    val storageCost =
      BigDecimal(inputs.timeBasedUsage.overallStorageByteTicks) *
        rates.storagePricePerGiBSecond / BytesPerGiB

    DynamoDbCostBreakdown(
      readCapacityCost = readCapacityCost,
      writeCapacityCost = writeCapacityCost,
      replicatedWriteCapacityCost = replicatedWriteCapacityCost,
      storageCost = storageCost,
      totalCost = readCapacityCost + writeCapacityCost + replicatedWriteCapacityCost + storageCost
    )
