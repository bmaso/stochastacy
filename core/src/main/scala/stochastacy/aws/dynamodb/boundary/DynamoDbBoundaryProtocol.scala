package stochastacy.aws.dynamodb.boundary

import stochastacy.aws.boundary.{BoundaryDropDirection, BoundaryProtocol}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.SimTime

/**
 * DynamoDB instance of [[BoundaryProtocol]].  Lives in the DynamoDB layer (not
 * the generic `boundary` package); a future service (S3 CRR, RDS, …) supplies
 * its own instance the same way.
 *
 * The restamp dispatchers mirror the shape of `SdkClientStage.rebuildRetry`
 * (requests) and `stochastacy.test.clearTiming` (responses): a per-case
 * `.copy(eventTime, intraTick)` that preserves the concrete type and every
 * other field.  `clientAttempt` is left untouched — transport latency does not
 * change the SDK attempt number.
 */
object DynamoDbBoundaryProtocol extends BoundaryProtocol[DynamoDBRequest, DynamoDBResponse]:

  override def withRequestTiming(req: DynamoDBRequest, eventTime: SimTime, intraTick: Double): DynamoDBRequest =
    req match
      case r: GetItemRequest            => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: PutItemRequest            => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: UpdateItemRequest         => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: DeleteItemRequest         => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: QueryRequest              => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: ScanRequest               => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: PartiQLQueryRequest       => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: TransactWriteItemsRequest => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: TransactGetItemsRequest   => r.copy(eventTime = eventTime, intraTick = intraTick)

  override def withResponseTiming(resp: DynamoDBResponse, eventTime: SimTime, intraTick: Double): DynamoDBResponse =
    resp match
      case r: GetItemResponse                         => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: PutItemResponse                         => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: UpdateItemResponse                      => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: DeleteItemResponse                      => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: QueryResponse                           => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: ScanResponse                            => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: PartiQLQueryResponse                    => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: ThrottledResponse                       => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: ItemCollectionSizeLimitExceededResponse => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: SystemErrorResponse                     => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: ReconfigurationRejectedResponse         => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: BoundaryTimeoutResponse                 => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: TransactWriteItemsResponse              => r.copy(eventTime = eventTime, intraTick = intraTick)
      case r: TransactGetItemsResponse                => r.copy(eventTime = eventTime, intraTick = intraTick)

  override def timeoutResponse(
    req:       DynamoDBRequest,
    eventTime: SimTime,
    intraTick: Double,
    direction: BoundaryDropDirection
  ): DynamoDBResponse =
    BoundaryTimeoutResponse(
      eventTime        = eventTime,
      usecase          = req.usecase,
      droppedDirection = direction,
      intraTick        = intraTick,
      flowId           = req.flowId,
      clientAttempt    = req.clientAttempt,
      originalRequest  = Some(req)
    )

  override def originalRequestOf(resp: DynamoDBResponse): Option[DynamoDBRequest] =
    resp.originalRequest

  override def measureRequest(req: DynamoDBRequest, dimension: String): Long =
    dimension match
      case "requests" => 1L
      case "bytes" =>
        req match
          case r: PutItemRequest    => r.itemBytes
          case r: UpdateItemRequest => r.itemBytes
          case _                    => 0L   // reads carry a negligible request payload
      case _ => 0L

  override def measureResponse(resp: DynamoDBResponse, dimension: String): Long =
    dimension match
      case "requests" => 1L
      case "bytes" =>
        resp match
          case r: GetItemResponse    => r.itemBytes.getOrElse(0L)
          case r: QueryResponse      => r.returnedBytes
          case r: ScanResponse       => r.returnedBytes
          case r: PutItemResponse    => r.storedItemBytes
          case r: UpdateItemResponse => r.storedItemBytes
          case _                     => 0L
      case _ => 0L
