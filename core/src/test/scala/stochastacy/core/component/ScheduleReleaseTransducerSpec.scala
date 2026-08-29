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

  // --- toy domain (timeless payloads; timing lives on the Timed envelope) ---
  private final case class ToyReq()
  private final case class ToyResp(id: Int)
  private final case class ToyCons(kind: String)

  /** Emits one response at `latency`, one consumption fact at `consDelay`, and increments an
   *  Int state used as the response id (so state threading is observable). */
  private final class ToySampler(latency: Double, consDelay: Double = 0.0)
      extends ComponentSampler[Int, ToyReq, ToyResp, ToyCons]:
    def initialState: Int = 0
    def sample(in: ToyReq, state: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(state + 1, Scheduled(ToyResp(state), latency), List(Scheduled(ToyCons("work"), consDelay)))

  /** Emits one `ToyCons("tick-<t>")` at each tick boundary (delay 0); its `sample` emits a response plus a
   *  `ToyCons("req")` at delay 0. Lets us observe boundary facts landing in a tick's own window, ordered
   *  ahead of that tick's request-driven facts. */
  private final class ToyTickSampler extends ComponentSampler[Int, ToyReq, ToyResp, ToyCons]:
    def initialState: Int = 0
    def sample(in: ToyReq, state: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(state, Scheduled(ToyResp(state), 0.0), List(Scheduled(ToyCons("req"), 0.0)))
    override def onTick(tick: Long, state: Int): TickEmission[Int, ToyCons] =
      TickEmission(state, List(Scheduled(ToyCons(s"tick-$tick"), 0.0)))

  private def t(n: Long): SimTime = SimTime.of(n)
  private def req(tick: Long, intra: Double = 0.0, uc: Any = "uc"): Timed[ToyReq] =
    Timed(ToyReq(), t(tick), intra, uc)

  private def run(
    sampler: ComponentSampler[Int, ToyReq, ToyResp, ToyCons],
    input:   Vector[TimedElement[Timed[ToyReq]]]
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
    sampler: ComponentSampler[Int, ToyReq, ToyResp, ToyCons],
    input:   Vector[TimedElement[Timed[ToyReq]]]
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

    "release a component's tick-boundary facts inside that tick's window, ordered ahead of request facts" in {
      // Two real ticks (1, 2), each with a request at intraTick 0.5; framing adds the flush Tick(3).
      val input = TickFraming.frame(Vector(req(1, 0.5), req(2, 0.5)).iterator, 2).toVector
      val (resp, cons) = run(new ToyTickSampler, input)

      // The boundary fact for tick t is stamped (t, 0); it lands in tick t's window (released at Tick(t+1)),
      // ordered first (intraTick 0) ahead of the request's fact at intraTick 0.5.
      val c = timedOnly(cons)
      c.map(x => (x.event, x.eventTime.ticks, x.intraTick)) shouldBe Seq(
        (ToyCons("tick-1"), 1L, 0.0), (ToyCons("req"), 1L, 0.5),
        (ToyCons("tick-2"), 2L, 0.0), (ToyCons("req"), 2L, 0.5)
      )
      // the boundary fact is stamped with the tick-boundary usecase (no triggering request)
      c.head.usecase shouldBe TickBoundaryUsecase
      // request/response 1:1 is untouched — one response per request, no extra forward outputs
      timedOnly(resp) should have size 2

      // the flush tick's boundary fact (tick-3, beyond the horizon) is never released — it is residue
      c.map(_.event) should not contain ToyCons("tick-3")
      runResult(new ToyTickSampler, input).residue.consumptions shouldBe 1L
    }

    "stamp a response's eventTime/intraTick from request time + delay via the rawOffset rule" in {
      // request at tick 5, intraTick 0.7, latency 2.0 → rawOffset 2.7 → eventTime 7, intraTick 0.7
      val input = TickFraming.frame(Vector(req(5, 0.7)).iterator, 10).toVector
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
      val input = TickFraming.frame(Vector(req(5)).iterator, 10).toVector
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
      val reqs  = Vector(req(3, 0.5), req(3, 0.1))
      val input = TickFraming.frame(reqs.iterator, 6).toVector
      val (resp, _) = run(new ToySampler(latency = 0.0), input)

      timedOnly(resp).map(_.intraTick) shouldBe Seq(0.1, 0.5)
    }

    "summarize post-horizon outputs in the ComponentResult residue, absent from the streams" in {
      // request at last tick 10 with latency 1.5 and consDelay 1.5 → both outputs land at
      // eventTime 11. Tick(11) drains only eventTime < 11, so both are post-horizon: summarized in
      // the residue, never emitted; the streams still end cleanly at EndOfTime.
      val input = TickFraming.frame(Vector(req(10)).iterator, 10).toVector
      val (resp, cons) = run(new ToySampler(latency = 1.5, consDelay = 1.5), input)

      timedOnly(resp) shouldBe empty
      timedOnly(cons) shouldBe empty
      resp.last shouldBe EndOfTime
      cons.last shouldBe EndOfTime

      runResult(new ToySampler(latency = 1.5, consDelay = 1.5), input).residue shouldBe ResidueSummary(1L, 1L)
    }

    "expose the final sampler state as the ComponentResult's finalState" in {
      val reqs  = Vector(req(2), req(4), req(6))
      val input = TickFraming.frame(reqs.iterator, 8).toVector
      // ToySampler increments state once per request → finalState == request count.
      runResult(new ToySampler(latency = 0.0), input).finalState shouldBe 3
    }

    "carry every control event on BOTH output planes and thread state across requests" in {
      val reqs  = Vector(req(2), req(4), req(6))
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

    "call onTick at each tick boundary, before that tick's requests are sampled" in {
      // A per-tick counter: sample emits the current count and increments; onTick resets it to 0.
      val counting = new ComponentSampler[Int, ToyReq, ToyResp, ToyCons]:
        def initialState: Int = 0
        def sample(in: ToyReq, state: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
          Emission(state + 1, Scheduled(ToyResp(state), 0.0), Nil)
        override def onTick(tick: Long, state: Int): TickEmission[Int, ToyCons] = TickEmission(0, Nil)

      val reqs  = Vector(req(1), req(1), req(1), req(2), req(2)) // 3 in tick 1, 2 in tick 2
      val input = TickFraming.frame(reqs.iterator, 3).toVector
      val (resp, _) = run(counting, input)

      // Without the reset the second tick would continue 3,4; the reset restarts it at 0.
      timedOnly(resp).map(_.event) shouldBe Seq(ToyResp(0), ToyResp(1), ToyResp(2), ToyResp(0), ToyResp(1))
    }
  }
