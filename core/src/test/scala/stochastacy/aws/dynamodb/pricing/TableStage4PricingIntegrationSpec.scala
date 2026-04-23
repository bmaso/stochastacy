package stochastacy.aws.dynamodb.pricing

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, GetItemRequest, PutItemRequest}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.usage.{DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

class TableStage4PricingIntegrationSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-pricing-test")
  given Materializer = Materializer.matFromSystem

  "TableStage4 output" should {
    "support pricing from countable usage totals and time-based storage usage" in {
      val tableState = SummaryTableState(
        initialItemCount = 0L,
        initialTotalItemBytes = 0L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source(List[TimedElement[DynamoDBRequest]](
            TimedControlEvent.Tick(SimTime.of(1L)),
            PutItemRequest(
              eventTime = SimTime.of(1L),
              usecase = "stateful-table",
              itemBytes = 512L
            ),
            TimedControlEvent.Tick(SimTime.of(2L)),
            PutItemRequest(
              eventTime = SimTime.of(2L),
              usecase = "stateful-table",
              itemBytes = 1024L
            ),
            TimedControlEvent.Tick(SimTime.of(3L)),
            GetItemRequest(
              eventTime = SimTime.of(3L),
              usecase = "stateful-table"
            ),
            TimedControlEvent.Tick(SimTime.of(4L))
          )),
          tableState = tableState,
          behaviors = Map("stateful-table" -> StatefulTableBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      responseProbe.request(100)
      resourceProbe.request(100)
      metricsProbe.request(100)

      val timedConsumption = drainTimedConsumptionEvents(resourceProbe)
      val usageTotals = timedConsumption.collect {
        case evt: DynamoDbConsumptionEvent => evt
      }.foldLeft(DynamoDbUsageTotals())(DynamoDbUsageTotals.accumulate)
      val timeBasedTotals = DynamoDbTimeBasedUsageTotals.fromTimedEvents(timedConsumption)

      val breakdown = DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = usageTotals,
          timeBasedUsage = timeBasedTotals
        ),
        rates = DynamoDbPricingRates(
          readCapacityUnitPrice = BigDecimal(2.0),
          writeCapacityUnitPrice = BigDecimal(5.0),
          storagePricePerGiBSecond = BigDecimal(4.0)
        )
      )

      breakdown shouldBe DynamoDbCostBreakdown(
        readCapacityCost = BigDecimal(2.0),
        writeCapacityCost = BigDecimal(10.0),
        storageCost = BigDecimal(0.0000095367431640625),
        totalCost = BigDecimal("12.0000095367431640625")
      )
    }
  }

  private def runTable(
                        requestSource: Source[TimedElement[DynamoDBRequest], ?],
                        tableState: TableState,
                        behaviors: Map[Any, UseCaseSampler[TableState]],
                        tableTarget: DynamoDbTarget,
                        readConsistency: ReadConsistency
                      ) =
    val responseSink = TestSink.probe[TimedEvent]
    val resourceSink = TestSink.probe[TimedEvent]
    val metricsSink = TestSink.probe[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)(
        (r, c, m) => (r, c, m)
      ) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits._

          val table = b.add(TableStage4.componentOf(tableState, behaviors, tableTarget, readConsistency))

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private def drainTimedConsumptionEvents(
                                           probe: TestSubscriber.Probe[_]
                                         ): Vector[TimedElement[DynamoDbConsumptionEvent]] =
    val buf = Vector.newBuilder[TimedElement[DynamoDbConsumptionEvent]]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: DynamoDbConsumptionEvent) =>
          buf += evt

        case Right(tick: TimedControlEvent) =>
          buf += tick

        case Right(_) =>
          done = true

        case Left(_) =>
          done = true

    buf.result()

  private object StatefulTableBehavior extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): GetItemSample =
      GetItemSample(itemBytes = state.averageItemBytes)

    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = state.averageItemBytes
      )

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long]
                                       ) extends PutItemSample
