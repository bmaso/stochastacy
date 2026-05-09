package stochastacy.aws.dynamodb.pricing

import stochastacy.aws.dynamodb.table.DynamoDbTable
import stochastacy.aws.dynamodb.usage.{DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}

final case class ReservedCapacity(
  reservedReadCapacityUnits: Long,
  reservedWriteCapacityUnits: Long,
  discountedReadCapacityUnitPrice: BigDecimal,
  discountedWriteCapacityUnitPrice: BigDecimal
):
  require(reservedReadCapacityUnits > 0L, "reservedReadCapacityUnits must be positive")
  require(reservedWriteCapacityUnits > 0L, "reservedWriteCapacityUnits must be positive")
  require(discountedReadCapacityUnitPrice >= 0, "discountedReadCapacityUnitPrice must be non-negative")
  require(discountedWriteCapacityUnitPrice >= 0, "discountedWriteCapacityUnitPrice must be non-negative")

final case class ProvisionedCapacityData(
  totalProvisionedReadCapacityUnitTicks: BigInt,
  totalProvisionedWriteCapacityUnitTicks: BigInt,
  discountedReadCapacityUnitTicks: BigInt = BigInt(0),
  standardReadCapacityUnitTicks: BigInt = BigInt(0),
  discountedWriteCapacityUnitTicks: BigInt = BigInt(0),
  standardWriteCapacityUnitTicks: BigInt = BigInt(0)
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
    storagePricePerGiBSecond: BigDecimal,
    provisionedReadCapacityUnitHourlyPrice: BigDecimal  = BigDecimal("0.00013"),
    provisionedWriteCapacityUnitHourlyPrice: BigDecimal = BigDecimal("0.00065"),
    pitrStoragePricePerGiBSecond: BigDecimal = BigDecimal("0.20") / (BigDecimal(3600) * BigDecimal(24) * BigDecimal(30))
  )

  /**
   * AWS-calibrated Standard table class defaults.
   * Storage pricing uses GiB-seconds: one simulation tick = one second, 1024^3 bytes = one GiB.
   * Provisioned hourly prices are charged per capacity-unit per hour, independent of consumption.
   */
  val awsDefaultStandard: RateSet = RateSet(
    readCapacityUnitPrice    = BigDecimal("0.00000025"),
    writeCapacityUnitPrice   = BigDecimal("0.00000125"),
    storagePricePerGiBSecond = BigDecimal("0.25") / SecondsPer30DayMonth
  )

  // Standard-IA: higher storage rate, lower throughput rate (per Phase 5 roadmap spec)
  val awsDefaultStandardIa: RateSet = RateSet(
    readCapacityUnitPrice                   = BigDecimal("0.000000125"),
    writeCapacityUnitPrice                  = BigDecimal("0.000000625"),
    replicatedWriteCapacityUnitPrice        = BigDecimal("0.0000004875"),
    storagePricePerGiBSecond                = BigDecimal("0.50") / SecondsPer30DayMonth,
    provisionedReadCapacityUnitHourlyPrice  = BigDecimal("0.000065"),
    provisionedWriteCapacityUnitHourlyPrice = BigDecimal("0.000325")
  )

  val phase1Default: DynamoDbPricingRates =
    DynamoDbPricingRates(
      standard                 = awsDefaultStandard,
      standardInfrequentAccess = awsDefaultStandardIa
    )

final case class DynamoDbPricingRates(
  standard: DynamoDbPricingRates.RateSet,
  standardInfrequentAccess: DynamoDbPricingRates.RateSet = DynamoDbPricingRates.awsDefaultStandardIa,
  reservedCapacity: Option[ReservedCapacity] = None
):
  def forClass(tc: DynamoDbTable.TableClass): DynamoDbPricingRates.RateSet = tc match
    case DynamoDbTable.TableClass.Standard                 => standard
    case DynamoDbTable.TableClass.StandardInfrequentAccess => standardInfrequentAccess

final case class DynamoDbCostBreakdown(
                                        readCapacityCost: BigDecimal,
                                        writeCapacityCost: BigDecimal,
                                        replicatedWriteCapacityCost: BigDecimal = BigDecimal(0),
                                        storageCost: BigDecimal,
                                        pitrCost: BigDecimal = BigDecimal(0),
                                        totalCost: BigDecimal
                                      )

object DynamoDbCostBreakdown:
  private val BytesPerGiB = BigDecimal(1024).pow(3)

  def price(
             inputs: DynamoDbPricingInputs,
             rates: DynamoDbPricingRates = DynamoDbPricingRates.phase1Default,
             tableClass: DynamoDbTable.TableClass = DynamoDbTable.TableClass.Standard
           ): DynamoDbCostBreakdown =
    require(
      rates.reservedCapacity.isEmpty || tableClass == DynamoDbTable.TableClass.Standard,
      "Reserved capacity is unavailable for Standard-IA tables"
    )
    require(
      rates.reservedCapacity.isEmpty || inputs.provisionedCapacity.isDefined,
      "Reserved capacity requires provisioned billing mode; no provisionedCapacity data was supplied"
    )

    val r = rates.forClass(tableClass)

    val onDemandReadCost  = inputs.usage.overall.readCapacityUnits  * r.readCapacityUnitPrice
    val onDemandWriteCost = inputs.usage.overall.writeCapacityUnits * r.writeCapacityUnitPrice

    val (readCapacityCost, writeCapacityCost) = inputs.provisionedCapacity match
      case Some(pc) =>
        rates.reservedCapacity match
          case Some(rc) =>
            val provReadCost =
              BigDecimal(pc.discountedReadCapacityUnitTicks) * rc.discountedReadCapacityUnitPrice        / BigDecimal(3600) +
              BigDecimal(pc.standardReadCapacityUnitTicks)   * r.provisionedReadCapacityUnitHourlyPrice  / BigDecimal(3600)
            val provWriteCost =
              BigDecimal(pc.discountedWriteCapacityUnitTicks) * rc.discountedWriteCapacityUnitPrice       / BigDecimal(3600) +
              BigDecimal(pc.standardWriteCapacityUnitTicks)   * r.provisionedWriteCapacityUnitHourlyPrice / BigDecimal(3600)
            (onDemandReadCost + provReadCost, onDemandWriteCost + provWriteCost)
          case None =>
            val provReadCost  = BigDecimal(pc.totalProvisionedReadCapacityUnitTicks)  * r.provisionedReadCapacityUnitHourlyPrice  / BigDecimal(3600)
            val provWriteCost = BigDecimal(pc.totalProvisionedWriteCapacityUnitTicks) * r.provisionedWriteCapacityUnitHourlyPrice / BigDecimal(3600)
            (onDemandReadCost + provReadCost, onDemandWriteCost + provWriteCost)
      case None =>
        (onDemandReadCost, onDemandWriteCost)

    val replicatedWriteCapacityCost =
      inputs.usage.overall.replicatedWriteCapacityUnits * r.replicatedWriteCapacityUnitPrice

    val storageCost =
      BigDecimal(inputs.timeBasedUsage.overallStorageByteTicks) *
        r.storagePricePerGiBSecond / BytesPerGiB

    val pitrCost =
      BigDecimal(inputs.timeBasedUsage.pitrStorageByteTicks) *
        r.pitrStoragePricePerGiBSecond / BytesPerGiB

    DynamoDbCostBreakdown(
      readCapacityCost = readCapacityCost,
      writeCapacityCost = writeCapacityCost,
      replicatedWriteCapacityCost = replicatedWriteCapacityCost,
      storageCost = storageCost,
      pitrCost = pitrCost,
      totalCost = readCapacityCost + writeCapacityCost + replicatedWriteCapacityCost + storageCost + pitrCost
    )
