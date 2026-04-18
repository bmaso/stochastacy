package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, GetItemRequest, GetItemResponse, PutItemRequest, PutItemResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStage4PutItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-put-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component (write path)" should {
    "mutate summary state so later responses, consumption totals, and metric totals reflect prior puts" in {
      val tableState = SummaryTableState(
        initialItemCount = 0L,
        initialTotalItemBytes = 0L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source(List[DynamoDBRequest](
            PutItemRequest(
              eventTime = SimTime.of(1L),
              usecase = "stateful-table",
              itemBytes = 512L
            ),
            PutItemRequest(
              eventTime = SimTime.of(2L),
              usecase = "stateful-table",
              itemBytes = 1024L
            ),
            GetItemRequest(
              eventTime = SimTime.of(3L),
              usecase = "stateful-table"
            )
          )),
          tableState = tableState,
          behaviors = Map("stateful-table" -> StatefulTableBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      val responses = responseProbe.request(3).expectNextN(3)
      responses shouldBe Seq(
        PutItemResponse(
          eventTime = SimTime.of(1L),
          usecase = "stateful-table",
          storedItemBytes = 512L,
          createdNewItem = true,
          previousItemBytes = None
        ),
        PutItemResponse(
          eventTime = SimTime.of(2L),
          usecase = "stateful-table",
          storedItemBytes = 1024L,
          createdNewItem = false,
          previousItemBytes = Some(512L)
        ),
        GetItemResponse(
          eventTime = SimTime.of(3L),
          usecase = "stateful-table",
          itemFound = true,
          itemBytes = Some(1024L)
        )
      )
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(Stage4ConsumptionTotals())(Stage4ConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal("1")
      consumptionTotals.writeCapacityUnits shouldBe BigDecimal("2")
      consumptionTotals.storageBytesRead shouldBe 1024L
      consumptionTotals.storageBytesWritten shouldBe 1536L
      consumptionTotals.storageBytesDelta shouldBe 1024L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.StronglyConsistent)

      val metricTotals = drainMetricEvents(metricsProbe)
        .foldLeft(Stage4MetricTotals())(Stage4MetricTotals.accumulate)

      metricTotals.observedPuts shouldBe 2
      metricTotals.storedPuts shouldBe 2
      metricTotals.storedBytes shouldBe 1536L
      metricTotals.createdItems shouldBe 1
      metricTotals.itemCountDelta shouldBe 1L
      metricTotals.tableBytesDelta shouldBe 1024L
      metricTotals.observedGets shouldBe 1
      metricTotals.returnedItems shouldBe 1
      metricTotals.returnedBytes shouldBe 1024L

      tableState.itemCount shouldBe 1L
      tableState.totalItemBytes shouldBe 1024L
      tableState.averageItemBytes shouldBe Some(1024L)
    }
  }

  private def runTable(
                        requestSource: Source[DynamoDBRequest, ?],
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

  private def drainMetricEvents(
                                 probe: TestSubscriber.Probe[_]
                               ): Vector[Stage4MetricEvent] =
    val buf = Vector.newBuilder[Stage4MetricEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: Stage4MetricEvent) =>
          buf += evt

        case Right(_) =>
          done = true

        case Left(_) =>
          done = true

    buf.result()

  private def drainConsumptionEvents(
                                      probe: TestSubscriber.Probe[_]
                                    ): Vector[DynamoDbConsumptionEvent] =
    val buf = Vector.newBuilder[DynamoDbConsumptionEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: DynamoDbConsumptionEvent) =>
          buf += evt

        case Right(_) =>
          done = true

        case Left(_) =>
          done = true

    buf.result()

  private object StatefulTableBehavior extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] =
      state.averageItemBytes.map(FixedGetItemSample.apply)

    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = state.averageItemBytes
      )

  private case class FixedGetItemSample(override val getItemBytes: Long) extends GetItemSample

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long]
                                       ) extends PutItemSample
