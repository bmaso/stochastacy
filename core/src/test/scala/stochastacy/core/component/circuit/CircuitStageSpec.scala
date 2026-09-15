package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{ComponentSampler, Emission, FeedbackEmission, LoopbackComponentSampler, LoopbackEmission,
  ScheduleReleaseTransducer, Scheduled, TickBoundaryUsecase, TickEmission, Timed}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.core.component.circuit.RouteSource.{CircuitInput, NodePlane}
import stochastacy.core.component.circuit.RouteTarget.{ForwardOutlet, NodePort}
import stochastacy.sim.{SimInstant, TimedControlEvent, TimedElement, ticks}

/** The circuit engine's behavior: exact in-window loop ordering, feedback that answers, loops across ticks, tick
 *  boundaries, routing (fan-out, filters, unrouted diagnostics, usecase), residue, the failure guards, the
 *  documented input-ordering difference from the transducer, determinism, and plan validation. */
class CircuitStageSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitStageSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def rng(seed: Long = 1L): UniformRandomProvider = RandomSource.KISS.create(seed)

  private def plan(nodes: ErasedNode*)(routes: (RouteSource, Route)*): CircuitPlan =
    CircuitPlan(nodes.toVector, routes.toVector.groupMap(_._1)(_._2))

  /** A node that loops to itself: input → `In`, taps → its own `Fb`, forward output → forward outlet. */
  private def selfLoop(node: ErasedNode): CircuitPlan =
    plan(node)(
      CircuitInput                       -> Route(NodePort(0, Port.In)),
      NodePlane(0, CircuitPlane.Taps)    -> Route(NodePort(0, Port.Fb)),
      NodePlane(0, CircuitPlane.Out)     -> Route(ForwardOutlet)
    )

  /** Every timed element sits inside its own tick window: after `Tick(eventTime)`, before `Tick(eventTime + 1)`. */
  private def assertWindowed[E](s: Seq[TimedElement[Timed[E]]]): Unit =
    var current = 0L
    s.foreach {
      case TimedControlEvent.Tick(t)   => current = t.ticks
      case TimedControlEvent.EndOfTime => ()
      case x: Timed[E] @unchecked      => withClue(s"$x in window $current: ")(x.eventTime.ticks shouldBe current)
    }

  private def approx(actual: Seq[Double], expected: Seq[Double]): Unit =
    actual should have size expected.size.toLong
    actual.zip(expected).foreach((a, e) => a shouldBe (e +- 1e-9))

  // --- toy nodes ---

  private type Log = Vector[(String, Int, SimInstant)]

  /** Logs every dispatch (`p` = primary, `f` = feedback); each primary taps itself back after `loopDelay`. */
  private final class LoopLogger(loopDelay: Double) extends LoopbackComponentSampler[Log, Int, Int, Int, Nothing, Int]:
    def initialState: Log = Vector.empty
    def sample(in: Int, at: SimInstant, s: Log, rng: UniformRandomProvider): LoopbackEmission[Log, Int, Nothing, Int] =
      LoopbackEmission(s :+ (("p", in, at)), Scheduled(in, 0.0), Nil, List(Scheduled(in, loopDelay)))
    def onFeedback(fb: Int, at: SimInstant, s: Log, rng: UniformRandomProvider): FeedbackEmission[Log, Int, Nothing, Int] =
      FeedbackEmission(s :+ (("f", fb, at)))

  /** Answers each primary, taps it back after 0.2, and answers the fed-back item with `fb × 10` after 0.05. */
  private final class Answerer extends LoopbackComponentSampler[Unit, Int, Int, Int, Nothing, Int]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): LoopbackEmission[Unit, Int, Nothing, Int] =
      LoopbackEmission((), Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.2)))
    def onFeedback(fb: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): FeedbackEmission[Unit, Int, Nothing, Int] =
      FeedbackEmission((), output = Some(Scheduled(fb * 10, 0.05)))

  /** Relays a hop counter around the loop, `hopDelay` per hop, recording each feedback's instant. */
  private final class HopRelay(hopDelay: Double) extends LoopbackComponentSampler[Vector[SimInstant], Int, Int, Int, Nothing, Int]:
    def initialState: Vector[SimInstant] = Vector.empty
    def sample(in: Int, at: SimInstant, s: Vector[SimInstant], rng: UniformRandomProvider): LoopbackEmission[Vector[SimInstant], Int, Nothing, Int] =
      LoopbackEmission(s, Scheduled(in, 0.0), Nil, List(Scheduled(in, hopDelay)))
    def onFeedback(fb: Int, at: SimInstant, s: Vector[SimInstant], rng: UniformRandomProvider): FeedbackEmission[Vector[SimInstant], Int, Nothing, Int] =
      FeedbackEmission(s :+ at, taps = if fb > 1 then List(Scheduled(fb - 1, hopDelay)) else Nil)

  /** A per-tick counter: answers with its count; `onTick` resets it and emits a `tick-<t>` boundary fact. */
  private final class TickCounter extends ComponentSampler[Int, String, Int, String]:
    def initialState: Int = 0
    def sample(in: String, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, Int, String] =
      Emission(s + 1, Scheduled(s, 0.0), List(Scheduled("req", 0.0)))
    override def onTick(tick: Long, s: Int): TickEmission[Int, String] = TickEmission(0, List(Scheduled(s"tick-$tick", 0.0)))

  /** Answers `in + plus`, optionally with one consumption fact. */
  private final class Echo(plus: Int, cons: Option[String]) extends ComponentSampler[Unit, Int, Int, String]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Int, String] =
      Emission((), Scheduled(in + plus, 0.0), cons.toList.map(c => Scheduled(c, 0.0)))

  /** Answers 1.5 ticks late and taps itself back 2.5 ticks late; counts feedbacks. */
  private final class LateEcho extends LoopbackComponentSampler[Int, Int, Int, Int, Nothing, Int]:
    def initialState: Int = 0
    def sample(in: Int, at: SimInstant, s: Int, rng: UniformRandomProvider): LoopbackEmission[Int, Int, Nothing, Int] =
      LoopbackEmission(s, Scheduled(in, 1.5), Nil, List(Scheduled(in, 2.5)))
    def onFeedback(fb: Int, at: SimInstant, s: Int, rng: UniformRandomProvider): FeedbackEmission[Int, Int, Nothing, Int] =
      FeedbackEmission(s + 1)

  /** A zero-delay cycle that never stops: every feedback taps itself back immediately. */
  private final class Forever extends LoopbackComponentSampler[Unit, Int, Int, Int, Nothing, Int]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): LoopbackEmission[Unit, Int, Nothing, Int] =
      LoopbackEmission((), Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): FeedbackEmission[Unit, Int, Nothing, Int] =
      FeedbackEmission((), taps = List(Scheduled(fb, 0.0)))

  /** Counts inputs; answers each with the count before it. */
  private final class Counter extends ComponentSampler[Int, String, Int, Nothing]:
    def initialState: Int = 0
    def sample(in: String, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, Int, Nothing] =
      Emission(s + 1, Scheduled(s, 0.0), Nil)

  /** Draws its outputs, delays, and loop delays from its RNG. */
  private final class Jitter extends LoopbackComponentSampler[Long, Int, Int, Int, Nothing, Int]:
    def initialState: Long = 0L
    def sample(in: Int, at: SimInstant, s: Long, rng: UniformRandomProvider): LoopbackEmission[Long, Int, Nothing, Int] =
      LoopbackEmission(s + 1, Scheduled(rng.nextInt(1000), rng.nextDouble() * 0.5), Nil, List(Scheduled(in, rng.nextDouble() * 0.3)))
    def onFeedback(fb: Int, at: SimInstant, s: Long, rng: UniformRandomProvider): FeedbackEmission[Long, Int, Nothing, Int] =
      FeedbackEmission(s + 1, output = Some(Scheduled(rng.nextInt(1000), rng.nextDouble() * 0.2)))

  "The circuit stage" should {

    "dispatch a self-loop's feedback exactly between primary inputs, by conceptual time" in {
      val input = framed(Seq((1L, 0.1, 1), (1L, 0.3, 2), (1L, 0.5, 3), (1L, 0.7, 4)), horizon = 3L)
      val out   = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("logger", new LoopLogger(0.25), rng()))), input)
      val log   = out.mat.nodeStates(0).asInstanceOf[Log]

      // Feedbacks land at 0.35, 0.55, 0.75, 0.95. Arrival order would be p1 p2 p3 p4 f1 f2 f3 f4, and a naive
      // "feedback right after its primary" would be p1 f1 p2 f2 …; conceptual-time order is neither.
      log.map((k, v, _) => (k, v)) shouldBe Vector(("p", 1), ("p", 2), ("f", 1), ("p", 3), ("f", 2), ("p", 4), ("f", 3), ("f", 4))
      approx(log.map(_._3.intraTick), Seq(0.1, 0.3, 0.35, 0.5, 0.55, 0.7, 0.75, 0.95))
      log.map(_._3.tick).distinct shouldBe Vector(1L)
    }

    "dispatch a zero-delay feedback immediately after its primary, before the next primary" in {
      val input = framed(Seq((1L, 0.1, 1), (1L, 0.3, 2), (1L, 0.5, 3), (1L, 0.7, 4)), horizon = 3L)
      val out   = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("logger", new LoopLogger(0.0), rng()))), input)
      val log   = out.mat.nodeStates(0).asInstanceOf[Log]

      log.map((k, v, _) => (k, v)) shouldBe Vector(("p", 1), ("f", 1), ("p", 2), ("f", 2), ("p", 3), ("f", 3), ("p", 4), ("f", 4))
      approx(log.map(_._3.intraTick), Seq(0.1, 0.1, 0.3, 0.3, 0.5, 0.5, 0.7, 0.7))
    }

    "release a feedback's answer in time order, inside its tick window" in {
      // 1 @ (1, 0.1) is fed back @ (1, 0.3) → answers 10 @ (1, 0.35); 2 @ (1, 0.9) is fed back @ (2, 0.1) → 20 @ (2, 0.15).
      val input = framed(Seq((1L, 0.1, 1), (1L, 0.9, 2)), horizon = 4L)
      val out   = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("answerer", new Answerer, rng()))), input)
      val fwd   = timedOnly(out.fwd).map(t => (t.event, t.eventTime.ticks, t.intraTick))

      fwd.map((e, t, _) => (e, t)) shouldBe Seq((1, 1L), (10, 1L), (2, 1L), (20, 2L))
      approx(fwd.map(_._3), Seq(0.1, 0.35, 0.9, 0.15))
      assertWindowed(out.fwd)
      out.fwd.last shouldBe TimedControlEvent.EndOfTime
    }

    "carry a loop across tick boundaries" in {
      // 3 hops of 0.6 from (1, 0.5): feedbacks at (2, 0.1), (2, 0.7), (3, 0.3).
      val out = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("relay", new HopRelay(0.6), rng()))),
                    framed(Seq((1L, 0.5, 3)), horizon = 5L))
      val hops = out.mat.nodeStates(0).asInstanceOf[Vector[SimInstant]]

      hops.map(_.tick) shouldBe Vector(2L, 2L, 3L)
      approx(hops.map(_.intraTick), Seq(0.1, 0.7, 0.3))
      timedOnly(out.fwd).map(_.event) shouldBe Seq(3)
    }

    "run onTick before a window's events, releasing boundary facts first in their tick" in {
      val input = framed(Seq((1L, 0.2, "a"), (1L, 0.4, "a"), (1L, 0.6, "a"), (2L, 0.2, "a"), (2L, 0.4, "a")), horizon = 2L)
      val out   = runCircuit(CircuitStage.componentOf[String, Int, String](oneNodePlan(ErasedNode.of("counter", new TickCounter, rng()))), input)

      timedOnly(out.fwd).map(_.event) shouldBe Seq(0, 1, 2, 0, 1) // the reset restarts each tick at 0
      val cons = timedOnly(out.cons)
      cons.map(_.event) shouldBe Seq("tick-1", "req", "req", "req", "tick-2", "req", "req")
      cons.head.usecase shouldBe TickBoundaryUsecase
      assertWindowed(out.cons)
      out.mat.residue shouldBe CircuitResidue(0L, 0L, 1L) // the flush tick's boundary fact is never released
    }

    "fan out, filter by transform, count unrouted emissions and inputs, and propagate the input's usecase" in {
      val nonNegative: Any => Option[Any] = { case v: Int if v >= 0 => Some(v); case _ => None }
      val even: Any => Option[Any]        = { case v: Int if v % 2 == 0 => Some(v); case _ => None }
      val oddTimes100: Any => Option[Any] = { case v: Int if v % 2 != 0 => Some(v * 100); case _ => None }
      val p = plan(ErasedNode.of("A", new Echo(0, Some("x")), rng()), ErasedNode.of("B", new Echo(1, None), rng()))(
        CircuitInput                   -> Route(NodePort(0, Port.In), nonNegative),
        CircuitInput                   -> Route(NodePort(1, Port.In), nonNegative),
        NodePlane(0, CircuitPlane.Out) -> Route(ForwardOutlet, even),
        NodePlane(0, CircuitPlane.Out) -> Route(NodePort(1, Port.In), oddTimes100),
        NodePlane(1, CircuitPlane.Out) -> Route(ForwardOutlet)
      )
      val out = runCircuit(CircuitStage.componentOf[Int, Int, String](p), framed(Seq((1L, 0.1, 1), (1L, 0.2, 2), (1L, 0.3, -5)), horizon = 2L, usecase = "uc-7"))

      // (1, 0.1): A(1) → odd → B(100); B(1) → 2; B(100) → 101.  (1, 0.2): A(2) → even → 2; B(2) → 3.  -5 is filtered.
      timedOnly(out.fwd).map(_.event) shouldBe Seq(2, 101, 2, 3)
      timedOnly(out.fwd).map(_.usecase).distinct shouldBe Seq("uc-7")
      out.mat.unrouted shouldBe Vector(UnroutedCount(0, "A", CircuitPlane.Consumption, 2L))
      out.mat.unroutedInputs shouldBe 1L
    }

    "count post-horizon outlet items and calendar events as residue, never emitting them" in {
      // input @ (2, 0.0), horizon 2: its answer lands @ (3, 0.5) and its feedback @ (4, 0.5) — both past the flush tick.
      val out = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("late", new LateEcho, rng()))),
                    framed(Seq((2L, 0.0, 7)), horizon = 2L))
      timedOnly(out.fwd) shouldBe empty
      out.fwd.last shouldBe TimedControlEvent.EndOfTime
      out.mat.residue shouldBe CircuitResidue(calendarEvents = 1L, forwardOutputs = 1L, consumptions = 0L)
      out.mat.nodeStates shouldBe Vector(0) // the feedback was never dispatched
    }

    "fail on a runaway zero-delay cycle" in {
      val p = plan(ErasedNode.of("forever", new Forever, rng()))(
        CircuitInput                    -> Route(NodePort(0, Port.In)),
        NodePlane(0, CircuitPlane.Taps) -> Route(NodePort(0, Port.Fb))
      ).copy(maxEventsPerWindow = 1000L)
      val (mat, _, _) = runFutures(CircuitStage.componentOf[Int, Int, Nothing](p), framed(Seq((1L, 0.5, 1)), horizon = 3L))
      val ex = intercept[IllegalStateException](Await.result(mat, 30.seconds))
      ex.getMessage should include ("dispatched more than 1000")
      ex.getMessage should include ("'forever'")
    }

    "fail on an emission with a negative delay" in {
      val backwards = new ComponentSampler[Unit, Int, Int, Nothing]:
        def initialState: Unit = ()
        def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Int, Nothing] =
          Emission((), Scheduled(in, -0.1), Nil)
      val (mat, _, _) = runFutures(CircuitStage.componentOf[Int, Int, Nothing](oneNodePlan(ErasedNode.of("backwards", backwards, rng()))),
                                   framed(Seq((1L, 0.5, 1)), horizon = 2L))
      val ex = intercept[IllegalStateException](Await.result(mat, 30.seconds))
      ex.getMessage should include ("negative delay")
    }

    "fail with the node's name when a node throws" in {
      val thrower = new ComponentSampler[Unit, Int, Int, Nothing]:
        def initialState: Unit = ()
        def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Int, Nothing] =
          throw new RuntimeException("boom")
      val (mat, _, _) = runFutures(CircuitStage.componentOf[Int, Int, Nothing](oneNodePlan(ErasedNode.of("thrower", thrower, rng()))),
                                   framed(Seq((1L, 0.5, 1)), horizon = 2L))
      val ex = intercept[IllegalStateException](Await.result(mat, 30.seconds))
      ex.getMessage should include ("'thrower'")
      ex.getMessage should include ("boom")
      ex.getCause.getMessage shouldBe "boom"
    }

    "process a window's inputs in conceptual-time order — unlike the transducer's arrival order (documented)" in {
      // Unsorted within tick 1: "late" (0.9) arrives before "early" (0.1).
      val input = framed(Seq((1L, 0.9, "late"), (1L, 0.1, "early")), horizon = 2L)
      val viaCircuit    = runCircuit(CircuitStage.componentOf[String, Int, Nothing](oneNodePlan(ErasedNode.of("counter", new Counter, rng()))), input)
      val viaTransducer = runCircuit(ScheduleReleaseTransducer.componentOf(new Counter, rng()), input)

      // The circuit numbers "early" first; the transducer numbers "late" first. Both release in time order.
      timedOnly(viaCircuit.fwd).map(t => (t.event, t.intraTick))    shouldBe Seq((0, 0.1), (1, 0.9))
      timedOnly(viaTransducer.fwd).map(t => (t.event, t.intraTick)) shouldBe Seq((1, 0.1), (0, 0.9))
    }

    "be deterministic across runs" in {
      val input = framed(Seq.tabulate(50)(i => ((i / 10 + 1).toLong, (i % 10) / 10.0 + 0.05, i)), horizon = 8L)
      def once() = runCircuit(CircuitStage.componentOf[Int, Int, Nothing](selfLoop(ErasedNode.of("jitter", new Jitter, rng(42L)))), input)
      val (a, b) = (once(), once())
      a.fwd shouldBe b.fwd
      a.mat shouldBe b.mat
      timedOnly(a.fwd).size should be > 50 // primaries answered, plus feedback answers within the horizon
    }

    "reject a structurally malformed plan" in {
      val node = ErasedNode.of("n", new Counter, rng())
      an[IllegalArgumentException] should be thrownBy CircuitPlan(Vector.empty, Map.empty)
      an[IllegalArgumentException] should be thrownBy plan(node)(CircuitInput -> Route(NodePort(5, Port.In)))
      an[IllegalArgumentException] should be thrownBy plan(node)(NodePlane(0, CircuitPlane.Consumption) -> Route(NodePort(0, Port.In)))
      an[IllegalArgumentException] should be thrownBy plan(node)(CircuitInput -> Route(ForwardOutlet))
      an[IllegalArgumentException] should be thrownBy plan(node)().copy(maxEventsPerWindow = 0L)
    }
  }
