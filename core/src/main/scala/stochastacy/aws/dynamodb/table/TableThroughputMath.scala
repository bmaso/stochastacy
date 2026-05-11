package stochastacy.aws.dynamodb.table

private[table] object TableThroughputMath:
  private val BytesPerReadCapacityUnitChunk = 4096L
  private val BytesPerWriteCapacityUnitChunk = 1024L

  def readCapacityUnitsFor(itemBytes: Option[Long], consistency: ReadConsistency): BigDecimal =
    val readCapacityUnitMultiplier = consistency match
      case ReadConsistency.EventuallyConsistent => BigDecimal("0.5")
      case ReadConsistency.StronglyConsistent => BigDecimal(1)
    val chunkCount = itemBytes match
      case Some(bytes) if bytes > 0 =>
        ((bytes - 1L) / BytesPerReadCapacityUnitChunk) + 1L
      case _ =>
        1L
    BigDecimal(chunkCount) * readCapacityUnitMultiplier

  def writeCapacityUnitsFor(itemBytes: Long): BigDecimal =
    val chunkCount =
      if itemBytes > 0 then ((itemBytes - 1L) / BytesPerWriteCapacityUnitChunk) + 1L
      else 1L
    BigDecimal(chunkCount)

  // Transactional writes cost 2× WCU per item (DynamoDB pricing)
  def transactionalWriteCapacityUnitsFor(itemBytes: Long): BigDecimal =
    writeCapacityUnitsFor(itemBytes) * 2

  // Transactional reads are always strongly consistent and cost 2× RCU per item
  def transactionalReadCapacityUnitsFor(itemBytes: Option[Long]): BigDecimal =
    readCapacityUnitsFor(itemBytes, ReadConsistency.StronglyConsistent) * 2
