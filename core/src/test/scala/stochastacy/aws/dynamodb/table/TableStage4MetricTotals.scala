package stochastacy.aws.dynamodb.table

final case class Stage4MetricTotals(
                                     observedGets: Long = 0,
                                     returnedItems: Long = 0,
                                     returnedBytes: Long = 0,
                                     observedPuts: Long = 0,
                                     storedPuts: Long = 0,
                                     storedBytes: Long = 0,
                                     createdItems: Long = 0,
                                     itemCountDelta: Long = 0,
                                     tableBytesDelta: Long = 0
                                   )

object Stage4MetricTotals:
  def accumulate(
                  acc: Stage4MetricTotals,
                  evt: Stage4MetricEvent
                ): Stage4MetricTotals =
    evt match
      case Stage4MetricEvent.GetItemObserved(_, _) =>
        acc.copy(observedGets = acc.observedGets + 1)

      case Stage4MetricEvent.GetItemReturned(_, _, bytes) =>
        acc.copy(
          returnedItems = acc.returnedItems + 1,
          returnedBytes = acc.returnedBytes + bytes
        )

      case Stage4MetricEvent.PutItemObserved(_, _) =>
        acc.copy(observedPuts = acc.observedPuts + 1)

      case Stage4MetricEvent.PutItemStored(_, _, bytes, createdNewItem) =>
        acc.copy(
          storedPuts = acc.storedPuts + 1,
          storedBytes = acc.storedBytes + bytes,
          createdItems = acc.createdItems + (if createdNewItem then 1L else 0L)
        )

      case Stage4MetricEvent.TableItemCountChanged(_, _, delta) =>
        acc.copy(itemCountDelta = acc.itemCountDelta + delta)

      case Stage4MetricEvent.TableBytesChanged(_, _, delta) =>
        acc.copy(tableBytesDelta = acc.tableBytesDelta + delta)
