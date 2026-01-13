package stochastacy.aws.ddb

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.{MetricEvent, ResourceConsumptionEvent}
import stochastacy.graphs.{SimTime, TableStage4}

/**
 * "Stage 4" of a Table component graph represents the DDB data-plane. This stage is only reached _after_
 * per-account throttling and provisioned capacity (and burst capacity) throttling. This is the component
 * that consumes RCUs and WCUs, and where may Table metrics are maintained and reported.
 *
 * This is the read-only (aka "stateless", "observation-only", or "query") test suite for this component
 * stage. (There are also stateful and timing-consistency test suites.)
 *
 * This test suite verifies the expected _stateless) behavior of the `GetItem` request handling behavior for this
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
      // ------------------------------------------------------------
      // Arrange
      // ------------------------------------------------------------

      // Initial table state
      val tableState = FixedTableState(
          itemCount = 0L,
          totalItemBytes = 0L)

      // Use-case behavior registry
      val behaviors: Map[Any, UseCaseSampler[TableState]] = Map(
          "get-miss" -> AlwaysMissGetItemBehavior)

      // Finite request stream
      val requestSource = Source((1 to 10).map { i =>
          GetItemRequest(
            eventTime = SimTime.of(i.toLong),
            usecase   = "get-miss")
          })

      // ------------------------------------------------------------
      // Observation sinks
      // ------------------------------------------------------------

      val responseSink = TestSink.probe[DynamoDBResponse]
      val resourceSink = TestSink.probe[ResourceConsumptionEvent]
      val metricsSink = TestSink.probe[MetricEvent]

      // ------------------------------------------------------------
      // Build + run graph, retain materialized objects (test probes)
      // ------------------------------------------------------------

      val (responseProbe, resourceProbe, metricsProbe) =
        RunnableGraph.fromGraph(
          GraphDSL.createGraph(responseSink, resourceSink, metricsSink)(
            (r, c, m) => (r, c, m)
          ) { implicit b =>
            (respSink, consSink, metrSink) =>
              import GraphDSL.Implicits._

              val table = b.add(TableStage4.componentOf(tableState, behaviors))

              requestSource ~> table.in
              table.out0     ~> respSink
              table.out1     ~> consSink
              table.out2     ~> metrSink

              ClosedShape
          }
        ).run()

      // ------------------------------------------------------------
      // Assert: responses, resources, and metrics
      // ------------------------------------------------------------

      responseProbe
        .request(10)
        .expectNextN(10)
        .foreach {
          case _: GetItemResponse => succeed
          case other =>
            fail(s"Unexpected response: $other")
        }

      // TODO: expect RCU's consumed
      resourceProbe.expectSubscription()
      resourceProbe.expectComplete()

      // TODO: figure out which metrics are deduced/observed by this component, and make sure this test
      // does indeed expect the right elements.
      metricsProbe.expectSubscription()
      metricsProbe.expectComplete()
    }
  }
