package stochastacy.core.component

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, Keep, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.*
import stochastacy.sim.TimedControlEvent.{EndOfTime, Tick}
import stochastacy.core.stream.TickFraming

class ScheduleReleaseTransducerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ScheduleReleaseTransducerSpec")

  override def afterAll(): Unit = system.terminate()

  // --- toy domain ---
  private final case class ToyReq(eventTime: SimTime, override val intraTick: Double, usecase: Any)
      extends TimedEvent
  private final case class ToyResp(id: Int)
  private final case class ToyCons(kind: String)

  /** Emits one response at `latency`, one consumption fact at `consDelay`, and increments an
   *  Int state used as the response id (so state threading is observable). */
  private final class ToySampler(latency: Double, consDelay: Double = 0.0)
      extends RequestResponseSampler[Int, ToyReq, ToyResp, ToyCons]:
    def initialState: Int = 0
    def sample(req: ToyReq, state: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(state + 1, Scheduled(ToyResp(state), latency), List(Scheduled(ToyCons("work"), consDelay)))

  private def t(n: Long): SimTime = SimTime.of(n)

  private def run(
    sampler: RequestResponseSampler[Int, ToyReq, ToyResp, ToyCons],
    input:   Vector[TimedElement[ToyReq]]
  ): (Seq[TimedElement[Timed[ToyResp]]], Seq[TimedElement[Timed[ToyCons]]]) =
    val rng = RandomSource.KISS.create(1L)
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(
        Sink.seq[TimedElement[Timed[ToyResp]]],
        Sink.seq[TimedElement[Timed[ToyCons]]]
      )(Keep.both) { implicit b => (respSink, consSink) =>
        import GraphDSL.Implicits.*
        val src = b.add(Source(input))
        val td  = b.add(ScheduleReleaseTransducer.componentOf(sampler, rng))
        src ~> td.in
        td.out0 ~> respSink.in
        td.out1 ~> consSink.in
        ClosedShape
      }
    )
    val (rf, cf) = graph.run()
    (Await.result(rf, 5.seconds), Await.result(cf, 5.seconds))

  private def timedOnly[E](s: Seq[TimedElement[Timed[E]]]): Seq[Timed[E]] =
    s.collect { case x: Timed[E] @unchecked => x }

  /** Materialize the component and drain both streams, returning its `ComponentResult` (the Mat). */
  private def runResult(
    sampler: RequestResponseSampler[Int, ToyReq, ToyResp, ToyCons],
    input:   Vector[TimedElement[ToyReq]]
  ): ComponentResult[Int] =
    val rng = RandomSource.KISS.create(1L)
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(ScheduleReleaseTransducer.componentOf(sampler, rng)) { implicit b => comp =>
        import GraphDSL.Implicits.*
        val src = b.add(Source(input))
        src ~> comp.in
        comp.out0 ~> b.add(Sink.ignore)
        comp.out1 ~> b.add(Sink.ignore)
        ClosedShape
      }
    )
    Await.result(graph.run(), 5.seconds)

  "ScheduleReleaseTransducer" should {

    "stamp a response's eventTime/intraTick from request time + delay via the rawOffset rule" in {
      // request at tick 5, intraTick 0.7, latency 2.0 → rawOffset 2.7 → eventTime 7, intraTick 0.7
      val input = TickFraming.frame(Vector(ToyReq(t(5), 0.7, "uc")).iterator, 10).toVector
      val (resp, cons) = run(new ToySampler(latency = 2.0), input)

      val r = timedOnly(resp)
      r should have size 1
      r.head.event shouldBe ToyResp(0) // initial state threaded as id
      r.head.eventTime.ticks shouldBe 7L
      r.head.intraTick shouldBe (0.7 +- 1e-9)
      r.head.usecase shouldBe "uc"

      // consumption at delay 0 → same tick 5, intraTick 0.7
      val c = timedOnly(cons)
      c should have size 1
      c.head.event shouldBe ToyCons("work")
      c.head.eventTime.ticks shouldBe 5L
      c.head.intraTick shouldBe (0.7 +- 1e-9)
    }

    "release a response inside its own tick window (after Tick(t), before Tick(t+1))" in {
      val input = TickFraming.frame(Vector(ToyReq(t(5), 0.0, "uc")).iterator, 10).toVector
      val (resp, _) = run(new ToySampler(latency = 2.0), input) // eventTime 7

      val idxResp  = resp.indexWhere { case _: Timed[?] => true; case _ => false }
      val idxTick7 = resp.indexOf(Tick(t(7)))
      val idxTick8 = resp.indexOf(Tick(t(8)))
      idxTick7 should be < idxResp
      idxResp should be < idxTick8
    }

    "release buffered outputs in (eventTime, intraTick) order regardless of arrival order" in {
      // Same eventTime (3), two intraTicks; fed in DESCENDING intraTick order.
      // With latency 0, both land at eventTime 3 and must emerge sorted 0.1 before 0.5.
      val reqs  = Vector(ToyReq(t(3), 0.5, "uc"), ToyReq(t(3), 0.1, "uc"))
      val input = TickFraming.frame(reqs.iterator, 6).toVector
      val (resp, _) = run(new ToySampler(latency = 0.0), input)

      timedOnly(resp).map(_.intraTick) shouldBe Seq(0.1, 0.5)
    }

    "summarize post-horizon outputs in the ComponentResult residue, absent from the streams" in {
      // request at last tick 10 with latency 1.5 and consDelay 1.5 → both outputs land at
      // eventTime 11. Tick(11) drains only eventTime < 11, so both are post-horizon: summarized in
      // the residue, never emitted; the streams still end cleanly at EndOfTime.
      val input = TickFraming.frame(Vector(ToyReq(t(10), 0.0, "uc")).iterator, 10).toVector
      val (resp, cons) = run(new ToySampler(latency = 1.5, consDelay = 1.5), input)

      timedOnly(resp) shouldBe empty
      timedOnly(cons) shouldBe empty
      resp.last shouldBe EndOfTime
      cons.last shouldBe EndOfTime

      runResult(new ToySampler(latency = 1.5, consDelay = 1.5), input).residue shouldBe ResidueSummary(1L, 1L)
    }

    "expose the final sampler state as the ComponentResult's finalState" in {
      val reqs  = Vector(ToyReq(t(2), 0.0, "uc"), ToyReq(t(4), 0.0, "uc"), ToyReq(t(6), 0.0, "uc"))
      val input = TickFraming.frame(reqs.iterator, 8).toVector
      // ToySampler increments state once per request → finalState == request count.
      runResult(new ToySampler(latency = 0.0), input).finalState shouldBe 3
    }

    "carry every control event on BOTH output planes and thread state across requests" in {
      val reqs  = Vector(ToyReq(t(2), 0.0, "uc"), ToyReq(t(4), 0.0, "uc"), ToyReq(t(6), 0.0, "uc"))
      val input = TickFraming.frame(reqs.iterator, 8).toVector
      val (resp, cons) = run(new ToySampler(latency = 0.0), input)

      // both planes end with EndOfTime and carry all ticks 1..9
      resp.last shouldBe EndOfTime
      cons.last shouldBe EndOfTime
      (1L to 9L).foreach { n =>
        resp should contain(Tick(t(n)))
        cons should contain(Tick(t(n)))
      }
      // state threaded: response ids are 0,1,2 in arrival order
      timedOnly(resp).map(_.event) shouldBe Seq(ToyResp(0), ToyResp(1), ToyResp(2))
    }
  }
