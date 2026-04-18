package stochastacy.aws.dynamodb.table

final case class Stage4ConsumptionTotals(
                                          readCapacityUnits: BigDecimal = BigDecimal(0),
                                          writeCapacityUnits: BigDecimal = BigDecimal(0),
                                          storageBytesRead: Long = 0L,
                                          storageBytesWritten: Long = 0L,
                                          storageBytesDelta: Long = 0L,
                                          targets: Set[DynamoDbTarget] = Set.empty,
                                          consistencies: Set[ReadConsistency] = Set.empty
                                        )

object Stage4ConsumptionTotals:
  def accumulate(
                  acc: Stage4ConsumptionTotals,
                  evt: DynamoDbConsumptionEvent
                ): Stage4ConsumptionTotals =
    evt match
      case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, target, units, consistency) =>
        acc.copy(
          readCapacityUnits = acc.readCapacityUnits + units,
          targets = acc.targets + target,
          consistencies = acc.consistencies + consistency
        )

      case DynamoDbConsumptionEvent.StorageBytesRead(_, _, target, bytes) =>
        acc.copy(
          storageBytesRead = acc.storageBytesRead + bytes,
          targets = acc.targets + target
        )

      case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, target, units) =>
        acc.copy(
          writeCapacityUnits = acc.writeCapacityUnits + units,
          targets = acc.targets + target
        )

      case DynamoDbConsumptionEvent.StorageBytesWritten(_, _, target, bytes) =>
        acc.copy(
          storageBytesWritten = acc.storageBytesWritten + bytes,
          targets = acc.targets + target
        )

      case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, target, bytesDelta) =>
        acc.copy(
          storageBytesDelta = acc.storageBytesDelta + bytesDelta,
          targets = acc.targets + target
        )
