package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.{DeleteItemRequest, DeleteItemResponse, DynamoDBRequest, DynamoDBResponse, GetItemRequest, GetItemResponse, PartiQLQueryRequest, PutItemRequest, PutItemResponse, QueryRequest, QueryResponse, ScanRequest, ScanResponse, UpdateItemRequest, UpdateItemResponse}
import stochastacy.sim.*

/**
 * A table is implemented as a multi-stage Pekko component graph. Stage 4 of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStage4:

  private val BytesPerReadCapacityUnitChunk = 4096L
  private val BytesPerWriteCapacityUnitChunk = 1024L

  private sealed trait TimedRequestSample extends TimedEvent:
    def req: DynamoDBRequest
    override val eventTime: SimTime = req.eventTime
    override val usecase: Any = req.usecase

  private case class TimedGetItemSample(req: GetItemRequest, sample: Option[GetItemSample]) extends TimedRequestSample

  private case class TimedQuerySample(req: QueryRequest, sample: QuerySample) extends TimedRequestSample

  private case class TimedScanSample(req: ScanRequest, sample: ScanSample) extends TimedRequestSample

  private case class TimedPutItemSample(req: PutItemRequest, sample: PutItemSample) extends TimedRequestSample

  private case class TimedUpdateItemSample(req: UpdateItemRequest, sample: UpdateItemSample) extends TimedRequestSample

  private case class TimedDeleteItemSample(req: DeleteItemRequest, sample: DeleteItemSample) extends TimedRequestSample

  def componentOf(
                   stateModel: TableState,
                   useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                   tableTarget: DynamoDbTarget = DynamoDbTarget.Table("table"),
                   readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse], // <-- response events in a timed stream
      TimedElement[DynamoDbConsumptionEvent], // <-- consumption events in a timed stream
      TimedElement[Stage4MetricEvent] // <-- metric events in a timed stream
    ],
    NotUsed
  ] = {
    def readCapacityUnitsFor(itemBytes: Option[Long], consistency: ReadConsistency): BigDecimal =
      val readCapacityUnitMultiplier = consistency match
        case ReadConsistency.EventuallyConsistent => BigDecimal("0.5")
        case ReadConsistency.StronglyConsistent => BigDecimal(1)
      val chunkCount = itemBytes match
        case Some(bytes) if bytes > 0 =>
          ((bytes - 1L) / BytesPerReadCapacityUnitChunk) + 1L
        case _ =>
          1L
      BigDecimal(chunkCount) * readCapacityUnitMultiplier

    def writeCapacityUnitsFor(itemBytes: Long): BigDecimal =
      val chunkCount =
        if itemBytes > 0 then ((itemBytes - 1L) / BytesPerWriteCapacityUnitChunk) + 1L
        else 1L
      BigDecimal(chunkCount)

    def samplerFor(request: DynamoDBRequest): UseCaseSampler[TableState] =
      useCaseBehaviors.getOrElse(
        request.usecase,
        throw new IllegalArgumentException(s"No table behavior for '${request.usecase}'")
      )

    def targetFor(readTarget: stochastacy.aws.dynamodb.DynamoDbReadTarget): DynamoDbTarget =
      readTarget match
        case stochastacy.aws.dynamodb.DynamoDbReadTarget.Table(tableName) =>
          DynamoDbTarget.Table(tableName)
        case stochastacy.aws.dynamodb.DynamoDbReadTarget.GlobalSecondaryIndex(tableName, indexName) =>
          DynamoDbTarget.GlobalSecondaryIndex(tableName, indexName)
        case stochastacy.aws.dynamodb.DynamoDbReadTarget.LocalSecondaryIndex(tableName, indexName) =>
          DynamoDbTarget.LocalSecondaryIndex(tableName, indexName)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      // ─────────────────────────────────────────────────────────────
      // Request → sample; used internally
      // ─────────────────────────────────────────────────────────────
      val requestFlow = b.add(
        Flow[TimedElement[DynamoDBRequest]]
          .map[TimedElement[TimedRequestSample]] {
            case r: GetItemRequest =>
              val sampler = samplerFor(r)
              TimedGetItemSample(r, sampler.getItem(r, stateModel))

            case r: PutItemRequest =>
              val sampler = samplerFor(r)
              val sample = sampler.putItem(r, stateModel)
              stateModel.recordSuccessfulPut(sample.writtenItemBytes, sample.previousItemBytes)
              TimedPutItemSample(r, sample)

            case r: QueryRequest =>
              val sampler = samplerFor(r)
              TimedQuerySample(r, sampler.query(r, stateModel))

            case r: ScanRequest =>
              val sampler = samplerFor(r)
              TimedScanSample(r, sampler.scan(r, stateModel))

            case r: UpdateItemRequest =>
              val sampler = samplerFor(r)
              val sample = sampler.updateItem(r, stateModel)
              stateModel.recordSuccessfulUpdate(sample.writtenItemBytes, sample.previousItemBytes)
              TimedUpdateItemSample(r, sample)

            case r: DeleteItemRequest =>
              val sampler = samplerFor(r)
              val sample = sampler.deleteItem(r, stateModel)
              stateModel.recordSuccessfulDelete(sample.deletedItemBytes)
              TimedDeleteItemSample(r, sample)

            case _: PartiQLQueryRequest =>
              throw new UnsupportedOperationException("PartiQL query execution is not yet supported")
  
            case t: TimedControlEvent => t // ...everything else, which should just be TimedEvent elements, gets passed through
          }
      )

      val broadcast = b.add(Broadcast[TimedElement[TimedRequestSample]](3))

      // ─────────────────────────────────────────────────────────────
      // sample → Response
      // ─────────────────────────────────────────────────────────────
      val responseFlow =
        b.add(
          Flow[TimedElement[TimedRequestSample]].map[TimedElement[DynamoDBResponse]] {
            case t: TimedControlEvent => t

            case TimedGetItemSample(r: GetItemRequest, Some(s: GetItemSample)) =>
              GetItemResponse(
                eventTime = r.eventTime,
                usecase   = r.usecase,
                itemFound = true,
                itemBytes = Some(s.getItemBytes)
              )

            case TimedGetItemSample(r: GetItemRequest, None) =>
              GetItemResponse(
                eventTime = r.eventTime,
                usecase   = r.usecase,
                itemFound = false,
                itemBytes = None
              )

            case TimedQuerySample(r: QueryRequest, s: QuerySample) =>
              QueryResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = r.target,
                readConsistency = r.readConsistency,
                evaluatedItemCount = s.evaluatedItemCount,
                evaluatedBytes = s.evaluatedBytes,
                returnedItemCount = s.returnedItemCount,
                returnedBytes = s.returnedBytes
              )

            case TimedScanSample(r: ScanRequest, s: ScanSample) =>
              ScanResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = r.target,
                readConsistency = r.readConsistency,
                evaluatedItemCount = s.evaluatedItemCount,
                evaluatedBytes = s.evaluatedBytes,
                returnedItemCount = s.returnedItemCount,
                returnedBytes = s.returnedBytes
              )

            case TimedPutItemSample(r: PutItemRequest, s: PutItemSample) =>
              PutItemResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                storedItemBytes = s.writtenItemBytes,
                createdNewItem = s.createdNewItem,
                previousItemBytes = s.previousItemBytes
              )

            case TimedUpdateItemSample(r: UpdateItemRequest, s: UpdateItemSample) =>
              UpdateItemResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                storedItemBytes = s.writtenItemBytes,
                createdNewItem = s.createdNewItem,
                previousItemBytes = s.previousItemBytes
              )

            case TimedDeleteItemSample(r: DeleteItemRequest, s: DeleteItemSample) =>
              DeleteItemResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                deletedItemBytes = s.deletedItemBytes
              )
          }
        )

      // ─────────────────────────────────────────────────────────────
      // Request → Metric events
      // ─────────────────────────────────────────────────────────────
      val metricFlow =
        b.add(
          Flow[TimedElement[TimedRequestSample]].mapConcat[TimedElement[Stage4MetricEvent]] {
            case t: TimedControlEvent => List(t) // propagate time events

            case TimedGetItemSample(r: GetItemRequest, Some(s: GetItemSample)) =>
              List(
                Stage4MetricEvent.GetItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                ),
                Stage4MetricEvent.GetItemReturned(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  bytes = s.getItemBytes
                )
              )

            case TimedGetItemSample(r: GetItemRequest, None) =>
              List(
                Stage4MetricEvent.GetItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                )
              )

            case TimedQuerySample(r: QueryRequest, s: QuerySample) =>
              val returnedEvents =
                if s.returnedItemCount > 0L || s.returnedBytes > 0L then
                  List(
                    Stage4MetricEvent.QueryReturned(
                      eventTime = r.eventTime,
                      usecase = r.usecase,
                      target = r.target,
                      itemCount = s.returnedItemCount,
                      bytes = s.returnedBytes
                    )
                  )
                else Nil
              List(
                Stage4MetricEvent.QueryObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = r.target
                ),
                Stage4MetricEvent.QueryEvaluated(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = r.target,
                  itemCount = s.evaluatedItemCount,
                  bytes = s.evaluatedBytes
                )
              ) ++ returnedEvents

            case TimedScanSample(r: ScanRequest, s: ScanSample) =>
              val returnedEvents =
                if s.returnedItemCount > 0L || s.returnedBytes > 0L then
                  List(
                    Stage4MetricEvent.ScanReturned(
                      eventTime = r.eventTime,
                      usecase = r.usecase,
                      target = r.target,
                      itemCount = s.returnedItemCount,
                      bytes = s.returnedBytes
                    )
                  )
                else Nil
              List(
                Stage4MetricEvent.ScanObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = r.target
                ),
                Stage4MetricEvent.ScanEvaluated(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = r.target,
                  itemCount = s.evaluatedItemCount,
                  bytes = s.evaluatedBytes
                )
              ) ++ returnedEvents

            case TimedPutItemSample(r: PutItemRequest, s: PutItemSample) =>
              List(
                Stage4MetricEvent.PutItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                ),
                Stage4MetricEvent.PutItemStored(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  bytes = s.writtenItemBytes,
                  createdNewItem = s.createdNewItem
                ),
                Stage4MetricEvent.TableItemCountChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.itemCountDelta
                ),
                Stage4MetricEvent.TableBytesChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.storageBytesDelta
                )
              )

            case TimedUpdateItemSample(r: UpdateItemRequest, s: UpdateItemSample) =>
              List(
                Stage4MetricEvent.UpdateItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                ),
                Stage4MetricEvent.UpdateItemStored(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  bytes = s.writtenItemBytes,
                  createdNewItem = s.createdNewItem
                ),
                Stage4MetricEvent.TableItemCountChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.itemCountDelta
                ),
                Stage4MetricEvent.TableBytesChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.storageBytesDelta
                )
              )

            case TimedDeleteItemSample(r: DeleteItemRequest, s: DeleteItemSample) =>
              val deleteEvents =
                s.deletedItemBytes.toList.map { bytes =>
                  Stage4MetricEvent.DeleteItemDeleted(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    bytes = bytes
                  )
                }
              List(
                Stage4MetricEvent.DeleteItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                )
              ) ++ deleteEvents ++ List(
                Stage4MetricEvent.TableItemCountChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.itemCountDelta
                ),
                Stage4MetricEvent.TableBytesChanged(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  delta = s.storageBytesDelta
                )
              )
          }
        )

      // ─────────────────────────────────────────────────────────────
      // Resource consumption
      // ─────────────────────────────────────────────────────────────
      val consumptionFlow =
        b.add(
          Flow[TimedElement[TimedRequestSample]].mapConcat[TimedElement[DynamoDbConsumptionEvent]] {
            case t: TimedControlEvent => List(t)

            case TimedGetItemSample(r: GetItemRequest, Some(s: GetItemSample)) =>
              List(
                DynamoDbConsumptionEvent.ReadCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  units = readCapacityUnitsFor(Some(s.getItemBytes), readConsistency),
                  consistency = readConsistency
                ),
                DynamoDbConsumptionEvent.StorageBytesRead(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytes = s.getItemBytes
                )
              )

            case TimedGetItemSample(r: GetItemRequest, None) =>
              List(
                DynamoDbConsumptionEvent.ReadCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  units = readCapacityUnitsFor(None, readConsistency),
                  consistency = readConsistency
                )
              )

            case TimedQuerySample(r: QueryRequest, s: QuerySample) =>
              val queryTarget = targetFor(r.target)

              val bytesReadEvents =
                if s.evaluatedBytes > 0L then
                  List(
                    DynamoDbConsumptionEvent.StorageBytesRead(
                      eventTime = r.eventTime,
                      usecase = r.usecase,
                      target = queryTarget,
                      bytes = s.evaluatedBytes
                    )
                  )
                else Nil

              List(
                DynamoDbConsumptionEvent.ReadCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = queryTarget,
                  units = readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                  consistency = r.readConsistency
                )
              ) ++ bytesReadEvents

            case TimedScanSample(r: ScanRequest, s: ScanSample) =>
              val scanTarget = targetFor(r.target)

              val bytesReadEvents =
                if s.evaluatedBytes > 0L then
                  List(
                    DynamoDbConsumptionEvent.StorageBytesRead(
                      eventTime = r.eventTime,
                      usecase = r.usecase,
                      target = scanTarget,
                      bytes = s.evaluatedBytes
                    )
                  )
                else Nil

              List(
                DynamoDbConsumptionEvent.ReadCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = scanTarget,
                  units = readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                  consistency = r.readConsistency
                )
              ) ++ bytesReadEvents

            case TimedPutItemSample(r: PutItemRequest, s: PutItemSample) =>
              List(
                DynamoDbConsumptionEvent.WriteCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  units = writeCapacityUnitsFor(s.writtenItemBytes)
                ),
                DynamoDbConsumptionEvent.StorageBytesWritten(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytes = s.writtenItemBytes
                ),
                DynamoDbConsumptionEvent.StorageBytesDelta(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytesDelta = s.storageBytesDelta
                )
              )

            case TimedUpdateItemSample(r: UpdateItemRequest, s: UpdateItemSample) =>
              List(
                DynamoDbConsumptionEvent.WriteCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  units = writeCapacityUnitsFor(s.writtenItemBytes)
                ),
                DynamoDbConsumptionEvent.StorageBytesWritten(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytes = s.writtenItemBytes
                ),
                DynamoDbConsumptionEvent.StorageBytesDelta(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytesDelta = s.storageBytesDelta
                )
              )

            case TimedDeleteItemSample(r: DeleteItemRequest, s: DeleteItemSample) =>
              val deletedBytesEvents =
                s.deletedItemBytes.toList.map { bytes =>
                  DynamoDbConsumptionEvent.StorageBytesDeleted(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = tableTarget,
                    bytes = bytes
                  )
                }
              List(
                DynamoDbConsumptionEvent.WriteCapacityConsumed(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  units = writeCapacityUnitsFor(s.deletedItemBytes.getOrElse(0L))
                )
              ) ++ deletedBytesEvents ++ List(
                DynamoDbConsumptionEvent.StorageBytesDelta(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = tableTarget,
                  bytesDelta = s.storageBytesDelta
                )
              )
          }
        )

      requestFlow.out ~> broadcast.in
      broadcast.out(0) ~> responseFlow
      broadcast.out(1) ~> consumptionFlow
      broadcast.out(2) ~> metricFlow

      new FanOutShape3(
        requestFlow.in,
        responseFlow.out,
        consumptionFlow.out,
        metricFlow.out
      )
    }
  }
