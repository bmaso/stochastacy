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
 * Slice 4 — budget dimensions & throughput limiting.  Over-budget crossings
 * delay in a bounded queue (drained as budget frees each tick) and tail-drop
 * into a timeout when the queue is full.
 */
class SystemBoundaryBudgetSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("system-boundary-budget-spec")
  given mat: Materializer   = Materializer.matFromSystem

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()

  import SystemBoundaryStage.BudgetDimension

  private def tick(t: Long): TimedControlEvent.Tick = TimedControlEvent.Tick(SimTime.of(t))
  private val EOT: TimedControlEvent                = TimedControlEvent.EndOfTime

  private def req(t: Long, flow: String = "primary"): GetItemRequest =
    GetItemRequest(SimTime.of(t), "test", flowId = Some(flow))
  private def put(t: Long, bytes: Long, flow: String = "primary"): PutItemRequest =
    PutItemRequest(SimTime.of(t), "test", itemBytes = bytes, flowId = Some(flow))
  private def okResp(t: Long, bytes: Long): GetItemResponse =
    GetItemResponse(SimTime.of(t), "test", itemFound = true, itemBytes = Some(bytes),
                    originalRequest = Some(req(t)))

  private def run(
    config: SystemBoundaryStage.Config,
    reqIn:  Vector[TimedElement[DynamoDBRequest]],
    respIn: Vector[TimedElement[DynamoDBResponse]],
    seed:   Long = 42L
  ): (Vector[TimedElement[DynamoDBRequest]], Vector[TimedElement[DynamoDBResponse]]) =
    val reqSink  = Sink.seq[TimedElement[DynamoDBRequest]]
    val respSink = Sink.seq[TimedElement[DynamoDBResponse]]
    val consSink = Sink.ignore
    val (fa, fb, _) = RunnableGraph.fromGraph(
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
    (Await.result(fa, 5.seconds).toVector, Await.result(fb, 5.seconds).toVector)

  private def reqWindows(v: Vector[TimedElement[DynamoDBRequest]]): Vector[Long] =
    v.collect { case r: DynamoDBRequest => r.eventTime.ticks }
  private def timeouts(v: Vector[TimedElement[DynamoDBResponse]]): Vector[BoundaryTimeoutResponse] =
    v.collect { case t: BoundaryTimeoutResponse => t }

  private val respTicks: Vector[TimedElement[DynamoDBResponse]] =
    Vector(tick(1), tick(2), tick(3), tick(4), EOT)

  "SystemBoundaryStage budget admission" should {

    "admit everything immediately when under budget" in {
      val cfg = SystemBoundaryStage.Config(ingressBudget = Vector(BudgetDimension("requests", 100)))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++ (1 to 5).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), EOT)
      val (rOut, pOut) = run(cfg, reqIn, respTicks)
      reqWindows(rOut) shouldBe Vector(1L, 1L, 1L, 1L, 1L)   // all admitted in window 1
      timeouts(pOut) shouldBe empty
    }

    "delay over-budget crossings and admit them as budget frees each tick" in {
      val cfg = SystemBoundaryStage.Config(ingressBudget = Vector(BudgetDimension("requests", 2)))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++ (1 to 5).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), tick(3), EOT)
      val (rOut, pOut) = run(cfg, reqIn, respTicks)
      // cap 2/tick: 2 in w1, 2 in w2, 1 in w3
      reqWindows(rOut).sorted shouldBe Vector(1L, 1L, 2L, 2L, 3L)
      timeouts(pOut) shouldBe empty
    }

    "tail-drop into Ingress timeouts when the delay queue is full" in {
      val cfg = SystemBoundaryStage.Config(
        ingressBudget  = Vector(BudgetDimension("requests", 1)),
        maxBudgetQueue = 2
      )
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++ (1 to 5).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), tick(3), EOT)
      val (rOut, pOut) = run(cfg, reqIn, respTicks)
      // w1: 1 admitted, 2 queued, 2 tail-dropped → timeouts
      val ts = timeouts(pOut)
      ts should have size 2
      ts.foreach(_.droppedDirection shouldBe BoundaryDropDirection.Ingress)
      // the admitted ones still cross (1 per tick)
      reqWindows(rOut).size should be >= 2
    }

    "enforce multiple dimensions — the tighter one binds" in {
      // requests cap 10 (loose) vs bytes cap 1000 with 400-byte writes → 2/tick.
      val cfg = SystemBoundaryStage.Config(ingressBudget =
        Vector(BudgetDimension("requests", 10), BudgetDimension("bytes", 1000)))
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++ (1 to 5).map(i => put(1, 400L, s"p$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), tick(3), EOT)
      val (rOut, _) = run(cfg, reqIn, respTicks)
      reqWindows(rOut).sorted shouldBe Vector(1L, 1L, 2L, 2L, 3L)   // bytes-limited to 2/tick
    }

    "limit the egress direction, producing Egress timeouts on overflow" in {
      val cfg = SystemBoundaryStage.Config(
        egressBudget   = Vector(BudgetDimension("requests", 1)),
        maxBudgetQueue = 1
      )
      val respIn: Vector[TimedElement[DynamoDBResponse]] =
        Vector[TimedElement[DynamoDBResponse]](tick(1)) ++
          (1 to 4).map(i => okResp(1, 100L)) ++
          Vector[TimedElement[DynamoDBResponse]](tick(2), tick(3), EOT)
      val (_, pOut) = run(cfg, Vector(EOT), respIn)
      val ts = timeouts(pOut)
      ts should not be empty
      ts.foreach(_.droppedDirection shouldBe BoundaryDropDirection.Egress)
    }

    "be a no-op when no budget is configured (Slice-3b behavior)" in {
      val reqIn: Vector[TimedElement[DynamoDBRequest]] = Vector(tick(1), req(1), tick(2), EOT)
      val (rOut, _) = run(SystemBoundaryStage.Config(), reqIn, respTicks)
      rOut shouldBe reqIn
    }

    "be deterministic for a fixed seed" in {
      val cfg = SystemBoundaryStage.Config(
        ingressBudget  = Vector(BudgetDimension("requests", 1)),
        maxBudgetQueue = 2
      )
      val reqIn: Vector[TimedElement[DynamoDBRequest]] =
        Vector[TimedElement[DynamoDBRequest]](tick(1)) ++ (1 to 6).map(i => req(1, s"r$i")) ++
          Vector[TimedElement[DynamoDBRequest]](tick(2), tick(3), EOT)
      val (ra, pa) = run(cfg, reqIn, respTicks, seed = 3L)
      val (rb, pb) = run(cfg, reqIn, respTicks, seed = 3L)
      ra shouldBe rb
      pa shouldBe pb
    }
  }
