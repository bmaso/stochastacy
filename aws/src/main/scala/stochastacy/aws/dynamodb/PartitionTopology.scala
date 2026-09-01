package stochastacy.aws.dynamodb

/**
 * The physical-partition topology of a table: how DynamoDB splits a table's provisioned capacity across
 * partitions, each bounded by a physical throughput/storage limit. Provisioned capacity is shared evenly, so
 * a partition's fair-share ceiling is `capacity / partitionCount`; load concentrated on one partition throttles
 * against that ceiling even while the table has aggregate spare — the "hot partition" effect.
 *
 * The count is **derived** from the table's capacity and storage (the greater of the two), an evolving topology
 * that grows as capacity or storage grows — matching real DynamoDB, and a refinement over the legacy simulator
 * (which took a fixed configured partition count). Keys are hashed to a partition, so the per-partition state
 * is bounded by the partition count, never the key space.
 */
object PartitionTopology:
  /** Physical per-partition limits: ~3000 RCU / 1000 WCU / 10 GiB per partition. */
  val RcuPerPartition:   Long = 3000L
  val WcuPerPartition:   Long = 1000L
  val BytesPerPartition: Long = 10L * 1024L * 1024L * 1024L

  /** Partitions needed for `readCapacityUnits` / `writeCapacityUnits` / `storageBytes` — the greater of the
   *  capacity-based and storage-based counts, at least one. */
  def derive(readCapacityUnits: Long, writeCapacityUnits: Long, storageBytes: Long): Int =
    val capacityBased = math.ceil(readCapacityUnits.toDouble / RcuPerPartition + writeCapacityUnits.toDouble / WcuPerPartition).toInt
    val storageBased  = math.ceil(storageBytes.toDouble / BytesPerPartition).toInt
    math.max(1, math.max(capacityBased, storageBased))

  /** The physical partition a key token maps to (a stable hash into `[0, partitionCount)`). */
  def partitionOf(keyToken: String, partitionCount: Int): Int =
    require(partitionCount > 0, s"partitionCount must be positive, got $partitionCount")
    math.floorMod(keyToken.hashCode, partitionCount)
