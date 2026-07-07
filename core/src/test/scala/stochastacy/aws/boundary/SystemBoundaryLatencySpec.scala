package stochastacy.aws.boundary

import org.apache.commons.rng.UniformRandomProvider
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
 * Slice 2 — transport latency.  A business crossing is delayed by a sampled
 * latency using the same intra-tick math as `TableStorageStage`; sub-tick
 * latency only shifts `intraTick`, multi-tick latency parks the element and
 * emits it once the target window opens.
 */
class SystemBoundaryLatencySpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("system-boundary-latency-spec")
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

  private def constMs(ms: Double): SystemBoundaryStage.LatencyMillisSampler = _ => ms

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

  "SystemBoundaryStage transport latency" should {

    "shift only intraTick for sub-tick ingress latency (stays in-window)" in {
      val cfg = SystemBoundaryStage.Config(ingressLatency = Some(constMs(300.0)))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), tick(2), EOT)
      val (rOut, _, _) = run(cfg, reqIn, Vector(EOT))
      val got = rOut.collect { case r: GetItemRequest => r }
      got should have size 1
      got.head.eventTime.ticks shouldBe 1L
      got.head.intraTick shouldBe (0.3 +- 1e-9)
      // causal time advanced
      (got.head.eventTime.ticks + got.head.intraTick) should be > 1.0
    }

    "advance eventTime and park for multi-tick ingress latency" in {
      val cfg = SystemBoundaryStage.Config(ingressLatency = Some(constMs(1500.0)))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), tick(2), tick(3), EOT)
      val (rOut, _, _) = run(cfg, reqIn, Vector(EOT))
      val got = rOut.collect { case r: GetItemRequest => r }
      got should have size 1
      got.head.eventTime.ticks shouldBe 2L
      got.head.intraTick shouldBe (0.5 +- 1e-9)
      // emitted in window 2: after Tick(2), before Tick(3)
      val idxReq   = rOut.indexWhere { case _: GetItemRequest => true; case _ => false }
      val idxTick2 = rOut.indexWhere { case t: TimedControlEvent.Tick => t.eventTime.ticks == 2L; case _ => false }
      val idxTick3 = rOut.indexWhere { case t: TimedControlEvent.Tick => t.eventTime.ticks == 3L; case _ => false }
      idxReq should (be > idxTick2 and be < idxTick3)
    }

    "apply egress latency on the response direction" in {
      val cfg = SystemBoundaryStage.Config(egressLatency = Some(constMs(1500.0)))
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector(tick(1), okResp(req(1)), tick(2), tick(3), EOT)
      val (_, pOut, _) = run(cfg, Vector(EOT), respIn)
      val got = pOut.collect { case r: GetItemResponse => r }
      got should have size 1
      got.head.eventTime.ticks shouldBe 2L
      got.head.intraTick shouldBe (0.5 +- 1e-9)
    }

    "be deterministic for a fixed seed" in {
      val stochastic: SystemBoundaryStage.LatencyMillisSampler = _.nextDouble() * 2000.0
      val cfg = SystemBoundaryStage.Config(ingressLatency = Some(stochastic))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector(tick(1), req(1, "a"), req(1, "b"), tick(2), tick(3), tick(4), EOT)
      val (a, _, _) = run(cfg, reqIn, Vector(EOT), seed = 99L)
      val (b, _, _) = run(cfg, reqIn, Vector(EOT), seed = 99L)
      a shouldBe b
    }

    "drop undrained parked elements at EndOfTime" in {
      val cfg = SystemBoundaryStage.Config(ingressLatency = Some(constMs(5000.0))) // +5 ticks, never opens
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), EOT)
      val (rOut, _, _) = run(cfg, reqIn, Vector(EOT))
      rOut.collect { case r: GetItemRequest => r } shouldBe empty
      rOut.last shouldBe TimedControlEvent.EndOfTime
    }

    "regress to identity when no latency is configured" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), tick(2), EOT)
      val (rOut, _, _) = run(SystemBoundaryStage.Config(), reqIn, Vector(EOT))
      rOut shouldBe reqIn
    }
  }
