package stochastacy.aws.examples.demo

/** DynamoDB pricing rates. On-demand consumption is priced per capacity unit; **provisioned** capacity is
 *  priced per capacity-**hour** (a standing reservation, consumption-independent). Storage is priced per
 *  GiB-second, with one tick treated as one second. */
final case class Rates(
  rcuPrice:                     BigDecimal,
  wcuPrice:                     BigDecimal,
  storagePricePerGiBSecond:     BigDecimal,
  provisionedRcuHourlyPrice:    BigDecimal = BigDecimal("0.00013"),
  provisionedWcuHourlyPrice:    BigDecimal = BigDecimal("0.00065"),
  pitrStoragePricePerGiBSecond: BigDecimal = BigDecimal("0.20") / (BigDecimal(3600) * BigDecimal(24) * BigDecimal(30))
)

object Pricing:
  private val SecondsPerHour       = BigDecimal(3600)
  private val SecondsPer30DayMonth = BigDecimal(3600) * BigDecimal(24) * BigDecimal(30)
  private val BytesPerGiB          = BigDecimal(1024).pow(3)

  /** AWS-calibrated Standard rates (the values the legacy `phase1Default` uses): on-demand $0.25/M RCU and
   *  $1.25/M WCU consumed, provisioned $0.00013/RCU-hr and $0.00065/WCU-hr, storage $0.25/GiB-month. */
  val phase1Default: Rates = Rates(
    rcuPrice                  = BigDecimal("0.00000025"),
    wcuPrice                  = BigDecimal("0.00000125"),
    storagePricePerGiBSecond     = BigDecimal("0.25") / SecondsPer30DayMonth,
    provisionedRcuHourlyPrice    = BigDecimal("0.00013"),
    provisionedWcuHourlyPrice    = BigDecimal("0.00065"),
    pitrStoragePricePerGiBSecond = BigDecimal("0.20") / SecondsPer30DayMonth
  )

  /** Stored GiB-seconds (byte-ticks / 1024³) at the storage rate — billed the same under either mode. */
  def storageCost(storageByteTicks: BigInt, rates: Rates): BigDecimal =
    BigDecimal(storageByteTicks) * rates.storagePricePerGiBSecond / BytesPerGiB

  /** Point-In-Time Recovery continuous-backup cost: the table's stored GiB-seconds (base + indexes, the same
   *  byte-ticks as storage) at the PITR rate. */
  def pitrCost(storageByteTicks: BigInt, rates: Rates): BigDecimal =
    BigDecimal(storageByteTicks) * rates.pitrStoragePricePerGiBSecond / BytesPerGiB

  /** On-demand consumption cost: consumed capacity units at their unit prices. */
  def consumptionCost(rcu: BigDecimal, wcu: BigDecimal, rates: Rates): BigDecimal =
    rcu * rates.rcuPrice + wcu * rates.wcuPrice

  /** Provisioned reservation cost: capacity-ticks ÷ 3600 (= capacity-hours, one tick = one second) at the
   *  hourly rate — independent of how much of the reserved capacity was actually consumed. */
  def provisionedCost(readCapacityUnitTicks: BigInt, writeCapacityUnitTicks: BigInt, rates: Rates): BigDecimal =
    BigDecimal(readCapacityUnitTicks)  * rates.provisionedRcuHourlyPrice / SecondsPerHour +
      BigDecimal(writeCapacityUnitTicks) * rates.provisionedWcuHourlyPrice / SecondsPerHour

  /** On-demand total cost: consumed capacity + storage. (Retained convenience over the granular helpers.) */
  def cost(totalRcu: BigDecimal, totalWcu: BigDecimal, storageByteTicks: BigInt, rates: Rates): BigDecimal =
    consumptionCost(totalRcu, totalWcu, rates) + storageCost(storageByteTicks, rates)
