package stochastacy.aws.dynamodb

import stochastacy.aws.{AWSServiceRequestEvent, AWSServiceResponseEvent}
import stochastacy.sim.SimTime

sealed trait DynamoDBRequest extends AWSServiceRequestEvent
sealed trait DynamoDBResponse extends AWSServiceResponseEvent

case class GetItemRequest(override val eventTime: SimTime, override val usecase: Any)
    extends DynamoDBRequest

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
