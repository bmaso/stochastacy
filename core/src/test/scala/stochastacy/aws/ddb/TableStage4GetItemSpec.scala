package stochastacy.aws.ddb

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.{MetricEvent, ResourceConsumptionEvent}
import stochastacy.graphs.{SimTime, TableStage4, TimedEvent}
import stochastacy.test.*

/**
 * "Stage 4" of a Table component graph represents the DDB data-plane. This stage is only reached _after_
 * per-account throttling and provisioned capacity (and burst capacity) throttling. This is the component
 * that consumes RCUs and WCUs, and where may Table metrics are maintained and reported.
 *
 * This is the read-only (aka "stateless", "observation-only", or "query") test suite for this component
 * stage. (There are also stateful and timing-consistency test suites.)
 *
 * This test suite verifies the expected _stateless_ behavior of the `GetItem` request handling behavior for this
 * stage. `GetItem` is a read-only operation.
 *
 * The test suite separately tests _eventually consistent_ configuration from _consistent_ reads. There are two
 * distinctions in the behavior of a table configured with or without consistent reads. Eventual consistency
 * consumes fewer RCUs than Guaranteed consistency.
 *
 * This test suite verifies the entanglement of the table sampler. table state, responses, consumed resources,
 * and metrics generated during `GetItem` processing.
 */
class TableStage4GetItemSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component (read-only)" should {
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
          behaviors = Map("get-miss" -> AlwaysMissGetItemBehavior)
        )

      resourceProbe.request(100)
      metricsProbe.request(100)
      val responses = responseProbe.request(10).expectNextN(10)
      responses.foreach {
        case GetItemResponse(_, "get-miss", false, None) => succeed
        case other =>
          fail(s"Unexpected response: $other")
      }
      responseProbe.expectComplete()

      resourceProbe.expectComplete()

      val totals = drainMetricEvents(metricsProbe).foldLeft(Stage4MetricTotals())(Stage4MetricTotals.accumulate)
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
          behaviors = Map("get-hit" -> FixedHitGetItemBehavior(512L))
        )

      resourceProbe.request(100)
      metricsProbe.request(100)
      val responses = responseProbe.request(3).expectNextN(3)
      responses.foreach {
        case GetItemResponse(_, "get-hit", true, Some(512L)) => succeed
        case other =>
          fail(s"Unexpected response: $other")
      }
      responseProbe.expectComplete()

      resourceProbe.expectComplete()

      val totals = drainMetricEvents(metricsProbe).foldLeft(Stage4MetricTotals())(Stage4MetricTotals.accumulate)
      totals.observedGets shouldBe 3
      totals.returnedItems shouldBe 3
      totals.returnedBytes shouldBe 1536L
    }
  }

  private def runTable(
                        requestSource: Source[GetItemRequest, ?],
                        tableState: TableState,
                        behaviors: Map[Any, UseCaseSampler[TableState]]
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

          val table = b.add(TableStage4.componentOf(tableState, behaviors))

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  def drainMetricEvents(
                         probe: TestSubscriber.Probe[_]
                       ): Vector[Stage4MetricEvent] =
    val buf = Vector.newBuilder[Stage4MetricEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(m: Stage4MetricEvent) =>
          buf += m

        case Right(_) =>
          // Non-metric element → stop draining
          done = true

        case Left(_) =>
          // NonStream completed → stop draining
          done = true

    buf.result()

  private case class FixedGetItemSample(override val getItemBytes: Long) extends GetItemSample

  private case class FixedHitGetItemBehavior(bytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] =
      Some(FixedGetItemSample(bytes))
