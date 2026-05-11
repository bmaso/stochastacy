package stochastacy.aws.dynamodb.table

final case class StorageConsumptionTotals(
                                          readCapacityUnits: BigDecimal = BigDecimal(0),
                                          writeCapacityUnits: BigDecimal = BigDecimal(0),
                                          storageBytesRead: Long = 0L,
                                          storageBytesWritten: Long = 0L,
                                          storageBytesDeleted: Long = 0L,
                                          storageBytesDelta: Long = 0L,
                                          targets: Set[DynamoDbTarget] = Set.empty,
                                          consistencies: Set[ReadConsistency] = Set.empty
                                        )

object StorageConsumptionTotals:
  def accumulate(
                  acc: StorageConsumptionTotals,
                  evt: DynamoDbConsumptionEvent
                ): StorageConsumptionTotals =
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

      case DynamoDbConsumptionEvent.StorageBytesDeleted(_, _, target, bytes) =>
        acc.copy(
          storageBytesDeleted = acc.storageBytesDeleted + bytes,
          targets = acc.targets + target
        )

      case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, target, bytesDelta) =>
        acc.copy(
          storageBytesDelta = acc.storageBytesDelta + bytesDelta,
          targets = acc.targets + target
        )

      case _: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed => acc
      case _: DynamoDbConsumptionEvent.PITRStorageBytesDelta           => acc
