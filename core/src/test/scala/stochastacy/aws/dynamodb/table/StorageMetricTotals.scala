package stochastacy.aws.dynamodb.table

final case class StorageMetricTotals(
                                     observedGets: Long = 0,
                                     returnedItems: Long = 0,
                                     returnedBytes: Long = 0,
                                     observedPuts: Long = 0,
                                     storedPuts: Long = 0,
                                     storedBytes: Long = 0,
                                     observedUpdates: Long = 0,
                                     storedUpdates: Long = 0,
                                     updatedBytes: Long = 0,
                                     observedDeletes: Long = 0,
                                     deletedItems: Long = 0,
                                     deletedBytes: Long = 0,
                                     createdItems: Long = 0,
                                     itemCountDelta: Long = 0,
                                     tableBytesDelta: Long = 0
                                   )

object StorageMetricTotals:
  def accumulate(
                  acc: StorageMetricTotals,
                  evt: StorageMetricEvent
                ): StorageMetricTotals =
    evt match
      case StorageMetricEvent.GetItemObserved(_, _) =>
        acc.copy(observedGets = acc.observedGets + 1)

      case StorageMetricEvent.GetItemReturned(_, _, bytes) =>
        acc.copy(
          returnedItems = acc.returnedItems + 1,
          returnedBytes = acc.returnedBytes + bytes
        )

      case StorageMetricEvent.PutItemObserved(_, _) =>
        acc.copy(observedPuts = acc.observedPuts + 1)

      case StorageMetricEvent.PutItemStored(_, _, bytes, createdNewItem) =>
        acc.copy(
          storedPuts = acc.storedPuts + 1,
          storedBytes = acc.storedBytes + bytes,
          createdItems = acc.createdItems + (if createdNewItem then 1L else 0L)
        )

      case StorageMetricEvent.UpdateItemObserved(_, _) =>
        acc.copy(observedUpdates = acc.observedUpdates + 1)

      case StorageMetricEvent.UpdateItemStored(_, _, bytes, createdNewItem) =>
        acc.copy(
          storedUpdates = acc.storedUpdates + 1,
          updatedBytes = acc.updatedBytes + bytes,
          createdItems = acc.createdItems + (if createdNewItem then 1L else 0L)
        )

      case StorageMetricEvent.DeleteItemObserved(_, _) =>
        acc.copy(observedDeletes = acc.observedDeletes + 1)

      case StorageMetricEvent.DeleteItemDeleted(_, _, bytes) =>
        acc.copy(
          deletedItems = acc.deletedItems + 1,
          deletedBytes = acc.deletedBytes + bytes
        )

      case StorageMetricEvent.TableItemCountChanged(_, _, delta) =>
        acc.copy(itemCountDelta = acc.itemCountDelta + delta)

      case StorageMetricEvent.TableBytesChanged(_, _, delta) =>
        acc.copy(tableBytesDelta = acc.tableBytesDelta + delta)

      case _: StorageMetricEvent.QueryObserved =>
        acc

      case _: StorageMetricEvent.QueryEvaluated =>
        acc

      case _: StorageMetricEvent.QueryReturned =>
        acc

      case _: StorageMetricEvent.QueryUsedIndexOnly =>
        acc

      case _: StorageMetricEvent.QueryFetchedFromBaseTable =>
        acc

      case _: StorageMetricEvent.IndexEntryInserted =>
        acc

      case _: StorageMetricEvent.IndexEntryReplaced =>
        acc

      case _: StorageMetricEvent.IndexEntryDeleted =>
        acc

      case _: StorageMetricEvent.IndexEntryUnchanged =>
        acc

      case _: StorageMetricEvent.ScanObserved =>
        acc

      case _: StorageMetricEvent.ScanEvaluated =>
        acc

      case _: StorageMetricEvent.ScanReturned =>
        acc

      case _: StorageMetricEvent.ScanUsedIndexOnly =>
        acc

      case _: StorageMetricEvent.ScanFetchedFromBaseTable =>
        acc

      case _: StorageMetricEvent.ReturnedItemCount =>
        acc

      case _: StorageMetricEvent.ItemCollectionSizeLimitExceeded =>
        acc

      case _: StorageMetricEvent.SystemError =>
        acc

      case _: StorageMetricEvent.SuccessfulRequestLatency =>
        acc

      case _: StorageMetricEvent.TtlItemsExpired =>
        acc

      case _: StorageMetricEvent.EstimatedItemCount =>
        acc
