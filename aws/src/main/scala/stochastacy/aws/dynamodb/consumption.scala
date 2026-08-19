package stochastacy.aws.dynamodb

/** Read-consistency mode for a read operation — sets the RCU multiplier (strong ×1, eventual ×0.5). */
enum ReadConsistency:
  case EventuallyConsistent
  case StronglyConsistent

/**
 * The consumption facts a table emits per operation — the "metric plane" folded downstream into usage
 * totals and, ultimately, cost: read/write capacity consumed, and the net change in stored bytes
 * (integrated over ticks into storage byte-ticks). Each fact is tagged with the `target` (the base table
 * or a secondary index) it was incurred on, so per-index usage can be broken out downstream.
 */
sealed trait DynamoDbConsumption:
  def target: DynamoDbTarget

/** Read capacity units consumed by a read, at the given consistency, on `target`. */
final case class ReadCapacityConsumed(units: BigDecimal, consistency: ReadConsistency, target: DynamoDbTarget) extends DynamoDbConsumption

/** Write capacity units consumed by a write (put / update / delete, or index maintenance) on `target`. */
final case class WriteCapacityConsumed(units: BigDecimal, target: DynamoDbTarget) extends DynamoDbConsumption

/** The signed change in stored bytes on `target` produced by a write or delete (positive grows storage). */
final case class StorageBytesDelta(bytesDelta: Long, target: DynamoDbTarget) extends DynamoDbConsumption
