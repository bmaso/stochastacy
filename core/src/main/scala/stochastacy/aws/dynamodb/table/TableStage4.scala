package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse, GetItemRequest, GetItemResponse, PutItemRequest, PutItemResponse}
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

  private case class TimedPutItemSample(req: PutItemRequest, sample: PutItemSample) extends TimedRequestSample

  def componentOf(
                   stateModel: TableState,
                   getItemBehaviors: Map[Any, UseCaseSampler[TableState]],
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
    val readCapacityUnitMultiplier = readConsistency match
      case ReadConsistency.EventuallyConsistent => BigDecimal("0.5")
      case ReadConsistency.StronglyConsistent => BigDecimal(1)

    def readCapacityUnitsFor(itemBytes: Option[Long]): BigDecimal =
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

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      // ─────────────────────────────────────────────────────────────
      // Request → sample; used internally
      // ─────────────────────────────────────────────────────────────
      val requestFlow = b.add(
        Flow[TimedElement[DynamoDBRequest]]
          .map[TimedElement[TimedRequestSample]] {
            case r: GetItemRequest =>
              val sampler = getItemBehaviors.getOrElse(
                r.usecase,
                throw new IllegalArgumentException(s"No GetItem behavior for '${r.usecase}'")
              )
              TimedGetItemSample(r, sampler.getItem(r, stateModel))

            case r: PutItemRequest =>
              val sampler = getItemBehaviors.getOrElse(
                r.usecase,
                throw new IllegalArgumentException(s"No PutItem behavior for '${r.usecase}'")
              )
              val sample = sampler.putItem(r, stateModel)
              stateModel.recordSuccessfulPut(sample.writtenItemBytes, sample.previousItemBytes)
              TimedPutItemSample(r, sample)
  
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

            case TimedPutItemSample(r: PutItemRequest, s: PutItemSample) =>
              PutItemResponse(
                eventTime = r.eventTime,
                usecase = r.usecase,
                storedItemBytes = s.writtenItemBytes,
                createdNewItem = s.createdNewItem,
                previousItemBytes = s.previousItemBytes
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
                  units = readCapacityUnitsFor(Some(s.getItemBytes)),
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
                  units = readCapacityUnitsFor(None),
                  consistency = readConsistency
                )
              )

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
