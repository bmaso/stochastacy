package stochastacy.aws.ddb

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.graphs.{SimTime, TableStage4}

class TableStage4Spec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-stage4-test")
  given Materializer = Materializer.matFromSystem

  "Stage 4 Table component" should {

    "return not-found responses for empty table GetItem requests" in {

      // --- Table state ---
      val tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0)

      // --- Use-case registry ---
      val behaviors =
        Map[Any, UseCaseSampler[TableState]]("get-miss" -> AlwaysMissGetItemBehavior)

      // --- Test inputs ---
      val requests =
        (1 to 10).map { i =>
          GetItemRequest(SimTime.of(i.toLong), usecase = "get-miss")
        }

      val requestSource = Source(requests)

      val responseSink = TestSink.probe[DynamoDBResponse]

      // --- Build graph ---
      val responseProbe =
        RunnableGraph.fromGraph(
          GraphDSL.createGraph(responseSink) { implicit b =>
            respSink =>
              import GraphDSL.Implicits._
  
              val table = b.add(TableStage4.stage4Table(tableState, behaviors))
  
              requestSource ~> table.in
              table.out ~> respSink
  
              ClosedShape
          }
        ).run()

      // --- Assertions: responses ---
      responseProbe
        .request(10)
        .expectNextN(10)
        .foreach {
          case _: GetItemResponse => succeed
          case other => fail(s"Unexpected response: $other")
        }
    }
  }