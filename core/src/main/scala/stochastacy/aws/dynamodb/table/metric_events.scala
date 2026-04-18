package stochastacy.aws.dynamodb.table

import stochastacy.aws.MetricEvent
import stochastacy.sim.SimTime

sealed trait Stage4MetricEvent extends MetricEvent

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

  /** A PutItem changed the table item-count total */
  final case class TableItemCountChanged(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          delta: Long
                                        ) extends Stage4MetricEvent

  /** A PutItem changed the table byte total */
  final case class TableBytesChanged(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      delta: Long
                                    ) extends Stage4MetricEvent
