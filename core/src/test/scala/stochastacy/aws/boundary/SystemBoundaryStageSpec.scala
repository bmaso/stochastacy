package stochastacy.aws.boundary

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.boundary.DynamoDbBoundaryProtocol
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Slice 1 — skeleton.  `SystemBoundaryStage` is an identity pass-through: the
 * two flow directions forward unchanged (preserving timing, tick ordering, and
 * the EndOfTime terminal), the two directions are independent, and the
 * consumption outlet yields a single EndOfTime once both inputs finish.
 *
 * Type parameters are instantiated with the DynamoDB request/response types and
 * `CrossRegionTransferEvent` as the (unused-in-slice-1) consumption type.
 */
class SystemBoundaryStageSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("system-boundary-stage-spec")
  given mat: Materializer   = Materializer.matFromSystem

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()

  private def tick(t: Long): TimedControlEvent.Tick = TimedControlEvent.Tick(SimTime.of(t))
  private val EOT: TimedControlEvent                = TimedControlEvent.EndOfTime

  private def req(t: Long, flow: String = "primary"): GetItemRequest =
    GetItemRequest(SimTime.of(t), "test", flowId = Some(flow))

  private def okResp(r: GetItemRequest): GetItemResponse =
    GetItemResponse(
      eventTime       = r.eventTime,
      usecase         = r.usecase,
      itemFound       = true,
      itemBytes       = Some(100L),
      flowId          = r.flowId,
      clientAttempt   = r.clientAttempt,
      originalRequest = Some(r)
    )

  /** Feed each inlet a finite Source and collect each outlet via Sink.seq. */
  private def runIdentity(
    reqIn:  Vector[TimedElement[DynamoDBRequest]],
    respIn: Vector[TimedElement[DynamoDBResponse]]
  ): (Vector[TimedElement[DynamoDBRequest]],
      Vector[TimedElement[DynamoDBResponse]],
      Vector[TimedElement[CrossRegionTransferEvent]]) =
    val reqSink  = Sink.seq[TimedElement[DynamoDBRequest]]
    val respSink = Sink.seq[TimedElement[DynamoDBResponse]]
    val consSink = Sink.seq[TimedElement[CrossRegionTransferEvent]]
    val (fa, fb, fc) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(reqSink, respSink, consSink)((a, b, c) => (a, b, c)) {
        implicit builder => (rs, ps, cs) =>
          import GraphDSL.Implicits.*
          val stage = builder.add(
            SystemBoundaryStage.componentOf[DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
              DynamoDbBoundaryProtocol,
              SystemBoundaryStage.Config(),
              RandomSource.KISS.create(42L)
            )
          )
          Source(reqIn)  ~> stage.requestIn
          Source(respIn) ~> stage.responseIn
          stage.requestOut     ~> rs
          stage.responseOut    ~> ps
          stage.consumptionOut ~> cs
          ClosedShape
      }
    ).run()
    ( Await.result(fa, 5.seconds).toVector,
      Await.result(fb, 5.seconds).toVector,
      Await.result(fc, 5.seconds).toVector )

  "SystemBoundaryStage (slice 1 identity)" should {

    "forward request-direction elements unchanged, preserving timing" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1), tick(2), EOT)
      val (rOut, _, _) = runIdentity(reqIn, Vector(EOT))
      rOut shouldBe reqIn
    }

    "forward response-direction elements unchanged, preserving timing" in {
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(req(1)), tick(2), EOT)
      val (_, pOut, _) = runIdentity(Vector(EOT), respIn)
      pOut shouldBe respIn
    }

    "preserve tick ordering on the flow outlets" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), tick(2), tick(3), EOT)
      val (rOut, _, _) = runIdentity(reqIn, Vector(EOT))
      val tickTimes = rOut.collect { case t: TimedControlEvent.Tick => t.eventTime.ticks }
      tickTimes shouldBe Vector(1L, 2L, 3L)
    }

    "emit EndOfTime as the terminal element on both flow outlets" in {
      val reqIn:  Vector[TimedElement[DynamoDBRequest]]  = Vector(tick(1), req(1), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), okResp(req(1)), EOT)
      val (rOut, pOut, _) = runIdentity(reqIn, respIn)
      rOut.last shouldBe TimedControlEvent.EndOfTime
      pOut.last shouldBe TimedControlEvent.EndOfTime
    }

    "emit exactly one EndOfTime on the consumption outlet" in {
      val (_, _, cOut) = runIdentity(
        Vector(tick(1), EOT),
        Vector(tick(1), EOT)
      )
      cOut shouldBe Vector(TimedControlEvent.EndOfTime)
    }

    "forward multiple business elements per tick and terminate fully" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1, "a"), req(1, "b"), tick(2), req(2, "c"), EOT)
      val (rOut, _, _) = runIdentity(reqIn, Vector(EOT))
      rOut shouldBe reqIn
    }

    "forward the request direction independently of any response-side activity" in {
      val (reqPub, reqSub) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(
          TestSource.probe[TimedElement[DynamoDBRequest]],
          TestSink.probe[TimedElement[DynamoDBRequest]]
        )((a, b) => (a, b)) { implicit builder => (rp, rs) =>
          import GraphDSL.Implicits.*
          val stage = builder.add(
            SystemBoundaryStage.componentOf[DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
              DynamoDbBoundaryProtocol,
              SystemBoundaryStage.Config(),
              RandomSource.KISS.create(42L)
            )
          )
          rp ~> stage.requestIn
          Source.empty[TimedElement[DynamoDBResponse]] ~> stage.responseIn
          stage.requestOut     ~> rs
          stage.responseOut    ~> Sink.ignore
          stage.consumptionOut ~> Sink.ignore
          ClosedShape
        }
      ).run()

      // Response side is empty; request direction must still flow.
      reqSub.request(4)
      reqPub.sendNext(tick(1))
      reqSub.expectNext(tick(1))
      reqPub.sendNext(req(1))
      reqSub.expectNext(req(1))
      reqPub.sendNext(TimedControlEvent.EndOfTime)
      reqSub.expectNext(TimedControlEvent.EndOfTime)
      reqPub.sendComplete()
      reqSub.expectComplete()
    }
  }
