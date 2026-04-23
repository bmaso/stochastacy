package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, GetItemRequest, GetItemResponse, UpdateItemRequest, UpdateItemResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStage4UpdateItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-update-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component (update path)" should {
    "mutate summary state so later responses, consumption totals, and metric totals reflect prior updates" in {
      val tableState = SummaryTableState(
        initialItemCount = 1L,
        initialTotalItemBytes = 512L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source(
            Seq[DynamoDBRequest](
              UpdateItemRequest(
                eventTime = SimTime.of(1L),
                usecase = "stateful-table",
                itemBytes = 768L
              ),
              GetItemRequest(
                eventTime = SimTime.of(2L),
                usecase = "stateful-table"
              )
            )
          ),
          tableState = tableState,
          behaviors = Map("stateful-table" -> StatefulTableBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      val responses = responseProbe.request(2).expectNextN(2)
      responses shouldBe Seq(
        UpdateItemResponse(
          eventTime = SimTime.of(1L),
          usecase = "stateful-table",
          storedItemBytes = 768L,
          createdNewItem = false,
          previousItemBytes = Some(512L)
        ),
        GetItemResponse(
          eventTime = SimTime.of(2L),
          usecase = "stateful-table",
          itemFound = true,
          itemBytes = Some(768L)
        )
      )
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(Stage4ConsumptionTotals())(Stage4ConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal(1)
      consumptionTotals.writeCapacityUnits shouldBe BigDecimal(1)
      consumptionTotals.storageBytesRead shouldBe 768L
      consumptionTotals.storageBytesWritten shouldBe 768L
      consumptionTotals.storageBytesDeleted shouldBe 0L
      consumptionTotals.storageBytesDelta shouldBe 256L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.StronglyConsistent)

      val metricTotals = drainMetricEvents(metricsProbe)
        .foldLeft(Stage4MetricTotals())(Stage4MetricTotals.accumulate)

      metricTotals.observedUpdates shouldBe 1
      metricTotals.storedUpdates shouldBe 1
      metricTotals.updatedBytes shouldBe 768L
      metricTotals.createdItems shouldBe 0L
      metricTotals.itemCountDelta shouldBe 0L
      metricTotals.tableBytesDelta shouldBe 256L
      metricTotals.observedGets shouldBe 1
      metricTotals.returnedItems shouldBe 1
      metricTotals.returnedBytes shouldBe 768L

      tableState.itemCount shouldBe 1L
      tableState.totalItemBytes shouldBe 768L
      tableState.averageItemBytes shouldBe Some(768L)
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
    override def getItem(request: GetItemRequest, state: TableState): GetItemSample =
      GetItemSample(itemBytes = state.averageItemBytes)

    override def updateItem(request: UpdateItemRequest, state: TableState): UpdateItemSample =
      FixedUpdateItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = state.averageItemBytes
      )

  private case class FixedUpdateItemSample(
                                            override val writtenItemBytes: Long,
                                            override val previousItemBytes: Option[Long]
                                          ) extends UpdateItemSample
