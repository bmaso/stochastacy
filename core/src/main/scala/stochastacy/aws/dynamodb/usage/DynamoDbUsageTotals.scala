package stochastacy.aws.dynamodb.usage

import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTarget}

final case class DynamoDbTargetUsageTotals(
                                            readCapacityUnits: BigDecimal = BigDecimal(0),
                                            writeCapacityUnits: BigDecimal = BigDecimal(0),
                                            storageBytesRead: Long = 0L,
                                            storageBytesWritten: Long = 0L,
                                            storageBytesDeleted: Long = 0L,
                                            storageBytesDelta: Long = 0L
                                          )

final case class DynamoDbUsageTotals(
                                       overall: DynamoDbTargetUsageTotals = DynamoDbTargetUsageTotals(),
                                       byTarget: Map[DynamoDbTarget, DynamoDbTargetUsageTotals] = Map.empty
                                     )

object DynamoDbUsageTotals:

  def accumulate(
                  acc: DynamoDbUsageTotals,
                  evt: DynamoDbConsumptionEvent
                ): DynamoDbUsageTotals =
    val targetTotals = acc.byTarget.getOrElse(evt.target, DynamoDbTargetUsageTotals())
    val nextTargetTotals = accumulateTarget(targetTotals, evt)

    acc.copy(
      overall = accumulateTarget(acc.overall, evt),
      byTarget = acc.byTarget.updated(evt.target, nextTargetTotals)
    )

  private def accumulateTarget(
                               acc: DynamoDbTargetUsageTotals,
                               evt: DynamoDbConsumptionEvent
                             ): DynamoDbTargetUsageTotals =
    evt match
      case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, _, units, _) =>
        acc.copy(readCapacityUnits = acc.readCapacityUnits + units)

      case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, _, units) =>
        acc.copy(writeCapacityUnits = acc.writeCapacityUnits + units)

      case DynamoDbConsumptionEvent.StorageBytesRead(_, _, _, bytes) =>
        acc.copy(storageBytesRead = acc.storageBytesRead + bytes)

      case DynamoDbConsumptionEvent.StorageBytesWritten(_, _, _, bytes) =>
        acc.copy(storageBytesWritten = acc.storageBytesWritten + bytes)

      case DynamoDbConsumptionEvent.StorageBytesDeleted(_, _, _, bytes) =>
        acc.copy(storageBytesDeleted = acc.storageBytesDeleted + bytes)

      case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, _, bytesDelta) =>
        acc.copy(storageBytesDelta = acc.storageBytesDelta + bytesDelta)
