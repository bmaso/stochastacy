package stochastacy.aws.boundary

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
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
 * Slice 3b — metering.  Successful crossings emit consumption events on a
 * response-paced consumption stream: response-direction events in their window,
 * request-direction events parked and drained when the response clock reaches
 * their window.
 */
class SystemBoundaryMeteringSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("system-boundary-metering-spec")
  given mat: Materializer   = Materializer.matFromSystem

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()

  private def tick(t: Long): TimedControlEvent.Tick = TimedControlEvent.Tick(SimTime.of(t))
  private val EOT: TimedControlEvent                = TimedControlEvent.EndOfTime

  private def req(t: Long, flow: String = "primary"): GetItemRequest =
    GetItemRequest(SimTime.of(t), "test", flowId = Some(flow))

  private def okResp(t: Long, bytes: Long): GetItemResponse =
    GetItemResponse(SimTime.of(t), "test", itemFound = true, itemBytes = Some(bytes),
                    originalRequest = Some(req(t)))

  private val SrcRegion = "us-east-1"
  private val DstRegion = "us-west-2"

  // Egress policy: one transfer event per response, bytes = the response's itemBytes.
  private val egressPolicy: DynamoDBResponse => Seq[CrossRegionTransferEvent] = {
    case r: GetItemResponse =>
      Seq(CrossRegionTransferEvent(r.eventTime, r.usecase, SrcRegion, DstRegion, "DynamoDB",
        r.itemBytes.getOrElse(0L)))
    case _ => Seq.empty
  }

  // Ingress policy: one transfer event per request, fixed 50 bytes.
  private val ingressPolicy: DynamoDBRequest => Seq[CrossRegionTransferEvent] =
    r => Seq(CrossRegionTransferEvent(r.eventTime, r.usecase, SrcRegion, DstRegion, "DynamoDB", 50L))

  private def run(
    config:  SystemBoundaryStage.Config,
    reqIn:   Vector[TimedElement[DynamoDBRequest]],
    respIn:  Vector[TimedElement[DynamoDBResponse]],
    ingress: DynamoDBRequest  => Seq[CrossRegionTransferEvent] = _ => Seq.empty,
    egress:  DynamoDBResponse => Seq[CrossRegionTransferEvent] = _ => Seq.empty,
    seed:    Long = 42L
  ): Vector[TimedElement[CrossRegionTransferEvent]] =
    val reqSink  = Sink.ignore
    val respSink = Sink.ignore
    val consSink = Sink.seq[TimedElement[CrossRegionTransferEvent]]
    val (_, _, fc) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(reqSink, respSink, consSink)((a, b, c) => (a, b, c)) {
        implicit builder => (rs, ps, cs) =>
          import GraphDSL.Implicits.*
          val stage = builder.add(
            SystemBoundaryStage.componentOf[DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
              DynamoDbBoundaryProtocol, config, RandomSource.KISS.create(seed),
              ingressMetering = ingress, egressMetering = egress
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
    Await.result(fc, 5.seconds).toVector

  private def events(v: Vector[TimedElement[CrossRegionTransferEvent]]): Vector[CrossRegionTransferEvent] =
    v.collect { case e: CrossRegionTransferEvent => e }

  "SystemBoundaryStage metering" should {

    "emit an egress consumption event per response, in its window" in {
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(1, 100L), tick(2), okResp(2, 250L), tick(3), EOT)
      val cOut = run(SystemBoundaryStage.Config(), Vector(EOT), respIn, egress = egressPolicy)
      val es = events(cOut)
      es.map(_.bytes) shouldBe Vector(100L, 250L)
      es.map(_.eventTime.ticks) shouldBe Vector(1L, 2L)
      es.foreach { e => e.sourceRegion shouldBe SrcRegion; e.destinationRegion shouldBe DstRegion }
    }

    "park and drain ingress consumption events on the response-paced clock" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1, "a"), tick(2), req(2, "b"), tick(3), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), tick(3), EOT)
      val cOut = run(SystemBoundaryStage.Config(), reqIn, respIn, ingress = ingressPolicy)
      val es = events(cOut)
      es.map(_.bytes) shouldBe Vector(50L, 50L)
      es.map(_.eventTime.ticks) shouldBe Vector(1L, 2L)
    }

    "meter both directions onto one monotonic stream" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1), tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(1, 100L), tick(2), EOT)
      val cOut = run(SystemBoundaryStage.Config(), reqIn, respIn, ingress = ingressPolicy, egress = egressPolicy)
      val es = events(cOut)
      es.map(_.bytes).sum shouldBe 150L   // 50 ingress + 100 egress
    }

    "not meter dropped crossings (successful-only)" in {
      val cfg = SystemBoundaryStage.Config(ingressLossProbability = 1.0)
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1, "a"), req(1, "b"), tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), tick(3), EOT)
      val cOut = run(cfg, reqIn, respIn, ingress = ingressPolicy)
      events(cOut) shouldBe empty
    }

    "frame the stream: ticks, then EndOfTime terminal" in {
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(1, 100L), tick(2), EOT)
      val cOut = run(SystemBoundaryStage.Config(), Vector(EOT), respIn, egress = egressPolicy)
      cOut.last shouldBe TimedControlEvent.EndOfTime
      val tickTimes = cOut.collect { case t: TimedControlEvent.Tick => t.eventTime.ticks }
      tickTimes shouldBe Vector(1L, 2L)
      // the window-1 event follows Tick(1) and precedes Tick(2)
      val idxEvent = cOut.indexWhere { case _: CrossRegionTransferEvent => true; case _ => false }
      val idxTick2 = cOut.indexWhere { case t: TimedControlEvent.Tick => t.eventTime.ticks == 2L; case _ => false }
      idxEvent should be < idxTick2
    }

    "emit only tick framing when no metering is configured" in {
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), EOT)
      val cOut = run(SystemBoundaryStage.Config(), Vector(EOT), respIn)
      cOut shouldBe Vector(tick(1), tick(2), TimedControlEvent.EndOfTime)
    }

    "be deterministic for a fixed seed" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1), tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(1, 100L), tick(2), EOT)
      val a = run(SystemBoundaryStage.Config(), reqIn, respIn, ingress = ingressPolicy, egress = egressPolicy, seed = 5L)
      val b = run(SystemBoundaryStage.Config(), reqIn, respIn, ingress = ingressPolicy, egress = egressPolicy, seed = 5L)
      a shouldBe b
    }
  }
