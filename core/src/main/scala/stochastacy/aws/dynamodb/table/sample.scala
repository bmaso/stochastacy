package stochastacy.aws.dynamodb.table

trait GetItemSample:
  /**  */
  def getItemBytes: Long

trait WriteItemSample:
  def writtenItemBytes: Long
  def previousItemBytes: Option[Long]

  def createdNewItem: Boolean = previousItemBytes.isEmpty

  def storageBytesDelta: Long = writtenItemBytes - previousItemBytes.getOrElse(0L)

  def itemCountDelta: Long = if createdNewItem then 1L else 0L

trait PutItemSample extends WriteItemSample

trait UpdateItemSample extends WriteItemSample

trait DeleteItemSample:
  def deletedItemBytes: Option[Long]

  def deletedExistingItem: Boolean = deletedItemBytes.isDefined

  def storageBytesDelta: Long = -deletedItemBytes.getOrElse(0L)

  def itemCountDelta: Long = if deletedExistingItem then -1L else 0L
