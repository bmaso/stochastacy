package stochastacy.aws.dynamodb

/**
 * DynamoDB capacity-unit arithmetic — a clean re-creation of the legacy `TableThroughputMath`.
 *
 *   - Reads round item size up to 4 KB chunks; strongly-consistent reads cost 1 RCU per chunk,
 *     eventually-consistent reads 0.5.
 *   - Writes round item size up to 1 KB chunks at 1 WCU per chunk.
 *
 * A zero-or-unknown size still costs the one-chunk minimum (so a read miss is 1 RCU, a delete of an
 * absent item 1 WCU).
 */
object ThroughputMath:
  private val BytesPerReadCapacityUnitChunk  = 4096L
  private val BytesPerWriteCapacityUnitChunk = 1024L

  /** RCU for a read of `itemBytes` (empty = a miss, charged the one-chunk minimum). */
  def readCapacityUnits(itemBytes: Option[Long], consistency: ReadConsistency): BigDecimal =
    val multiplier = consistency match
      case ReadConsistency.EventuallyConsistent => BigDecimal("0.5")
      case ReadConsistency.StronglyConsistent   => BigDecimal(1)
    BigDecimal(chunks(itemBytes.getOrElse(0L), BytesPerReadCapacityUnitChunk)) * multiplier

  /** WCU for a write of `itemBytes` (zero = the one-chunk minimum). */
  def writeCapacityUnits(itemBytes: Long): BigDecimal =
    BigDecimal(chunks(itemBytes, BytesPerWriteCapacityUnitChunk))

  private def chunks(bytes: Long, chunkBytes: Long): Long =
    if bytes > 0L then ((bytes - 1L) / chunkBytes) + 1L else 1L
