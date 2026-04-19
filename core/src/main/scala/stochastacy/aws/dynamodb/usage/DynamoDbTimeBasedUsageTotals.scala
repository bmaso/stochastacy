package stochastacy.aws.dynamodb.usage

import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTarget}
import stochastacy.sim.{TimedControlEvent, TimedElement}

final case class DynamoDbTargetTimeBasedUsageTotals(
                                                     storageByteTicks: BigInt = BigInt(0),
                                                     endingStorageBytes: Long = 0L
                                                   )

final case class DynamoDbTimeBasedUsageTotals(
                                               overallStorageByteTicks: BigInt = BigInt(0),
                                               endingOverallStorageBytes: Long = 0L,
                                               byTarget: Map[DynamoDbTarget, DynamoDbTargetTimeBasedUsageTotals] = Map.empty
                                             )

object DynamoDbTimeBasedUsageTotals:

  def fromTimedEvents(
                       events: Seq[TimedElement[DynamoDbConsumptionEvent]]
                     ): DynamoDbTimeBasedUsageTotals =
    finalizeState(events.foldLeft(AccumulatorState())(accumulate))

  private case class AccumulatorState(
                                       byteTicksByTarget: Map[DynamoDbTarget, BigInt] = Map.empty,
                                       currentStorageBytesByTarget: Map[DynamoDbTarget, Long] = Map.empty,
                                       lastTickSeen: Option[TimedControlEvent.Tick] = None
                                     )

  private def accumulate(
                          acc: AccumulatorState,
                          evt: TimedElement[DynamoDbConsumptionEvent]
                        ): AccumulatorState =
    evt match
      case tick: TimedControlEvent.Tick =>
        acc.lastTickSeen match
          case Some(_) =>
            val nextByteTicksByTarget =
              acc.currentStorageBytesByTarget.foldLeft(acc.byteTicksByTarget) {
                case (btAcc, (target, currentBytes)) =>
                  btAcc.updated(
                    target,
                    btAcc.getOrElse(target, BigInt(0)) + BigInt(currentBytes)
                  )
              }
            acc.copy(
              byteTicksByTarget = nextByteTicksByTarget,
              lastTickSeen = Some(tick)
            )

          case None =>
            acc.copy(lastTickSeen = Some(tick))

      case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, target, bytesDelta) =>
        val currentBytes = acc.currentStorageBytesByTarget.getOrElse(target, 0L)
        acc.copy(
          currentStorageBytesByTarget =
            acc.currentStorageBytesByTarget.updated(target, currentBytes + bytesDelta)
        )

      case _ =>
        acc

  private def finalizeState(acc: AccumulatorState): DynamoDbTimeBasedUsageTotals =
    val allTargets = acc.byteTicksByTarget.keySet ++ acc.currentStorageBytesByTarget.keySet

    val byTarget =
      allTargets.iterator.map { target =>
        target ->
          DynamoDbTargetTimeBasedUsageTotals(
            storageByteTicks = acc.byteTicksByTarget.getOrElse(target, BigInt(0)),
            endingStorageBytes = acc.currentStorageBytesByTarget.getOrElse(target, 0L)
          )
      }.toMap

    DynamoDbTimeBasedUsageTotals(
      overallStorageByteTicks = byTarget.valuesIterator.map(_.storageByteTicks).sum,
      endingOverallStorageBytes = byTarget.valuesIterator.map(_.endingStorageBytes).sum,
      byTarget = byTarget
    )
