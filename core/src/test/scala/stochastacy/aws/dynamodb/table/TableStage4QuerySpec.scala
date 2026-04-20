package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbReadTarget, QueryRequest, QueryResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStage4QuerySpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-query-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component (query path)" should {
    "produce summary query responses and read consumption from evaluated bytes without mutating table state" in {
      val tableState = SummaryTableState(
        initialItemCount = 5L,
        initialTotalItemBytes = 5120L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "filtered-query",
              target = DynamoDbReadTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          tableState = tableState,
          behaviors = Map("filtered-query" -> FilteredQueryBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe QueryResponse(
        eventTime = SimTime.of(1L),
        usecase = "filtered-query",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.StronglyConsistent,
        evaluatedItemCount = 10L,
        evaluatedBytes = 8192L,
        returnedItemCount = 2L,
        returnedBytes = 1024L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(2))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(8192L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case evt: Stage4MetricEvent.QueryObserved => evt.target } shouldBe Vector(DynamoDbReadTarget.Table("orders"))
      metricEvents.collect { case evt: Stage4MetricEvent.QueryEvaluated => evt.bytes } shouldBe Vector(8192L)
      metricEvents.collect { case evt: Stage4MetricEvent.QueryReturned => evt.bytes } shouldBe Vector(1024L)

      tableState.itemCount shouldBe 5L
      tableState.totalItemBytes shouldBe 5120L
    }

    "charge reads even when a query returns no items but evaluates data" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "empty-query",
              target = DynamoDbReadTarget.Table("orders")
            )
          ),
          tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          behaviors = Map("empty-query" -> EmptyButEvaluatedQueryBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe QueryResponse(
        eventTime = SimTime.of(1L),
        usecase = "empty-query",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 4L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal("0.5"))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(4096L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case _: Stage4MetricEvent.QueryReturned => 1 } shouldBe empty
      metricEvents.collect { case evt: Stage4MetricEvent.QueryEvaluated => evt.itemCount } shouldBe Vector(4L)
    }
  }

  private def runTable(
                        requestSource: Source[QueryRequest, ?],
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

  private object FilteredQueryBehavior extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, state: TableState): QuerySample =
      QuerySample(
        evaluatedItemCount = 10L,
        evaluatedBytes = 8192L,
        returnedItemCount = 2L,
        returnedBytes = 1024L
      )

  private object EmptyButEvaluatedQueryBehavior extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, state: TableState): QuerySample =
      QuerySample(
        evaluatedItemCount = 4L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )
