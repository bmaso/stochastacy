package stochastacy.aws.dynamodb

/**
 * How much of a base item a secondary index projects into its own entries — which sets the index's
 * per-entry size (and so its storage and maintenance write cost). A re-creation of the legacy
 * `IndexProjection`.
 */
enum IndexProjection:
  /** The whole item (an entry is the base item's size). */
  case All
  /** Only the key attributes — an entry is capped at [[SecondaryIndexMechanics.IndexKeyBytesPerEntry]]. */
  case KeysOnly
  /** The keys plus `projectedNonKeyBytesPerItem` of projected attributes. */
  case Include(projectedNonKeyBytesPerItem: Long)

/**
 * A secondary index declared **on** a table (never a graph-level component). A GSI is an independent
 * sub-store with its own entries, maintained asynchronously; an LSI shares the base partition and is
 * maintained synchronously. Both compute maintenance identically ([[SecondaryIndexMechanics]]); they
 * differ only in that timing.
 */
sealed trait SecondaryIndex:
  def indexName:  String
  def projection: IndexProjection
  def target:     DynamoDbTarget
  /** The propagation delay (fractional ticks) before this index's maintenance is observed. */
  def maintenanceDelay: Double

/** A global secondary index — maintained asynchronously after `propagationDelayTicks` (default 0, which
 *  matches the legacy's emit-at-write-time timing; raise it to model eventual-consistency lag). */
final case class GlobalSecondaryIndex(
  indexName:             String,
  projection:            IndexProjection = IndexProjection.All,
  propagationDelayTicks: Double          = 0.0
) extends SecondaryIndex:
  def target: DynamoDbTarget = DynamoDbTarget.Gsi(indexName)
  def maintenanceDelay: Double = propagationDelayTicks

/** A local secondary index — maintained synchronously with the base write (delay 0). */
final case class LocalSecondaryIndex(
  indexName:  String,
  projection: IndexProjection = IndexProjection.All
) extends SecondaryIndex:
  def target: DynamoDbTarget = DynamoDbTarget.Lsi(indexName)
  def maintenanceDelay: Double = 0.0
