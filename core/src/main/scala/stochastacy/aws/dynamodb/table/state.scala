package stochastacy.aws.dynamodb.table

trait TableState:
  def itemCount: Long
  def totalItemBytes: Long
  def recordSuccessfulWrite(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit
  def recordSuccessfulDelete(deletedItemBytes: Option[Long]): Unit
  def recordTtlExpiry(expiredItemCount: Long, freedBytes: Long): Unit

  def recordSuccessfulPut(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit =
    recordSuccessfulWrite(writtenItemBytes, previousItemBytes)

  def recordSuccessfulUpdate(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit =
    recordSuccessfulWrite(writtenItemBytes, previousItemBytes)

  def averageItemBytes: Option[Long] =
    if itemCount > 0 then Some(totalItemBytes / itemCount)
    else None

class SummaryTableState(
                         initialItemCount: Long,
                         initialTotalItemBytes: Long
                       ) extends TableState:
  private var currentItemCount = initialItemCount
  private var currentTotalItemBytes = initialTotalItemBytes

  override def itemCount: Long = currentItemCount

  override def totalItemBytes: Long = currentTotalItemBytes

  override def recordSuccessfulWrite(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit =
    previousItemBytes match
      case Some(prevBytes) =>
        currentTotalItemBytes = currentTotalItemBytes - prevBytes + writtenItemBytes

      case None =>
        currentItemCount = currentItemCount + 1L
        currentTotalItemBytes = currentTotalItemBytes + writtenItemBytes

  override def recordSuccessfulDelete(deletedItemBytes: Option[Long]): Unit =
    deletedItemBytes.foreach { bytes =>
      currentItemCount = currentItemCount - 1L
      currentTotalItemBytes = currentTotalItemBytes - bytes
    }

  override def recordTtlExpiry(expiredItemCount: Long, freedBytes: Long): Unit =
    currentItemCount = math.max(0L, currentItemCount - expiredItemCount)
    currentTotalItemBytes = math.max(0L, currentTotalItemBytes - freedBytes)

object SummaryTableState:
  def apply(
             initialItemCount: Long,
             initialTotalItemBytes: Long
           ): SummaryTableState =
    new SummaryTableState(initialItemCount, initialTotalItemBytes)
