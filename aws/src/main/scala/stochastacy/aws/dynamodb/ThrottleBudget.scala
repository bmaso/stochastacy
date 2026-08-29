package stochastacy.aws.dynamodb

/**
 * A table's per-tick provisioned-capacity accounting — the reusable **weighted** throttle accumulator: the
 * read/write capacity already admitted this tick, **per budget target**. Budget targets are the base table
 * (key [[ThrottleBudget.BaseKey]], which also absorbs LSI maintenance — LSIs share the base's throughput)
 * and each GSI (keyed by its index name), each with its own provisioned ceiling.
 *
 * A request's demand is weighted by its actual computed capacity (grouped from its consumption facts), and
 * a request throttles when **any** target's admitted-plus-demand would exceed that target's ceiling. Reset
 * to empty at each tick boundary.
 */
final case class ThrottleBudget(
  read:  Map[String, BigDecimal] = Map.empty,
  write: Map[String, BigDecimal] = Map.empty
):
  /** Would admitting `readDemand` / `writeDemand` push any target past its provisioned ceiling? */
  def overBudget(
    readDemand:  Map[String, BigDecimal],
    writeDemand: Map[String, BigDecimal],
    provisioned: BillingMode.Provisioned
  ): Boolean =
    def exceeds(consumed: Map[String, BigDecimal], demand: Map[String, BigDecimal], ceiling: String => Long): Boolean =
      demand.exists { (key, d) => consumed.getOrElse(key, BigDecimal(0)) + d > BigDecimal(ceiling(key)) }
    exceeds(read, readDemand, readCeiling(provisioned)) || exceeds(write, writeDemand, writeCeiling(provisioned))

  /** Charge the (admitted) demand against the per-tick tallies. */
  def add(readDemand: Map[String, BigDecimal], writeDemand: Map[String, BigDecimal]): ThrottleBudget =
    ThrottleBudget(bump(read, readDemand), bump(write, writeDemand))

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
