package stochastacy.aws.dynamodb

/**
 * A table's per-tick provisioned-capacity accounting — the reusable **weighted** throttle accumulator: the
 * read/write capacity already admitted this tick, **per budget target**. Budget targets are the base table
 * (key [[ThrottleBudget.BaseKey]], which also absorbs LSI maintenance — LSIs share the base's throughput)
 * and each GSI (keyed by its index name), each with its own provisioned ceiling.
 *
 * A request's demand is weighted by its actual computed capacity (grouped from its consumption facts), and
 * a request throttles when **any** target's admitted-plus-demand would exceed that target's ceiling **plus
 * its banked burst capacity**. `readBank` / `writeBank` hold that bank per target — unused capacity carried
 * forward from earlier ticks (DynamoDB burst capacity). With no burst configured the banks stay empty and
 * the accumulator is reset to empty at each tick boundary, exactly as before.
 */
final case class ThrottleBudget(
  read:      Map[String, BigDecimal] = Map.empty,
  write:     Map[String, BigDecimal] = Map.empty,
  readBank:  Map[String, BigDecimal] = Map.empty,
  writeBank: Map[String, BigDecimal] = Map.empty
):
  /** Would admitting `readDemand` / `writeDemand` push any target past its provisioned ceiling *plus its
   *  banked burst capacity*? */
  def overBudget(
    readDemand:  Map[String, BigDecimal],
    writeDemand: Map[String, BigDecimal],
    provisioned: BillingMode.Provisioned
  ): Boolean =
    def exceeds(consumed: Map[String, BigDecimal], demand: Map[String, BigDecimal], ceiling: String => Long, bank: Map[String, BigDecimal]): Boolean =
      demand.exists { (key, d) => consumed.getOrElse(key, BigDecimal(0)) + d > BigDecimal(ceiling(key)) + bank.getOrElse(key, BigDecimal(0)) }
    exceeds(read, readDemand, readCeiling(provisioned), readBank) || exceeds(write, writeDemand, writeCeiling(provisioned), writeBank)

  /** Charge the (admitted) demand against the per-tick tallies. Preserves the banks (a `copy`, not a fresh
   *  construction) — the bank is drained at the tick boundary in [[rollForward]], not mid-tick. */
  def add(readDemand: Map[String, BigDecimal], writeDemand: Map[String, BigDecimal]): ThrottleBudget =
    copy(read = bump(read, readDemand), write = bump(write, writeDemand))

  /**
   * The tick-boundary transition **with burst capacity**: bank each target's unused capacity
   * (`ceiling − admitted`, which is negative when a tick spent bank) into `[0, ceiling × burstWindowTicks]`,
   * and clear the per-tick admitted tallies. `gsiNames` supplies the non-base targets so an *idle* GSI still
   * banks its full ceiling. Uses the just-completed tick's `provisioned` ceilings.
   */
  def rollForward(provisioned: BillingMode.Provisioned, gsiNames: Iterable[String], burstWindowTicks: Int): ThrottleBudget =
    val keys = ThrottleBudget.BaseKey +: gsiNames.toVector
    def rolled(admitted: Map[String, BigDecimal], bank: Map[String, BigDecimal], ceiling: String => Long): Map[String, BigDecimal] =
      keys.map { key =>
        val cap     = BigDecimal(ceiling(key)) * burstWindowTicks
        val updated = (bank.getOrElse(key, BigDecimal(0)) + BigDecimal(ceiling(key)) - admitted.getOrElse(key, BigDecimal(0))).max(0).min(cap)
        key -> updated
      }.toMap
    ThrottleBudget(readBank = rolled(read, readBank, readCeiling(provisioned)), writeBank = rolled(write, writeBank, writeCeiling(provisioned)))

  private def bump(into: Map[String, BigDecimal], demand: Map[String, BigDecimal]): Map[String, BigDecimal] =
    demand.foldLeft(into) { case (acc, (key, d)) => acc.updated(key, acc.getOrElse(key, BigDecimal(0)) + d) }

  private def readCeiling(p: BillingMode.Provisioned)(key: String): Long =
    if key == ThrottleBudget.BaseKey then p.readCapacityUnits else p.gsiRead(key)
  private def writeCeiling(p: BillingMode.Provisioned)(key: String): Long =
    if key == ThrottleBudget.BaseKey then p.writeCapacityUnits else p.gsiWrite(key)

object ThrottleBudget:
  val BaseKey = "base"
  val empty   = ThrottleBudget()

  /** The provisioned budget a target charges against: the base table and its LSIs share the base budget;
   *  each GSI has its own (keyed by index name). */
  def budgetKey(target: DynamoDbTarget): String = target match
    case DynamoDbTarget.Table    => BaseKey
    case DynamoDbTarget.Lsi(_)   => BaseKey
    case DynamoDbTarget.Gsi(name) => name
