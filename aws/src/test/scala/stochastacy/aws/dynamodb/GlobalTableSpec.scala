package stochastacy.aws.dynamodb

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Timed
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

/** Multi-region composition (phase-11 Slice 2): the replication coordinator in isolation, then the full
 *  region↔coordinator cycle — a write replicates A→B (rWCU-billed) after the link lag **without deadlock** (the
 *  N-way proof the Slice-1 self-loop foreshadowed), and a single-region Global Table matches a standalone table. */
class GlobalTableSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("GlobalTableSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)
  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private val behavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private def cfg     = DynamoDbTable.Config(initialState = TableSummaryState.empty, behavior = behavior, latency = latency)
  private val model   = ReplicationModel(default = Some(LogNormalSampler.constant(math.log(1.5), 0.0))) // → lag 1 tick
  private def rng: UniformRandomProvider = RandomSource.KISS.create(7L)

  "The replication coordinator, in isolation," should {
    "release a tap to its peer after the link lag, with transfer + latency, tracking pending depth" in {
      val in: Vector[TimedElement[Timed[TaggedTap]]] = Vector(
        TimedControlEvent.Tick(SimTime.of(1)),
        Timed(TaggedTap("A", ReplicationWrite(PutItemRequest(200L))), SimTime.of(1), 0.0, "x"),
        TimedControlEvent.Tick(SimTime.of(2)),
        TimedControlEvent.Tick(SimTime.of(3)),
        TimedControlEvent.EndOfTime
      )
      val out = await(Source(in).via(ReplicationCoordinator.flow(Vector("A", "B"), model, rng)).runWith(Sink.seq))
      val biz = out.collect { case t: Timed[ReplicationOutput] @unchecked => t }
      biz.collect { case Timed(ReplicationOutput.ReplicatedWriteFor(d, w), et, _, _) => (d, w, et.ticks) } shouldBe
        List(("B", ReplicationWrite(PutItemRequest(200L)), 2L)) // lag 1 → applies at tick 2
      biz.collect { case Timed(ReplicationOutput.Transfer(e), _, _, _) => e } shouldBe
        List(CrossRegionTransferEvent("A", "B", 200L))
      biz.collect { case Timed(ReplicationOutput.Latency(s), _, _, _) => s.latencyTicks } shouldBe List(1L)
      val pendAB = biz.collect { case Timed(ReplicationOutput.Pending(PendingReplicationSample("A", "B", n)), et, _, _) => (et.ticks, n) }
      pendAB should contain (1L -> 1L) // queued at tick 1
      pendAB should contain (2L -> 0L) // released by tick 2
    }
  }

  "A two-region Global Table" should {
    "replicate a write A→B (rWCU-billed) after the link lag, without deadlock" in {
      val framedA = TickFraming.frame(Iterator(Timed[DynamoDbRequest](PutItemRequest(1024L), SimTime.of(1), 0.5, "a")), 5L).toVector
      val framedB = TickFraming.frame(Iterator.empty[Timed[DynamoDbRequest]], 5L).toVector

      val gt          = GlobalTable.componentOf(GlobalTable.Config(Map("A" -> cfg, "B" -> cfg), model), rng)
      val metricsSink = Sink.seq[TimedElement[Timed[ReplicationOutput]]]
      val (states, metricsF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(gt, metricsSink)((m1, m2) => (m1, m2)) { implicit b => (g, ms) =>
          import GraphDSL.Implicits.*
          b.add(Source(framedA)) ~> g.requestIn("A")
          b.add(Source(framedB)) ~> g.requestIn("B")
          g.responseOut("A")    ~> b.add(Sink.ignore); g.consumptionOut("A") ~> b.add(Sink.ignore)
          g.responseOut("B")    ~> b.add(Sink.ignore); g.consumptionOut("B") ~> b.add(Sink.ignore)
          g.metricsOut          ~> ms
          ClosedShape
        }
      ).run()

      await(states("A")).finalState.base.totalItemBytes shouldBe 1024L // A's local write
      await(states("B")).finalState.base.totalItemBytes shouldBe 1024L // A's write, replicated + applied at B
      val metrics = await(metricsF).collect { case t: Timed[ReplicationOutput] @unchecked => t.event }
      metrics.collect { case ReplicationOutput.Transfer(CrossRegionTransferEvent("A", "B", bytes)) => bytes } shouldBe List(1024L)
    }

    "a single-region Global Table matches a standalone table's final state" in {
      val framed = TickFraming.frame(
        Iterator(Timed[DynamoDbRequest](PutItemRequest(500L), SimTime.of(1), 0.5, "a"),
                 Timed[DynamoDbRequest](PutItemRequest(700L), SimTime.of(2), 0.5, "a")), 4L).toVector

      val standalone = RunnableGraph.fromGraph(GraphDSL.createGraph(DynamoDbTable.componentOf(cfg, rng)) { implicit b => t =>
        import GraphDSL.Implicits.*
        b.add(Source(framed)) ~> t.in; t.out0 ~> b.add(Sink.ignore); t.out1 ~> b.add(Sink.ignore); ClosedShape
      }).run()

      val gt = GlobalTable.componentOf(GlobalTable.Config(Map("A" -> cfg), model), rng)
      val gtStates = RunnableGraph.fromGraph(GraphDSL.createGraph(gt) { implicit b => g =>
        import GraphDSL.Implicits.*
        b.add(Source(framed)) ~> g.requestIn("A")
        g.responseOut("A") ~> b.add(Sink.ignore); g.consumptionOut("A") ~> b.add(Sink.ignore); g.metricsOut ~> b.add(Sink.ignore)
        ClosedShape
      }).run()

      await(gtStates("A")).finalState.base.totalItemBytes shouldBe await(standalone).finalState.base.totalItemBytes
    }
  }
