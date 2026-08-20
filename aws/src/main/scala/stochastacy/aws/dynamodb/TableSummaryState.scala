package stochastacy.aws.dynamodb

/**
 * The stochastic-summary state of a table: an item count and a total-bytes figure, with the average item
 * size derived. This is the immutable v2 counterpart to the legacy mutable `SummaryTableState` — its
 * transitions return a new state rather than mutating vars, so it can be threaded functionally as a
 * `ComponentSampler` state (Slice 2). Transition semantics match the legacy recorder methods exactly.
 */
final case class TableSummaryState(itemCount: Long, totalItemBytes: Long):
  require(itemCount >= 0L,      s"TableSummaryState.itemCount must be non-negative, got $itemCount")
  require(totalItemBytes >= 0L, s"TableSummaryState.totalItemBytes must be non-negative, got $totalItemBytes")

  /** The mean item size, or `None` when the table is empty. */
  def averageItemBytes: Option[Long] =
    if itemCount > 0L then Some(totalItemBytes / itemCount) else None

  /**
   * Apply a successful write (put or update). `previousItemBytes = Some(prev)` means an existing item was
   * replaced in place (count unchanged, bytes adjusted by the difference); `None` means a new item was
   * inserted (count and bytes both grow).
   */
  def applyWrite(writtenItemBytes: Long, previousItemBytes: Option[Long]): TableSummaryState =
    previousItemBytes match
      case Some(prev) => copy(totalItemBytes = totalItemBytes - prev + writtenItemBytes)
      case None       => TableSummaryState(itemCount + 1L, totalItemBytes + writtenItemBytes)

  /**
   * Apply a successful delete. `Some(bytes)` removes an existing item (count and bytes both shrink);
   * `None` means no item was present — a no-op.
   */
  def applyDelete(deletedItemBytes: Option[Long]): TableSummaryState =
    deletedItemBytes match
      case Some(bytes) => TableSummaryState(itemCount - 1L, totalItemBytes - bytes)
      case None        => this

object TableSummaryState:
  /** An empty table. */
  val empty: TableSummaryState = TableSummaryState(0L, 0L)

  /** A table pre-loaded with `itemCount` items each averaging `averageItemBytes` bytes. */
  def initial(itemCount: Long, averageItemBytes: Long): TableSummaryState =
    TableSummaryState(itemCount, itemCount * averageItemBytes)

/**
 * The whole table's threaded state: the base table's summary plus one summary per secondary index (keyed
 * by index name), and the current tick (advanced at each tick boundary so a time-dependent behavior can
 * read it). This is the `DynamoDbTable` sampler's state and its materialized value; a table with no
 * indexes carries an empty `indexes` map and behaves exactly as the base summary alone.
 */
final case class TableState(base: TableSummaryState, indexes: Map[String, TableSummaryState], currentTick: Long = 0L):
  /** The summary of the index named `indexName` (empty if unknown). */
  def index(indexName: String): TableSummaryState = indexes.getOrElse(indexName, TableSummaryState.empty)

object TableState:
  /**
   * The initial whole-table state: the given base summary, with each secondary index seeded from the
   * base's pre-loaded items projected through the index — the entries a freshly-created index over an
   * existing table already holds.
   */
  def initial(base: TableSummaryState, indexes: Vector[SecondaryIndex]): TableState =
    val avgBytes = base.averageItemBytes.getOrElse(0L)
    val seeded = indexes.map { idx =>
      val perEntry = SecondaryIndexMechanics.projectedEntryBytes(Some(avgBytes), idx.projection).getOrElse(0L)
      idx.indexName -> TableSummaryState(base.itemCount, base.itemCount * perEntry)
    }.toMap
    TableState(base, seeded)
