package stochastacy.aws.dynamodb

/**
 * The deterministic mechanics of maintaining one secondary index against a base-table write — the
 * sibling of [[TableMechanics]], over the index's own [[TableSummaryState]]. Given the base write's new
 * and previous item sizes, it projects them to index-entry sizes, decides whether the entry is inserted /
 * replaced / deleted / unchanged, and produces the index's write-capacity + storage-delta consumption
 * (tagged with the index target) and its next state.
 *
 * rng-free, like `TableMechanics`: the base write's outcome (already drawn by the behavior) is the only
 * input. GSI and LSI use identical math; their only difference — async vs. synchronous timing — is
 * applied by the caller via [[SecondaryIndex.maintenanceDelay]], not here.
 */
object SecondaryIndexMechanics:

  /** The size an index key occupies — the floor an entry's projected size cannot go below. */
  val IndexKeyBytesPerEntry: Long = 128L

  /** The index-entry size for a base item of `itemBytes`, under `projection`. */
  def projectedEntryBytes(itemBytes: Option[Long], projection: IndexProjection): Option[Long] =
    itemBytes.map { bytes =>
      projection match
        case IndexProjection.All             => bytes
        case IndexProjection.KeysOnly        => bytes.min(IndexKeyBytesPerEntry)
        case IndexProjection.Include(nonKey) => bytes.min(IndexKeyBytesPerEntry + nonKey)
    }

  /** The maintenance an index incurs from one base write: its consumption facts and its next state. */
  final case class Maintenance(consumption: List[DynamoDbConsumption], state: TableSummaryState)

  /**
   * Resolve `index`'s maintenance for a base write whose item was `newBaseItemBytes` afterward and
   * `previousBaseItemBytes` before (a put/upsert: new = Some, previous per the base outcome; a delete:
   * new = None, previous = the deleted size or None). WCU is charged on the entry actually written or
   * deleted; the storage delta is the change in the index's stored bytes.
   */
  def maintain(
    index:                 SecondaryIndex,
    newBaseItemBytes:      Option[Long],
    previousBaseItemBytes: Option[Long],
    indexState:            TableSummaryState,
    transactional:         Boolean          = false
  ): Maintenance =
    val newEntry  = projectedEntryBytes(newBaseItemBytes, index.projection)
    val prevEntry = projectedEntryBytes(previousBaseItemBytes, index.projection)

    val outcome: Option[(Long, TableSummaryState)] = // (wcu-charged bytes, next state)
      (newEntry, prevEntry) match
        case (Some(nb), None)                 => Some((nb, indexState.applyWrite(nb, None)))          // insert
        case (None, Some(pb))                 => Some((pb, indexState.applyDelete(Some(pb))))         // delete
        case (Some(nb), Some(pb)) if nb != pb => Some((nb, indexState.applyWrite(nb, Some(pb))))      // replace
        case _                                => None                                                // no-op

    outcome match
      case None =>
        Maintenance(Nil, indexState)
      case Some((wcuBytes, next)) =>
        // A transactional write bills its LSI maintenance 2× (synchronous, co-located) and its GSI
        // maintenance 1× (async post-commit); a normal write bills 1× everywhere.
        val multiplier = if transactional then ThroughputMath.transactionalWriteMultiplier(index.target) else 1
        val wcu   = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(wcuBytes) * multiplier, index.target)
        val delta = next.totalItemBytes - indexState.totalItemBytes
        val facts = if delta != 0L then List(wcu, StorageBytesDelta(delta, index.target)) else List(wcu)
        Maintenance(facts, next)
