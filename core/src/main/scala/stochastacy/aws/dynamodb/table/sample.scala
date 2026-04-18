package stochastacy.aws.dynamodb.table

trait GetItemSample:
  /**  */
  def getItemBytes: Long

trait PutItemSample:
  def writtenItemBytes: Long
  def previousItemBytes: Option[Long]

  def createdNewItem: Boolean = previousItemBytes.isEmpty

  def storageBytesDelta: Long = writtenItemBytes - previousItemBytes.getOrElse(0L)

  def itemCountDelta: Long = if createdNewItem then 1L else 0L
