package stochastacy.aws.dynamodb

import stochastacy.aws.{AWSServiceRequestEvent, AWSServiceResponseEvent}
import stochastacy.sim.SimTime

sealed trait DynamoDBRequest extends AWSServiceRequestEvent
sealed trait DynamoDBResponse extends AWSServiceResponseEvent

case class GetItemRequest(override val eventTime: SimTime, override val usecase: Any)
    extends DynamoDBRequest

case class PutItemRequest(
                           override val eventTime: SimTime,
                           override val usecase: Any,
                           itemBytes: Long
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
