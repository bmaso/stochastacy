package stochastacy.aws.dynamodb.pricing

import stochastacy.aws.dynamodb.table.DynamoDbTable
import stochastacy.aws.dynamodb.usage.{DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}

final case class ProvisionedCapacityData(
  totalProvisionedReadCapacityUnitTicks: BigInt,
  totalProvisionedWriteCapacityUnitTicks: BigInt
)

final case class DynamoDbPricingInputs(
  usage: DynamoDbUsageTotals,
  timeBasedUsage: DynamoDbTimeBasedUsageTotals,
  provisionedCapacity: Option[ProvisionedCapacityData] = None
)

object DynamoDbPricingRates:
  private val SecondsPerHour = BigDecimal(3600)
  private val SecondsPerDay = SecondsPerHour * BigDecimal(24)
  private val SecondsPer30DayMonth = SecondsPerDay * BigDecimal(30)

  final case class RateSet(
    readCapacityUnitPrice: BigDecimal,
    writeCapacityUnitPrice: BigDecimal,
    replicatedWriteCapacityUnitPrice: BigDecimal = BigDecimal("0.000000975"),
    storagePricePerGiBSecond: BigDecimal
  )

  /**
   * AWS-calibrated Standard table class defaults.
   * Storage pricing uses GiB-seconds: one simulation tick = one second, 1024^3 bytes = one GiB.
   */
  val awsDefaultStandard: RateSet = RateSet(
    readCapacityUnitPrice    = BigDecimal("0.00000025"),
    writeCapacityUnitPrice   = BigDecimal("0.00000125"),
    storagePricePerGiBSecond = BigDecimal("0.25") / SecondsPer30DayMonth
  )

  // Standard-IA: higher storage rate, lower throughput rate (per Phase 5 roadmap spec)
  val awsDefaultStandardIa: RateSet = RateSet(
    readCapacityUnitPrice            = BigDecimal("0.000000125"),
    writeCapacityUnitPrice           = BigDecimal("0.000000625"),
    replicatedWriteCapacityUnitPrice = BigDecimal("0.0000004875"),
    storagePricePerGiBSecond         = BigDecimal("0.50") / SecondsPer30DayMonth
  )

  val phase1Default: DynamoDbPricingRates =
    DynamoDbPricingRates(
      standard                 = awsDefaultStandard,
      standardInfrequentAccess = awsDefaultStandardIa
    )

final case class DynamoDbPricingRates(
  standard: DynamoDbPricingRates.RateSet,
  standardInfrequentAccess: DynamoDbPricingRates.RateSet = DynamoDbPricingRates.awsDefaultStandardIa
):
  def forClass(tc: DynamoDbTable.TableClass): DynamoDbPricingRates.RateSet = tc match
    case DynamoDbTable.TableClass.Standard                 => standard
    case DynamoDbTable.TableClass.StandardInfrequentAccess => standardInfrequentAccess

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
             rates: DynamoDbPricingRates = DynamoDbPricingRates.phase1Default,
             tableClass: DynamoDbTable.TableClass = DynamoDbTable.TableClass.Standard
           ): DynamoDbCostBreakdown =
    val r = rates.forClass(tableClass)

    val (readCapacityCost, writeCapacityCost) = inputs.provisionedCapacity match
      case Some(pc) =>
        (BigDecimal(pc.totalProvisionedReadCapacityUnitTicks)  * r.readCapacityUnitPrice  / BigDecimal(3600),
         BigDecimal(pc.totalProvisionedWriteCapacityUnitTicks) * r.writeCapacityUnitPrice / BigDecimal(3600))
      case None =>
        (inputs.usage.overall.readCapacityUnits  * r.readCapacityUnitPrice,
         inputs.usage.overall.writeCapacityUnits * r.writeCapacityUnitPrice)

    val replicatedWriteCapacityCost =
      inputs.usage.overall.replicatedWriteCapacityUnits * r.replicatedWriteCapacityUnitPrice

    val storageCost =
      BigDecimal(inputs.timeBasedUsage.overallStorageByteTicks) *
        r.storagePricePerGiBSecond / BytesPerGiB

    DynamoDbCostBreakdown(
      readCapacityCost = readCapacityCost,
      writeCapacityCost = writeCapacityCost,
      replicatedWriteCapacityCost = replicatedWriteCapacityCost,
      storageCost = storageCost,
      totalCost = readCapacityCost + writeCapacityCost + replicatedWriteCapacityCost + storageCost
    )
