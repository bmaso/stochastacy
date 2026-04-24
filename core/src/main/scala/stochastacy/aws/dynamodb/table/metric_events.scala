package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.sim.SimTime

sealed trait Stage4MetricEvent extends TableMetricEvent

object Stage4MetricEvent:

  /** One GetItem request reached the data plane */
  final case class GetItemObserved(
                                    eventTime: SimTime,
                                    usecase: Any
                                  ) extends Stage4MetricEvent

  /** A GetItem returned an item */
  final case class GetItemReturned(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    bytes: Long
                                  ) extends Stage4MetricEvent

  /** One Query request reached the data plane */
  final case class QueryObserved(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget
                                ) extends Stage4MetricEvent

  /** A Query evaluated data while executing */
  final case class QueryEvaluated(
                                   eventTime: SimTime,
                                   usecase: Any,
                                   target: DynamoDbReadTarget,
                                   itemCount: Long,
                                   bytes: Long
                                 ) extends Stage4MetricEvent

  /** A Query returned data to the caller */
  final case class QueryReturned(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget,
                                  itemCount: Long,
                                  bytes: Long
                                ) extends Stage4MetricEvent

  /** A Query was satisfied using only index-projected data */
  final case class QueryUsedIndexOnly(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbReadTarget
                                     ) extends Stage4MetricEvent

  /** A Query fetched additional bytes from the base table after reading the index */
  final case class QueryFetchedFromBaseTable(
                                              eventTime: SimTime,
                                              usecase: Any,
                                              target: DynamoDbReadTarget,
                                              itemCount: Long,
                                              bytes: Long
                                            ) extends Stage4MetricEvent

  /** One Scan request reached the data plane */
  final case class ScanObserved(
                                 eventTime: SimTime,
                                 usecase: Any,
                                 target: DynamoDbReadTarget
                               ) extends Stage4MetricEvent

  /** A Scan evaluated data while executing */
  final case class ScanEvaluated(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  target: DynamoDbReadTarget,
                                  itemCount: Long,
                                  bytes: Long
                                ) extends Stage4MetricEvent

  /** A Scan returned data to the caller */
  final case class ScanReturned(
                                 eventTime: SimTime,
                                 usecase: Any,
                                 target: DynamoDbReadTarget,
                                 itemCount: Long,
                                 bytes: Long
                               ) extends Stage4MetricEvent

  /** A Scan was satisfied using only index-projected data */
  final case class ScanUsedIndexOnly(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      target: DynamoDbReadTarget
                                    ) extends Stage4MetricEvent

  /** A Scan fetched additional bytes from the base table after reading the index */
  final case class ScanFetchedFromBaseTable(
                                             eventTime: SimTime,
                                             usecase: Any,
                                             target: DynamoDbReadTarget,
                                             itemCount: Long,
                                             bytes: Long
                                           ) extends Stage4MetricEvent

  final case class IndexEntryInserted(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbTarget,
                                       bytes: Long
                                     ) extends Stage4MetricEvent

  final case class IndexEntryReplaced(
                                       eventTime: SimTime,
                                       usecase: Any,
                                       target: DynamoDbTarget,
                                       previousBytes: Long,
                                       newBytes: Long,
                                       bytesDelta: Long
                                     ) extends Stage4MetricEvent

  final case class IndexEntryDeleted(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      target: DynamoDbTarget,
                                      bytes: Long
                                    ) extends Stage4MetricEvent

  final case class IndexEntryUnchanged(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        target: DynamoDbTarget
                                      ) extends Stage4MetricEvent

  /** One PutItem request reached the data plane */
  final case class PutItemObserved(
                                    eventTime: SimTime,
                                    usecase: Any
                                  ) extends Stage4MetricEvent

  /** A PutItem stored an item */
  final case class PutItemStored(
                                  eventTime: SimTime,
                                  usecase: Any,
                                  bytes: Long,
                                  createdNewItem: Boolean
                                ) extends Stage4MetricEvent

  /** One UpdateItem request reached the data plane */
  final case class UpdateItemObserved(
                                       eventTime: SimTime,
                                       usecase: Any
                                     ) extends Stage4MetricEvent

  /** An UpdateItem stored the resulting item */
  final case class UpdateItemStored(
                                     eventTime: SimTime,
                                     usecase: Any,
                                     bytes: Long,
                                     createdNewItem: Boolean
                                   ) extends Stage4MetricEvent

  /** One DeleteItem request reached the data plane */
  final case class DeleteItemObserved(
                                       eventTime: SimTime,
                                       usecase: Any
                                     ) extends Stage4MetricEvent

  /** A DeleteItem removed an existing item */
  final case class DeleteItemDeleted(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      bytes: Long
                                    ) extends Stage4MetricEvent

  /** A write operation changed the table item-count total */
  final case class TableItemCountChanged(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          delta: Long
                                        ) extends Stage4MetricEvent

  /** A write operation changed the table byte total */
  final case class TableBytesChanged(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      delta: Long
                                    ) extends Stage4MetricEvent
