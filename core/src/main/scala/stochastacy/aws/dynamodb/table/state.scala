package stochastacy.aws.dynamodb.table

trait TableState:
  def itemCount: Long
  def totalItemBytes: Long
  def recordSuccessfulPut(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit

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

  override def recordSuccessfulPut(writtenItemBytes: Long, previousItemBytes: Option[Long]): Unit =
    previousItemBytes match
      case Some(prevBytes) =>
        currentTotalItemBytes = currentTotalItemBytes - prevBytes + writtenItemBytes

      case None =>
        currentItemCount = currentItemCount + 1L
        currentTotalItemBytes = currentTotalItemBytes + writtenItemBytes

object SummaryTableState:
  def apply(
             initialItemCount: Long,
             initialTotalItemBytes: Long
           ): SummaryTableState =
    new SummaryTableState(initialItemCount, initialTotalItemBytes)
