package stochastacy.aws.dynamodb.usage

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, GetItemRequest, PutItemRequest}
import stochastacy.aws.dynamodb.table.*
import stochastacy.sim.{SimTime, TimedEvent}

class TableStorageStageUsageAggregationIntegrationSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-usage-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage consumption output" should {
    "fold into stable DynamoDB usage totals" in {
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

      responseProbe.request(100)
      resourceProbe.request(100)
      metricsProbe.request(100)

      val usageTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(DynamoDbUsageTotals())(DynamoDbUsageTotals.accumulate)

      usageTotals.overall shouldBe DynamoDbTargetUsageTotals(
        readCapacityUnits = BigDecimal(1.0),
        writeCapacityUnits = BigDecimal(2.0),
        storageBytesRead = 1024L,
        storageBytesWritten = 1536L,
        storageBytesDelta = 1024L
      )

      usageTotals.byTarget shouldBe Map(
        DynamoDbTarget.Table("orders") -> DynamoDbTargetUsageTotals(
          readCapacityUnits = BigDecimal(1.0),
          writeCapacityUnits = BigDecimal(2.0),
          storageBytesRead = 1024L,
          storageBytesWritten = 1536L,
          storageBytesDelta = 1024L
        )
      )
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

    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = state.averageItemBytes
      )

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long]
                                       ) extends PutItemSample
