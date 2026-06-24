package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{GetItemRequest, GetItemResponse}
import stochastacy.sim.{SimTime, TimedEvent}
import stochastacy.test.*

/**
 * TableStorageStage of a Table component graph represents the DDB data-plane. This stage is only reached _after_
 * per-account throttling and provisioned capacity (and burst capacity) throttling. This is the component
 * that consumes RCUs and WCUs, and where many Table metrics are maintained and reported.
 *
 * This is the read-only (aka "stateless", "observation-only", or "query") test suite for this component
 * stage. (There are also stateful and timing-consistency test suites.)
 *
 * This test suite verifies the expected _stateless_ behavior of the `GetItem` request handling for this
 * stage. `GetItem` is a read-only operation.
 *
 * The test suite separately tests _eventually consistent_ configuration from _consistent_ reads. There are two
 * distinctions in the behavior of a table configured with or without consistent reads. Eventual consistency
 * consumes fewer RCUs than Guaranteed consistency.
 *
 * This test suite verifies the entanglement of the table sampler: table state, responses, consumed resources,
 * and metrics generated during `GetItem` processing.
 */
class TableStorageStageGetItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage (read-only)" should {
    "return not-found responses for empty table GetItem requests" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source((1 to 10).map { i =>
            GetItemRequest(
              eventTime = SimTime.of(i.toLong),
              usecase = "get-miss"
            )
          }),
          tableState = FixedTableState(
            itemCount = 0L,
            totalItemBytes = 0L
          ),
          behaviors = Map("get-miss" -> AlwaysMissGetItemBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)
      val responses = responseProbe.request(10).expectNextN(10)
      responses.foreach {
        case GetItemResponse(_, "get-miss", false, None, _) => succeed
        case other =>
          fail(s"Unexpected response: $other")
      }
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(StorageConsumptionTotals())(StorageConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal(5.0)
      consumptionTotals.storageBytesRead shouldBe 0L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.EventuallyConsistent)

      val totals = drainMetricEvents(metricsProbe).foldLeft(StorageMetricTotals())(StorageMetricTotals.accumulate)
      totals.observedGets shouldBe 10
      totals.returnedItems shouldBe 0
      totals.returnedBytes shouldBe 0
    }

    "return hit responses that preserve sampled item bytes" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source((1 to 3).map { i =>
            GetItemRequest(
              eventTime = SimTime.of(i.toLong),
              usecase = "get-hit"
            )
          }),
          tableState = FixedTableState(
            itemCount = 1L,
            totalItemBytes = 512L
          ),
          behaviors = Map("get-hit" -> FixedHitGetItemBehavior(512L)),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)
      val responses = responseProbe.request(3).expectNextN(3)
      responses.foreach {
        case GetItemResponse(_, "get-hit", true, Some(512L), _) => succeed
        case other =>
          fail(s"Unexpected response: $other")
      }
      responseProbe.expectComplete()

      val consumptionTotals = drainConsumptionEvents(resourceProbe)
        .foldLeft(StorageConsumptionTotals())(StorageConsumptionTotals.accumulate)

      consumptionTotals.readCapacityUnits shouldBe BigDecimal(3.0)
      consumptionTotals.storageBytesRead shouldBe 1536L
      consumptionTotals.targets shouldBe Set(DynamoDbTarget.Table("orders"))
      consumptionTotals.consistencies shouldBe Set(ReadConsistency.StronglyConsistent)

      val totals = drainMetricEvents(metricsProbe).foldLeft(StorageMetricTotals())(StorageMetricTotals.accumulate)
      totals.observedGets shouldBe 3
      totals.returnedItems shouldBe 3
      totals.returnedBytes shouldBe 1536L
    }
  }

  private def runTable(
                        requestSource: Source[GetItemRequest, ?],
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

  def drainMetricEvents(
                         probe: TestSubscriber.Probe[_]
                       ): Vector[StorageMetricEvent] =
    val buf = Vector.newBuilder[StorageMetricEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(m: StorageMetricEvent) =>
          buf += m

        case Right(_) =>
          // Non-metric element → stop draining
          done = true

        case Left(_) =>
          // NonStream completed → stop draining
          done = true

    buf.result()

  def drainConsumptionEvents(
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

  private case class FixedHitGetItemBehavior(bytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = Some(bytes))
