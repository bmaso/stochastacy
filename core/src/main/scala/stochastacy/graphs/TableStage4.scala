package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.ddb.{DynamoDBRequest, DynamoDBResponse, GetItemRequest, GetItemResponse, GetItemSample, Stage4MetricEvent, TableState, UseCaseSampler}

/**
 * A table is implemented as a multi-stage Pekko component graph. Stage 4 of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStage4:

  private case class TimedRequestSamplePair(req: DynamoDBRequest, sample: Option[GetItemSample]) extends TimedEvent:
    override val eventTime: SimTime = req.eventTime
    override val usecase: Any = req.usecase

  def componentOf(
                   stateModel: TableState,
                   getItemBehaviors: Map[Any, UseCaseSampler[TableState]]
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse], // <-- response events in a timed stream
      TimedElement[Nothing], // <-- consumption events in a timed stream
      TimedElement[Stage4MetricEvent] // <-- metric events in a timed stream
    ],
    NotUsed
  ] = {

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      // ─────────────────────────────────────────────────────────────
      // Request → sample; used internally
      // ─────────────────────────────────────────────────────────────
      val requestFlow = b.add(
        Flow[TimedElement[DynamoDBRequest]]
          .map[TimedElement[TimedRequestSamplePair]] {
            case r: GetItemRequest =>
              val sampler = getItemBehaviors.getOrElse(
                r.usecase,
                throw new IllegalArgumentException(s"No GetItem behavior for '${r.usecase}'")
              )
              TimedRequestSamplePair(r, sampler.getItem(r, stateModel))
  
            case t: TimedControlEvent => t // ...everything else, which should just be TimedEvent elements, gets passed through
          }
      )

      val broadcast = b.add(Broadcast[TimedElement[TimedRequestSamplePair]](3))

      // ─────────────────────────────────────────────────────────────
      // sample → Response
      // ─────────────────────────────────────────────────────────────
      val responseFlow =
        b.add(
          Flow[TimedElement[TimedRequestSamplePair]].map[TimedElement[DynamoDBResponse]] {
            case t: TimedControlEvent => t

            case TimedRequestSamplePair(r: GetItemRequest, Some(s: GetItemSample)) =>
              GetItemResponse(
                eventTime = r.eventTime,
                usecase   = r.usecase,
                itemFound = true,
                itemBytes = Some(s.getItemBytes)
              )

            case TimedRequestSamplePair(r: GetItemRequest, None) =>
              GetItemResponse(
                eventTime = r.eventTime,
                usecase   = r.usecase,
                itemFound = false,
                itemBytes = None
              )
          }
        )

      // ─────────────────────────────────────────────────────────────
      // Request → Metric events
      // ─────────────────────────────────────────────────────────────
      val metricFlow =
        b.add(
          Flow[TimedElement[TimedRequestSamplePair]].mapConcat[TimedElement[Stage4MetricEvent]] {
            case t: TimedControlEvent => List(t) // propagate time events

            case TimedRequestSamplePair(r: GetItemRequest, Some(s: GetItemSample)) =>
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

            case TimedRequestSamplePair(r: GetItemRequest, None) =>
              List(
                Stage4MetricEvent.GetItemObserved(
                  eventTime = r.eventTime,
                  usecase = r.usecase
                )
              )
          }
        )

      // ─────────────────────────────────────────────────────────────
      // Resource consumption (control timing only for now)
      // ─────────────────────────────────────────────────────────────
      val consumptionFlow =
        b.add(
          Flow[TimedElement[TimedRequestSamplePair]].collect[TimedElement[Nothing]] {
            case t: TimedControlEvent => t
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
