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
import stochastacy.aws.dynamodb.client.SdkRetryStrategy
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Slice 3a — loss + drop→timeout cascade.  A dropped ingress request is not
 * forwarded; a retryable Ingress timeout is injected onto the response outlet.
 * A dropped egress response is replaced by an Egress timeout built from its
 * originating request.
 */
class SystemBoundaryLossSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("system-boundary-loss-spec")
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
      eventTime = r.eventTime, usecase = r.usecase, itemFound = true, itemBytes = Some(100L),
      flowId = r.flowId, clientAttempt = r.clientAttempt, originalRequest = Some(r)
    )

  private def run(
    config: SystemBoundaryStage.Config,
    reqIn:  Vector[TimedElement[DynamoDBRequest]],
    respIn: Vector[TimedElement[DynamoDBResponse]],
    seed:   Long = 42L
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
              DynamoDbBoundaryProtocol, config, RandomSource.KISS.create(seed)
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

  private def timeouts(v: Vector[TimedElement[DynamoDBResponse]]): Vector[BoundaryTimeoutResponse] =
    v.collect { case t: BoundaryTimeoutResponse => t }

  "SystemBoundaryStage ingress loss" should {

    "drop the request and inject a retryable Ingress timeout" in {
      val cfg = SystemBoundaryStage.Config(ingressLossProbability = 1.0)
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1, "a"), req(1, "b"), tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), EOT)
      val (rOut, pOut, _) = run(cfg, reqIn, respIn)

      rOut.collect { case r: GetItemRequest => r } shouldBe empty     // nothing reached the service
      val ts = timeouts(pOut)
      ts should have size 2
      ts.foreach { t =>
        t.droppedDirection shouldBe BoundaryDropDirection.Ingress
        t.originalRequest shouldBe defined
        SdkRetryStrategy.AwsDefaultRetryable(t) shouldBe true
      }
      ts.flatMap(_.flowId).toSet shouldBe Set("a", "b")
    }

    "drop undrained timeouts at EndOfTime when the target window never opens" in {
      val cfg = SystemBoundaryStage.Config(ingressLossProbability = 1.0)
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), tick(2), tick(3), req(3), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), EOT) // never reaches window 3
      val (_, pOut, _) = run(cfg, reqIn, respIn)
      timeouts(pOut) shouldBe empty
    }

    "conserve requests: forwarded + timed-out == total, under partial loss" in {
      val cfg = SystemBoundaryStage.Config(ingressLossProbability = 0.5)
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++
          (1 to 20).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), EOT)
      val (rOut, pOut, _) = run(cfg, reqIn, respIn)
      val forwarded = rOut.collect { case r: GetItemRequest => r }.size
      val dropped   = timeouts(pOut).size
      forwarded + dropped shouldBe 20
    }

    "be deterministic for a fixed seed" in {
      val cfg = SystemBoundaryStage.Config(ingressLossProbability = 0.5)
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++
          (1 to 10).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), EOT)
      val respIn: Vector[TimedElement[DynamoDBResponse]] = Vector(tick(1), tick(2), EOT)
      val (_, a, _) = run(cfg, reqIn, respIn, seed = 7L)
      val (_, b, _) = run(cfg, reqIn, respIn, seed = 7L)
      a shouldBe b
    }
  }

  "SystemBoundaryStage egress loss" should {

    "replace the real response with an Egress timeout carrying its request" in {
      val cfg = SystemBoundaryStage.Config(egressLossProbability = 1.0)
      val orig = req(1)
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(orig), tick(2), EOT)
      val (_, pOut, _) = run(cfg, Vector(EOT), respIn)
      pOut.collect { case r: GetItemResponse => r } shouldBe empty
      val ts = timeouts(pOut)
      ts should have size 1
      ts.head.droppedDirection shouldBe BoundaryDropDirection.Egress
      ts.head.originalRequest shouldBe Some(orig)
    }
  }

  "SystemBoundaryStage with no loss" should {
    "regress to identity" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), tick(2), EOT)
      val (rOut, _, _) = run(SystemBoundaryStage.Config(), reqIn, Vector(EOT))
      rOut shouldBe reqIn
    }
  }
