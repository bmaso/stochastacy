package stochastacy.aws.ddb

final case class Stage4MetricTotals(
                                     observedGets: Long = 0,
                                     returnedItems: Long = 0,
                                     returnedBytes: Long = 0
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