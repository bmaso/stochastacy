package stochastacy.aws.examples.ordertracking

/** On-demand DynamoDB rates (a demo-local, Phase-1-scoped pricing model — no provisioned / reserved /
 *  replicated / PITR pricing). Storage is priced per GiB-second with one tick treated as one second. */
final case class Rates(
  rcuPrice:                 BigDecimal,
  wcuPrice:                 BigDecimal,
  storagePricePerGiBSecond: BigDecimal
)

object OnDemandPricing:
  private val SecondsPer30DayMonth = BigDecimal(3600) * BigDecimal(24) * BigDecimal(30)
  private val BytesPerGiB          = BigDecimal(1024).pow(3)

  /** AWS-calibrated Standard on-demand rates (the values the legacy `phase1Default` uses). */
  val phase1Default: Rates = Rates(
    rcuPrice                 = BigDecimal("0.00000025"),
    wcuPrice                 = BigDecimal("0.00000125"),
    storagePricePerGiBSecond = BigDecimal("0.25") / SecondsPer30DayMonth
  )

  /** On-demand cost: capacity units at their unit prices plus stored GiB-seconds (byte-ticks / 1024³). */
  def cost(totalRcu: BigDecimal, totalWcu: BigDecimal, storageByteTicks: BigInt, rates: Rates): BigDecimal =
    totalRcu * rates.rcuPrice +
      totalWcu * rates.wcuPrice +
      BigDecimal(storageByteTicks) * rates.storagePricePerGiBSecond / BytesPerGiB
