package stochastacy.aws.dynamodb.table

trait GetItemSample:
  /**  */
  def getItemBytes: Long

final case class QuerySample(
                              evaluatedItemCount: Long,
                              evaluatedBytes: Long,
                              returnedItemCount: Long,
                              returnedBytes: Long
                            ):
  require(evaluatedItemCount >= 0L, s"evaluatedItemCount must be non-negative, got $evaluatedItemCount")
  require(evaluatedBytes >= 0L, s"evaluatedBytes must be non-negative, got $evaluatedBytes")
  require(returnedItemCount >= 0L, s"returnedItemCount must be non-negative, got $returnedItemCount")
  require(returnedBytes >= 0L, s"returnedBytes must be non-negative, got $returnedBytes")
  require(
    returnedItemCount <= evaluatedItemCount,
    s"returnedItemCount ($returnedItemCount) must be <= evaluatedItemCount ($evaluatedItemCount)"
  )
  require(
    returnedBytes <= evaluatedBytes,
    s"returnedBytes ($returnedBytes) must be <= evaluatedBytes ($evaluatedBytes)"
  )

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
