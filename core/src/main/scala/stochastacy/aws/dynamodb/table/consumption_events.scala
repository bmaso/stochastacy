package stochastacy.aws.dynamodb.table

import stochastacy.aws.ResourceConsumptionEvent
import stochastacy.sim.SimTime

sealed trait DynamoDbConsumptionEvent extends ResourceConsumptionEvent:
  def target: DynamoDbTarget

sealed trait DynamoDbTarget

object DynamoDbTarget:
  final case class Table(name: String) extends DynamoDbTarget
  final case class GlobalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbTarget
  final case class LocalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbTarget

enum ReadConsistency:
  case EventuallyConsistent
  case StronglyConsistent

object DynamoDbConsumptionEvent:

  final case class ReadCapacityConsumed(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          target: DynamoDbTarget,
                                          units: BigDecimal,
                                          consistency: ReadConsistency
                                        ) extends DynamoDbConsumptionEvent

  final case class StorageBytesRead(
                                     eventTime: SimTime,
                                     usecase: Any,
                                     target: DynamoDbTarget,
                                     bytes: Long
                                   ) extends DynamoDbConsumptionEvent

  final case class WriteCapacityConsumed(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          target: DynamoDbTarget,
                                          units: BigDecimal
                                        ) extends DynamoDbConsumptionEvent

  /** Replicated write capacity consumed at a destination region (distinct from WCU for billing). */
  final case class ReplicatedWriteCapacityConsumed(
                                                    eventTime: SimTime,
                                                    usecase: Any,
                                                    target: DynamoDbTarget,
                                                    units: BigDecimal
                                                  ) extends DynamoDbConsumptionEvent

  final case class StorageBytesWritten(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        target: DynamoDbTarget,
                                        bytes: Long
                                      ) extends DynamoDbConsumptionEvent

  final case class StorageBytesDeleted(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        target: DynamoDbTarget,
                                        bytes: Long
                                      ) extends DynamoDbConsumptionEvent

  final case class StorageBytesDelta(
                                      eventTime: SimTime,
                                      usecase: Any,
                                      target: DynamoDbTarget,
                                      bytesDelta: Long
                                    ) extends DynamoDbConsumptionEvent

  /** Mirrors StorageBytesDelta but only emitted when PITR is enabled. Accumulated separately
   *  to compute the PITR storage charge without affecting base storage accounting. */
  final case class PITRStorageBytesDelta(
                                          eventTime: SimTime,
                                          usecase: Any,
                                          target: DynamoDbTarget,
                                          bytesDelta: Long
                                        ) extends DynamoDbConsumptionEvent
