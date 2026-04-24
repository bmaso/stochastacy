package stochastacy.aws.dynamodb

import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.aws.{AWSServiceRequestEvent, AWSServiceResponseEvent}
import stochastacy.sim.SimTime

sealed trait DynamoDbReadTarget

object DynamoDbReadTarget:
  final case class Table(tableName: String) extends DynamoDbReadTarget
  final case class GlobalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbReadTarget
  final case class LocalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbReadTarget

sealed trait DynamoDbOperationKind

object DynamoDbOperationKind:
  case object GetItem extends DynamoDbOperationKind
  case object PutItem extends DynamoDbOperationKind
  case object UpdateItem extends DynamoDbOperationKind
  case object DeleteItem extends DynamoDbOperationKind
  case object Query extends DynamoDbOperationKind
  case object Scan extends DynamoDbOperationKind
  case object PartiQLQuery extends DynamoDbOperationKind

  def fromRequest(request: DynamoDBRequest): DynamoDbOperationKind =
    request match
      case _: GetItemRequest => GetItem
      case _: PutItemRequest => PutItem
      case _: UpdateItemRequest => UpdateItem
      case _: DeleteItemRequest => DeleteItem
      case _: QueryRequest => Query
      case _: ScanRequest => Scan
      case _: PartiQLQueryRequest => PartiQLQuery

sealed trait DynamoDbThroughputDimension

object DynamoDbThroughputDimension:
  case object Read extends DynamoDbThroughputDimension
  case object Write extends DynamoDbThroughputDimension

sealed trait DynamoDbThrottleReason

object DynamoDbThrottleReason:
  case object TableReadMaxOnDemandThroughputExceeded extends DynamoDbThrottleReason
  case object TableWriteMaxOnDemandThroughputExceeded extends DynamoDbThrottleReason
  case object GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded extends DynamoDbThrottleReason
  case object GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded extends DynamoDbThrottleReason
  case object TableReadHotPartitionThroughputExceeded extends DynamoDbThrottleReason
  case object TableWriteHotPartitionThroughputExceeded extends DynamoDbThrottleReason
  case object GlobalSecondaryIndexReadHotPartitionThroughputExceeded extends DynamoDbThrottleReason
  case object GlobalSecondaryIndexWriteHotPartitionThroughputExceeded extends DynamoDbThrottleReason

sealed trait DynamoDBRequest extends AWSServiceRequestEvent
sealed trait DynamoDBResponse extends AWSServiceResponseEvent

sealed trait RequestedReadShape

object RequestedReadShape:
  case object AllProjectedOrFullItem extends RequestedReadShape
  final case class RequestedAttributeBytes(bytes: Long) extends RequestedReadShape:
    require(bytes > 0L, s"RequestedAttributeBytes.bytes must be positive, got $bytes")
  case object ProjectedOnly extends RequestedReadShape

case class GetItemRequest(override val eventTime: SimTime, override val usecase: Any)
    extends DynamoDBRequest

case class PutItemRequest(
                           override val eventTime: SimTime,
                           override val usecase: Any,
                           itemBytes: Long
                         ) extends DynamoDBRequest

case class UpdateItemRequest(
                              override val eventTime: SimTime,
                              override val usecase: Any,
                              itemBytes: Long
                            ) extends DynamoDBRequest

case class DeleteItemRequest(
                              override val eventTime: SimTime,
                              override val usecase: Any
                            ) extends DynamoDBRequest

case class QueryRequest(
                         override val eventTime: SimTime,
                         override val usecase: Any,
                         target: DynamoDbReadTarget,
                         readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                         requestedReadShape: RequestedReadShape = RequestedReadShape.AllProjectedOrFullItem
                       ) extends DynamoDBRequest

case class ScanRequest(
                        override val eventTime: SimTime,
                        override val usecase: Any,
                        target: DynamoDbReadTarget,
                        readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                        requestedReadShape: RequestedReadShape = RequestedReadShape.AllProjectedOrFullItem
                      ) extends DynamoDBRequest

case class PartiQLQueryRequest(
                                override val eventTime: SimTime,
                                override val usecase: Any,
                                queryText: String
                              ) extends DynamoDBRequest

/**
 * The non-error response to a GetItem request submitted to a DDB table
 */
case class GetItemResponse(
                            override val eventTime: SimTime,
                            override val usecase: Any,
                            itemFound: Boolean,
                            itemBytes: Option[Long]
                          )
    extends DynamoDBResponse

case class PutItemResponse(
                            override val eventTime: SimTime,
                            override val usecase: Any,
                            storedItemBytes: Long,
                            createdNewItem: Boolean,
                            previousItemBytes: Option[Long]
                          ) extends DynamoDBResponse

case class UpdateItemResponse(
                               override val eventTime: SimTime,
                               override val usecase: Any,
                               storedItemBytes: Long,
                               createdNewItem: Boolean,
                               previousItemBytes: Option[Long]
                             ) extends DynamoDBResponse

case class DeleteItemResponse(
                               override val eventTime: SimTime,
                               override val usecase: Any,
                               deletedItemBytes: Option[Long]
                             ) extends DynamoDBResponse

case class QueryResponse(
                          override val eventTime: SimTime,
                          override val usecase: Any,
                          target: DynamoDbReadTarget,
                          readConsistency: ReadConsistency,
                          evaluatedItemCount: Long,
                          evaluatedBytes: Long,
                          returnedItemCount: Long,
                          returnedBytes: Long
                        ) extends DynamoDBResponse

case class ScanResponse(
                         override val eventTime: SimTime,
                         override val usecase: Any,
                         target: DynamoDbReadTarget,
                         readConsistency: ReadConsistency,
                         evaluatedItemCount: Long,
                         evaluatedBytes: Long,
                         returnedItemCount: Long,
                         returnedBytes: Long
                       ) extends DynamoDBResponse

case class PartiQLQueryResponse(
                                 override val eventTime: SimTime,
                                 override val usecase: Any,
                                 queryText: String
                               ) extends DynamoDBResponse

case class ThrottledResponse(
                              override val eventTime: SimTime,
                              override val usecase: Any,
                              operation: DynamoDbOperationKind,
                              target: stochastacy.aws.dynamodb.table.DynamoDbTarget,
                              dimension: DynamoDbThroughputDimension,
                              reason: DynamoDbThrottleReason
                            ) extends DynamoDBResponse
