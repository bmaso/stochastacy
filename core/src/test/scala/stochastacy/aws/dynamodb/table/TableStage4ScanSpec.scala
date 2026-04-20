package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbReadTarget, ScanRequest, ScanResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStage4ScanSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-scan-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component (scan path)" should {
    "produce summary scan responses and read consumption from evaluated bytes without mutating table state" in {
      val tableState = SummaryTableState(
        initialItemCount = 7L,
        initialTotalItemBytes = 7168L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "filtered-scan",
              target = DynamoDbReadTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          tableState = tableState,
          behaviors = Map("filtered-scan" -> FilteredScanBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "filtered-scan",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.StronglyConsistent,
        evaluatedItemCount = 16L,
        evaluatedBytes = 12288L,
        returnedItemCount = 5L,
        returnedBytes = 2048L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(3))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(12288L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case evt: Stage4MetricEvent.ScanObserved => evt.target } shouldBe Vector(DynamoDbReadTarget.Table("orders"))
      metricEvents.collect { case evt: Stage4MetricEvent.ScanEvaluated => evt.bytes } shouldBe Vector(12288L)
      metricEvents.collect { case evt: Stage4MetricEvent.ScanReturned => evt.bytes } shouldBe Vector(2048L)

      tableState.itemCount shouldBe 7L
      tableState.totalItemBytes shouldBe 7168L
    }

    "charge reads even when a scan returns no items but evaluates data" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "empty-scan",
              target = DynamoDbReadTarget.Table("orders")
            )
          ),
          tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          behaviors = Map("empty-scan" -> EmptyButEvaluatedScanBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "empty-scan",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 6L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal("0.5"))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(4096L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case _: Stage4MetricEvent.ScanReturned => 1 } shouldBe empty
      metricEvents.collect { case evt: Stage4MetricEvent.ScanEvaluated => evt.itemCount } shouldBe Vector(6L)
    }
  }

  private def runTable(
                        requestSource: Source[ScanRequest, ?],
                        tableState: TableState,
                        behaviors: Map[Any, UseCaseSampler[TableState]],
                        tableTarget: DynamoDbTarget,
                        readConsistency: ReadConsistency
                      ) =
    val responseSink = TestSink.probe[TimedEvent]
    val resourceSink = TestSink.probe[TimedEvent]
    val metricsSink = TestSink.probe[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)((r, c, m) => (r, c, m)) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits.*

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

  private object FilteredScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, state: TableState): ScanSample =
      ScanSample(
        evaluatedItemCount = 16L,
        evaluatedBytes = 12288L,
        returnedItemCount = 5L,
        returnedBytes = 2048L
      )

  private object EmptyButEvaluatedScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, state: TableState): ScanSample =
      ScanSample(
        evaluatedItemCount = 6L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )
