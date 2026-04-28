package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DeleteItemRequest, DeleteItemResponse, DynamoDBRequest, GetItemRequest, GetItemResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStorageStageDeleteItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-delete-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage (delete path)" should {
    "mutate summary state so later responses, consumption totals, and metric totals reflect prior deletes" in {
      val tableState = SummaryTableState(
        initialItemCount = 1L,
        initialTotalItemBytes = 768L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source(
            Seq[DynamoDBRequest](
              DeleteItemRequest(
                eventTime = SimTime.of(1L),
                usecase = "stateful-table"
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
        DeleteItemResponse(
          eventTime = SimTime.of(1L),
          usecase = "stateful-table",
          deletedItemBytes = Some(768L)
        ),
        GetItemResponse(
          eventTime = SimTime.of(2L),
          usecase = "stateful-table",
          itemFound = false,
          itemBytes = None
        )
      )
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(StorageConsumptionTotals())(StorageConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal(1)
      consumptionTotals.writeCapacityUnits shouldBe BigDecimal(1)
      consumptionTotals.storageBytesRead shouldBe 0L
      consumptionTotals.storageBytesWritten shouldBe 0L
      consumptionTotals.storageBytesDeleted shouldBe 768L
      consumptionTotals.storageBytesDelta shouldBe -768L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.StronglyConsistent)

      val metricTotals = drainMetricEvents(metricsProbe)
        .foldLeft(StorageMetricTotals())(StorageMetricTotals.accumulate)

      metricTotals.observedDeletes shouldBe 1
      metricTotals.deletedItems shouldBe 1
      metricTotals.deletedBytes shouldBe 768L
      metricTotals.itemCountDelta shouldBe -1L
      metricTotals.tableBytesDelta shouldBe -768L
      metricTotals.observedGets shouldBe 1
      metricTotals.returnedItems shouldBe 0L
      metricTotals.returnedBytes shouldBe 0L

      tableState.itemCount shouldBe 0L
      tableState.totalItemBytes shouldBe 0L
      tableState.averageItemBytes shouldBe None
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

          val table = b.add(TableStorageStage.componentOf(tableState, behaviors, tableTarget, readConsistency))

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private def drainMetricEvents(
                                 probe: TestSubscriber.Probe[_]
                               ): Vector[StorageMetricEvent] =
    val buf = Vector.newBuilder[StorageMetricEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: StorageMetricEvent) =>
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
    override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = ctx.state.averageItemBytes)

    override def deleteItem(request: DeleteItemRequest, ctx: SamplerContext[TableState]): DeleteItemSample =
      FixedDeleteItemSample(deletedItemBytes = ctx.state.averageItemBytes)

  private case class FixedDeleteItemSample(
                                            override val deletedItemBytes: Option[Long]
                                          ) extends DeleteItemSample
