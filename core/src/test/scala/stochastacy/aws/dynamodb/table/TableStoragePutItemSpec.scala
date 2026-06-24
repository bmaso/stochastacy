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
import stochastacy.test.*

class TableStorageStagePutItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-put-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage (write path)" should {
    "mutate summary state so later responses, consumption totals, and metric totals reflect prior puts" in {
      val tableState = SummaryTableState(
        initialItemCount = 0L,
        initialTotalItemBytes = 0L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source(
            (Seq(512L, 1024L).zipWithIndex.map { case (itemBytes, idx) =>
              PutItemRequest(
                eventTime = SimTime.of(idx.toLong + 1L),
                usecase = "stateful-table",
                itemBytes = itemBytes
              ): DynamoDBRequest
            }) :+ GetItemRequest(
              eventTime = SimTime.of(3L),
              usecase = "stateful-table"
            )
          ),
          tableState = tableState,
          behaviors = Map("stateful-table" -> StatefulTableBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      val responses = responseProbe.request(3).expectNextN(3)
      responses.map(_.clearTiming) shouldBe (
        Seq(
          (512L, true, None),
          (1024L, false, Some(512L))
        ).zipWithIndex.map { case ((storedItemBytes, createdNewItem, previousItemBytes), idx) =>
          PutItemResponse(
            eventTime = SimTime.of(idx.toLong + 1L),
            usecase = "stateful-table",
            storedItemBytes = storedItemBytes,
            createdNewItem = createdNewItem,
            previousItemBytes = previousItemBytes
          )
        } :+ GetItemResponse(
          eventTime = SimTime.of(3L),
          usecase = "stateful-table",
          itemFound = true,
          itemBytes = Some(1024L)
        )
      )
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(StorageConsumptionTotals())(StorageConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal(1.0)
      consumptionTotals.writeCapacityUnits shouldBe BigDecimal(2.0)
      consumptionTotals.storageBytesRead shouldBe 1024L
      consumptionTotals.storageBytesWritten shouldBe 1536L
      consumptionTotals.storageBytesDelta shouldBe 1024L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.StronglyConsistent)

      val metricTotals = drainMetricEvents(metricsProbe)
        .foldLeft(StorageMetricTotals())(StorageMetricTotals.accumulate)

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

    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      FixedPutItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = ctx.state.averageItemBytes
      )

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long]
                                       ) extends PutItemSample
