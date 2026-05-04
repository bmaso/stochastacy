package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.{DynamoDbOperationKind, DynamoDbReadTarget}
import stochastacy.sim.SimTime

sealed trait StorageMetricEvent extends TableMetricEvent

object StorageMetricEvent:

  /** One GetItem request reached the data plane */
  final case class GetItemObserved(
                                    eventTime: SimTime,
                                    usecase: Any
                                  ) extends StorageMetricEvent

  /** A GetItem returned an item */
  final case class GetItemReturned(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    bytes: Long
                                  ) extends StorageMetricEvent

  /** One Query request reached the data plane */
  final case class QueryObserved(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget
                                ) extends StorageMetricEvent

  /** A Query evaluated data while executing */
  final case class QueryEvaluated(
                                   eventTime: SimTime,
                                   usecase: Any,
                                   target: DynamoDbReadTarget,
                                   itemCount: Long,
                                   bytes: Long
                                 ) extends StorageMetricEvent

  /** A Query returned data to the caller */
  final case class QueryReturned(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget,
                                  itemCount: Long,
                                  bytes: Long
                                ) extends StorageMetricEvent

  /** A Query was satisfied using only index-projected data */
  final case class QueryUsedIndexOnly(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbReadTarget
                                     ) extends StorageMetricEvent

  /** A Query fetched additional bytes from the base table after reading the index */
  final case class QueryFetchedFromBaseTable(
                                              eventTime: SimTime,
                                              usecase: Any,
                                              target: DynamoDbReadTarget,
                                              itemCount: Long,
                                              bytes: Long
                                            ) extends StorageMetricEvent

  /** One Scan request reached the data plane */
  final case class ScanObserved(
                                 eventTime: SimTime,
                                 usecase: Any,
                                 target: DynamoDbReadTarget
                               ) extends StorageMetricEvent

  /** A Scan evaluated data while executing */
  final case class ScanEvaluated(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget,
                                  itemCount: Long,
                                  bytes: Long
                                ) extends StorageMetricEvent

  /** A Scan returned data to the caller */
  final case class ScanReturned(
                                 eventTime: SimTime,
                                 usecase: Any,
                                 target: DynamoDbReadTarget,
                                 itemCount: Long,
                                 bytes: Long
                               ) extends StorageMetricEvent

  /** A Scan was satisfied using only index-projected data */
  final case class ScanUsedIndexOnly(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      target: DynamoDbReadTarget
                                    ) extends StorageMetricEvent

  /** A Scan fetched additional bytes from the base table after reading the index */
  final case class ScanFetchedFromBaseTable(
                                             eventTime: SimTime,
                                             usecase: Any,
                                             target: DynamoDbReadTarget,
                                             itemCount: Long,
                                             bytes: Long
                                           ) extends StorageMetricEvent

  /** Items returned to the caller from a Query or Scan. Emitted once per admitted request,
   *  including zero-count results. */
  final case class ReturnedItemCount(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      operation: DynamoDbOperationKind,
                                      count: Long
                                    ) extends StorageMetricEvent

  final case class IndexEntryInserted(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbTarget,
                                       bytes: Long
                                     ) extends StorageMetricEvent

  final case class IndexEntryReplaced(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbTarget,
                                       previousBytes: Long,
                                       newBytes: Long,
                                       bytesDelta: Long
                                     ) extends StorageMetricEvent

  final case class IndexEntryDeleted(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      target: DynamoDbTarget,
                                      bytes: Long
                                    ) extends StorageMetricEvent

  final case class IndexEntryUnchanged(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        target: DynamoDbTarget
                                      ) extends StorageMetricEvent

  /** One PutItem request reached the data plane */
  final case class PutItemObserved(
                                    eventTime: SimTime,
                                    usecase: Any
                                  ) extends StorageMetricEvent

  /** A PutItem stored an item */
  final case class PutItemStored(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  bytes: Long,
                                  createdNewItem: Boolean
                                ) extends StorageMetricEvent

  /** One UpdateItem request reached the data plane */
  final case class UpdateItemObserved(
                                       eventTime: SimTime,
                                       usecase: Any
                                     ) extends StorageMetricEvent

  /** An UpdateItem stored the resulting item */
  final case class UpdateItemStored(
                                     eventTime: SimTime,
                                     usecase: Any,
                                     bytes: Long,
                                     createdNewItem: Boolean
                                   ) extends StorageMetricEvent

  /** One DeleteItem request reached the data plane */
  final case class DeleteItemObserved(
                                       eventTime: SimTime,
                                       usecase: Any
                                     ) extends StorageMetricEvent

  /** A DeleteItem removed an existing item */
  final case class DeleteItemDeleted(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      bytes: Long
                                    ) extends StorageMetricEvent

  /** A write operation changed the table item-count total */
  final case class TableItemCountChanged(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          delta: Long
                                        ) extends StorageMetricEvent

  /** A write operation changed the table byte total */
  final case class TableBytesChanged(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      delta: Long
                                    ) extends StorageMetricEvent

  /** A write was rejected because the resulting LSI-backed item collection would exceed the configured limit. */
  final case class ItemCollectionSizeLimitExceeded(
                                                    eventTime: SimTime,
                                                    usecase: Any,
                                                    operation: DynamoDbOperationKind,
                                                    target: DynamoDbTarget,
                                                    logicalPartitionAccess: LogicalPartitionAccess,
                                                    resultingCollectionBytes: Long,
                                                    limitBytes: Long
                                                  ) extends StorageMetricEvent

  /** A storage-layer system error was simulated for this admitted request. */
  final case class SystemError(
    eventTime: SimTime,
    usecase: Any,
    operation: DynamoDbOperationKind,
    target: DynamoDbTarget
  ) extends StorageMetricEvent
