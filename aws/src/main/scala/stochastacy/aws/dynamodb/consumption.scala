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

/** A throttle marker (zero capacity): a provisioned request rejected because its demand would exceed the
 *  per-tick provisioned capacity of `target` (the first over-budget target). Counted downstream; it moves
 *  no capacity or storage. */
final case class RequestThrottled(target: DynamoDbTarget) extends DynamoDbConsumption

/** The reserved provisioned capacity in force for a tick — emitted at the tick boundary by an auto-scaling
 *  table (whose capacity is chosen at runtime, not from a static schedule) so the accounting can bill the
 *  actual per-tick capacity trace. A metric-plane marker on the base table; it moves no capacity or storage. */
final case class ProvisionedCapacitySnapshot(readCapacityUnits: Long, writeCapacityUnits: Long) extends DynamoDbConsumption:
  def target: DynamoDbTarget = DynamoDbTarget.Table
