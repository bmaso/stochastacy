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
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

class TableStorageStageTimeBasedUsageIntegrationSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-time-usage-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage consumption output" should {
    "support time-based storage usage rollups from timed storage delta events" in {
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
      val totals = DynamoDbTimeBasedUsageTotals.fromTimedEvents(timedConsumption)

      totals shouldBe DynamoDbTimeBasedUsageTotals(
        overallStorageByteTicks = BigInt(2560),
        endingOverallStorageBytes = 1024L,
        byTarget = Map(
          DynamoDbTarget.Table("orders") ->
            DynamoDbTargetTimeBasedUsageTotals(
              storageByteTicks = BigInt(2560),
              endingStorageBytes = 1024L
            )
        )
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

          val table = b.add(TableStorageStage.componentOf(tableState, behaviors, tableTarget, readConsistency))

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
