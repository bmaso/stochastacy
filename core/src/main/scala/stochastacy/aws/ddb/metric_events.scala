package stochastacy.aws.ddb

import stochastacy.aws.MetricEvent
import stochastacy.graphs.SimTime

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