package stochastacy.aws.dynamodb

/**
 * A table's billing mode — **intrinsic table config**, like its indexes: it changes how the table bills
 * and (once throttling lands) whether it rejects over-capacity requests.
 *
 *   - [[BillingMode.OnDemand]] — pay per consumed capacity unit; no throughput ceiling.
 *   - [[BillingMode.Provisioned]] — reserve a fixed RCU/WCU capacity, billed per capacity-hour
 *     (consumption-independent). Base-table and each GSI carry their **own** provisioned capacity (a GSI
 *     falls back to the base value when unset); LSIs share the base table's throughput.
 */
sealed trait BillingMode

object BillingMode:

  case object OnDemand extends BillingMode

  final case class Provisioned(
    readCapacityUnits:     Long,
    writeCapacityUnits:    Long,
    gsiReadCapacityUnits:  Map[String, Long] = Map.empty,
    gsiWriteCapacityUnits: Map[String, Long] = Map.empty
  ) extends BillingMode:
    require(readCapacityUnits > 0L,  "readCapacityUnits must be positive")
    require(writeCapacityUnits > 0L, "writeCapacityUnits must be positive")
    require(gsiReadCapacityUnits.values.forall(_ > 0L),  "per-GSI readCapacityUnits must be positive")
    require(gsiWriteCapacityUnits.values.forall(_ > 0L), "per-GSI writeCapacityUnits must be positive")

    /** A GSI's provisioned read/write **throttle ceiling**, falling back to the base table's value when the
     *  GSI's capacity is not separately provisioned (a GSI without its own capacity is limited by the base). */
    def gsiRead(indexName: String):  Long = gsiReadCapacityUnits.getOrElse(indexName, readCapacityUnits)
    def gsiWrite(indexName: String): Long = gsiWriteCapacityUnits.getOrElse(indexName, writeCapacityUnits)

    /** Total **reserved** (billed) read/write capacity: the base table plus only the GSIs that are
     *  **explicitly provisioned** (you pay for GSI capacity you actually provision; an unspecified GSI
     *  reserves nothing of its own, though it is still throttle-limited by the base — see `gsiRead`).
     *  LSIs share the base's throughput. */
    def totalReadCapacity:  Long = readCapacityUnits  + gsiReadCapacityUnits.values.sum
    def totalWriteCapacity: Long = writeCapacityUnits + gsiWriteCapacityUnits.values.sum
