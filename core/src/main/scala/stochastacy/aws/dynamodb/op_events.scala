package stochastacy.aws.dynamodb

import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.aws.{AWSServiceRequestEvent, AWSServiceResponseEvent}
import stochastacy.sim.SimTime

sealed trait DynamoDbReadTarget

object DynamoDbReadTarget:
  final case class Table(tableName: String) extends DynamoDbReadTarget
  final case class GlobalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbReadTarget
  final case class LocalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbReadTarget

sealed trait DynamoDBRequest extends AWSServiceRequestEvent
sealed trait DynamoDBResponse extends AWSServiceResponseEvent

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
                         readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
                       ) extends DynamoDBRequest

case class ScanRequest(
                        override val eventTime: SimTime,
                        override val usecase: Any,
                        target: DynamoDbReadTarget,
                        readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
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
