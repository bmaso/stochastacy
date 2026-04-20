package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDBRequest, PartiQLQueryRequest}
import stochastacy.sim.{SimTime, TimedEvent}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class TableStage4UnsupportedPhase2ReadSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-unsupported-phase2-read-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component" should {
    "fail fast for PartiQL query requests without emitting synthetic outputs" in {
      val (responseFuture, resourceFuture, metricsFuture) =
        runTable(
          requestSource = Source.single[DynamoDBRequest](
            PartiQLQueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "partiql-usecase",
              queryText = "select * from orders"
            )
          )
        )

      val responseError = Await.result(responseFuture.failed, 3.seconds)
      val resourceError = Await.result(resourceFuture.failed, 3.seconds)
      val metricsError = Await.result(metricsFuture.failed, 3.seconds)

      responseError.getMessage should include("PartiQL query execution is not yet supported")
      resourceError.getMessage should include("PartiQL query execution is not yet supported")
      metricsError.getMessage should include("PartiQL query execution is not yet supported")
    }
  }

  private def runTable(requestSource: Source[DynamoDBRequest, ?]) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)((r, c, m) => (r, c, m)) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits.*

          val table = b.add(
            TableStage4.componentOf(
              stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
              useCaseBehaviors = Map.empty,
              tableTarget = DynamoDbTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          )

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()
