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
import stochastacy.core.component.gate.{FlatThrottleGate, LatencyGate, TokenBucketGate}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.*
import stochastacy.sim.TimedControlEvent.EndOfTime

class InterfaceSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("InterfaceSpec")
  override def afterAll(): Unit = system.terminate()

  // --- toy domain ---
  private final case class ToyReq(id: Int)
  private final case class ToyResp(id: Int)
  private final case class ToyCons(kind: String)

  /** Downstream: echoes the request id back as a response (at `latency`) and records one consumption
   *  fact. `latency` defaults to 0 for the framing/throttle tests; the latency-accumulation tests give
   *  it a known non-zero value. */
  private final class EchoSampler(latency: Double = 0.0) extends ComponentSampler[Unit, ToyReq, ToyResp, ToyCons]:
    def initialState: Unit = ()
    def sample(in: ToyReq, state: Unit, rng: UniformRandomProvider): Emission[Unit, ToyResp, ToyCons] =
      Emission((), Scheduled(ToyResp(in.id), latency), List(Scheduled(ToyCons("work"), 0.0)))

  private def req(tick: Long, id: Int): Timed[ToyReq] = Timed(ToyReq(id), SimTime.of(tick), 0.0, "uc")

  private type ToyComponent = org.apache.pekko.stream.Graph[
    org.apache.pekko.stream.FanOutShape2[TimedElement[Timed[ToyReq]], TimedElement[Timed[ToyResp]], TimedElement[Timed[ToyCons]]],
    ?
  ]

  /** Run an arbitrary Req→Resp component, returning just its responses. */
  private def responsesOf(component: ToyComponent, input: Vector[TimedElement[Timed[ToyReq]]]): Seq[Timed[ToyResp]] =
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.seq[TimedElement[Timed[ToyResp]]]) { implicit b => respSink =>
        import GraphDSL.Implicits.*
        val src = b.add(Source(input))
        val c   = b.add(component)
        src ~> c.in
        c.out0 ~> respSink.in
        c.out1 ~> b.add(Sink.ignore)
        ClosedShape
      }
    )
    timedOnly(Await.result(graph.run(), 5.seconds))

  private def echo(latency: Double): ToyComponent =
    ScheduleReleaseTransducer.componentOf(new EchoSampler(latency), RandomSource.KISS.create(2L))

  private def run(
    cap:   Int,
    input: Vector[TimedElement[Timed[ToyReq]]]
  ): (Seq[TimedElement[Timed[ToyResp]]], Seq[TimedElement[Timed[ToyCons]]]) =
    val downstream = ScheduleReleaseTransducer.componentOf(new EchoSampler, RandomSource.KISS.create(2L))
    val gate       = new FlatThrottleGate[ToyReq, ToyResp](cap, ToyResp(-1))
    val wrapped    = Interface.wrap(downstream, gate, RandomSource.KISS.create(3L))

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(
        Sink.seq[TimedElement[Timed[ToyResp]]],
        Sink.seq[TimedElement[Timed[ToyCons]]]
      )(Keep.both) { implicit b => (respSink, consSink) =>
        import GraphDSL.Implicits.*
        val src = b.add(Source(input))
        val w   = b.add(wrapped)
        src ~> w.in
        w.out0 ~> respSink.in
        w.out1 ~> consSink.in
        ClosedShape
      }
    )
    val (rf, cf) = graph.run()
    (Await.result(rf, 5.seconds), Await.result(cf, 5.seconds))

  private def timedOnly[E](s: Seq[TimedElement[Timed[E]]]): Seq[Timed[E]] =
    s.collect { case x: Timed[E] @unchecked => x }

  // 4 requests in tick 1, 3 in tick 2; a capacity of 2 admits the first two of each tick.
  private val input = TickFraming.frame(
    Vector(req(1, 10), req(1, 11), req(1, 12), req(1, 13), req(2, 20), req(2, 21), req(2, 22)).iterator, 3
  ).toVector

  "Interface.wrap" should {

    "return exactly one response per request — admitted echoed, rejected short-circuited (1:1)" in {
      val (resp, _) = run(cap = 2, input)
      val events = timedOnly(resp).map(_.event)
      events should have size 7                                   // one terminal response per request
      events.count(_ == ToyResp(-1)) shouldBe 3                   // 2 rejected in tick 1 + 1 in tick 2
      events should contain allOf (ToyResp(10), ToyResp(11), ToyResp(20), ToyResp(21)) // onTick reset: tick 2 admits 2 again
    }

    "pass only admitted requests to the downstream (rejects never reach it)" in {
      val (_, cons) = run(cap = 2, input)
      timedOnly(cons) should have size 4                          // only the 4 admitted requests do work
      timedOnly(cons).map(_.event).distinct shouldBe Seq(ToyCons("work"))
    }

    "preserve control events, ending both planes with EndOfTime" in {
      val (resp, cons) = run(cap = 2, input)
      resp.last shouldBe EndOfTime
      cons.last shouldBe EndOfTime
    }

    "be deterministic" in {
      run(cap = 2, input) shouldBe run(cap = 2, input)
    }
  }

  // One request at tick 2, intraTick 0; conceptual response time = 2 + (sum of latencies it passed).
  private val one = TickFraming.frame(Vector(req(2, 0)).iterator, 5).toVector
  private def conceptualTime(rs: Seq[Timed[ToyResp]]): Double =
    rs.head.eventTime.ticks.toDouble + rs.head.intraTick

  "A stack of interface gates" should {

    "accumulate a latency gate's delay on top of the downstream's latency" in {
      val edge = Interface.wrap(echo(0.1), LatencyGate.constant[ToyReq, ToyResp](0.5), RandomSource.KISS.create(3L))
      conceptualTime(responsesOf(edge, one)) shouldBe (2.6 +- 1e-9)   // 2 + 0.5 (gate) + 0.1 (downstream)
    }

    "sum the latencies of stacked gates" in {
      val edge = Interface.wrap(
        Interface.wrap(echo(0.1), LatencyGate.constant[ToyReq, ToyResp](0.5), RandomSource.KISS.create(3L)),
        LatencyGate.constant[ToyReq, ToyResp](0.2),
        RandomSource.KISS.create(4L)
      )
      conceptualTime(responsesOf(edge, one)) shouldBe (2.8 +- 1e-9)   // 2 + 0.2 + 0.5 + 0.1
    }

    "preserve 1:1 and throttle behavior with a latency gate stacked in front of a throttle" in {
      val edge = Interface.wrap(
        Interface.wrap(echo(0.0), new FlatThrottleGate[ToyReq, ToyResp](2, ToyResp(-1)), RandomSource.KISS.create(3L)),
        LatencyGate.constant[ToyReq, ToyResp](0.3),
        RandomSource.KISS.create(4L)
      )
      val events = responsesOf(edge, input).map(_.event)
      events should have size 7                                       // still one response per request
      events.count(_ == ToyResp(-1)) shouldBe 3                       // the throttle still rejects; latency admits all
    }

    "compose a token-bucket gate through the wrap, preserving 1:1" in {
      // Full-3 bucket, refill 1/tick: tick 1 (4 arrivals) admits 3 + rejects 1; tick 2 refills to 1,
      // (3 arrivals) admits 1 + rejects 2.
      val edge = Interface.wrap(echo(0.0), new TokenBucketGate[ToyReq, ToyResp](3, 1, ToyResp(-1)), RandomSource.KISS.create(3L))
      val events = responsesOf(edge, input).map(_.event)
      events should have size 7
      events.count(_ == ToyResp(-1)) shouldBe 3
    }
  }
