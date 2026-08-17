package stochastacy.aws.dynamodb

/** Read-consistency mode for a read operation — sets the RCU multiplier (strong ×1, eventual ×0.5). */
enum ReadConsistency:
  case EventuallyConsistent
  case StronglyConsistent

/**
 * The consumption facts a table emits per operation — the "metric plane" folded downstream into usage
 * totals and, ultimately, cost. Phase-1's minimal set: read/write capacity consumed, and the net change
 * in stored bytes (integrated over ticks into storage byte-ticks). A single-table demo needs no target
 * dimension, so — unlike the legacy `DynamoDbConsumptionEvent` — these carry none.
 */
sealed trait DynamoDbConsumption

/** Read capacity units consumed by a read, at the given consistency. */
final case class ReadCapacityConsumed(units: BigDecimal, consistency: ReadConsistency) extends DynamoDbConsumption

/** Write capacity units consumed by a write (put / update / delete). */
final case class WriteCapacityConsumed(units: BigDecimal) extends DynamoDbConsumption

/** The signed change in total stored bytes produced by a write or delete (positive grows storage). */
final case class StorageBytesDelta(bytesDelta: Long) extends DynamoDbConsumption
