package stochastacy.core.component

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimInstant, SimTime, TimedControlEvent, TimedElement, ticks}

/** De-risking prototype for the loopback transducer (phase-11 Slice 1): a toy component wired into a
 *  delayed self-loop must run to completion (no deadlock) and see each effect it emits reappear on its
 *  feedback input, deterministically. Phase-13 Slice 1 adds the input-time (`at`) and emitting-feedback
 *  contract: a fed-back item may answer with a forward output, consumption, and (later-tick) taps. */
class LoopbackTransducerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("LoopbackTransducerSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /** Emits its input as both a forward output and a tap; accumulates every fed-back value into its state. */
  private final class ToyLoopback extends LoopbackComponentSampler[List[Int], Int, Int, Int, Nothing, Int]:
    def initialState: List[Int] = Nil
    def sample(in: Int, at: SimInstant, state: List[Int], rng: UniformRandomProvider): LoopbackEmission[List[Int], Int, Nothing, Int] =
      LoopbackEmission(state, Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, at: SimInstant, state: List[Int], rng: UniformRandomProvider): FeedbackEmission[List[Int], Int, Nothing, Int] =
      FeedbackEmission(fb :: state)

  /** The coordinator's role, minimally: hold each tap for one tick, then feed it back. Tick markers pass
   *  through so the feedback stream stays framed. */
  private def delayFlow: Flow[TimedElement[Timed[Int]], TimedElement[Timed[Int]], org.apache.pekko.NotUsed] =
    Flow[TimedElement[Timed[Int]]].statefulMapConcat { () =>
      var held = Vector.empty[Timed[Int]]
      {
        case tick: TimedControlEvent.Tick =>
          val fbs: List[TimedElement[Timed[Int]]] = held.toList.map(tp => Timed(tp.event, tp.eventTime, 0.0, tp.usecase))
          held = Vector.empty
          fbs :+ tick
        case other: TimedControlEvent      => List[TimedElement[Timed[Int]]](other)
        case tp: Timed[Int] @unchecked     => held = held :+ tp; Nil
      }
    }

  private def runLoop(inputs: Vector[Int], ticks: Long): ComponentResult[List[Int]] =
    val rng: UniformRandomProvider = RandomSource.KISS.create(1L)
    val workload = inputs.zipWithIndex.map { (v, i) => Timed(v, SimTime.of(i + 1L), 0.0, "toy") }
    val framed   = TickFraming.frame(workload.iterator, ticks).toVector
    val stageG   = ScheduleReleaseTransducer.loopbackComponentOf(new ToyLoopback, rng)
    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(stageG) { implicit b => stage =>
      import GraphDSL.Implicits.*
      b.add(Source(framed)) ~> stage.in
      stage.tapOut ~> b.add(delayFlow) ~> stage.fbIn // the cycle
      stage.fwdOut  ~> Sink.ignore
      stage.consOut ~> Sink.ignore
      ClosedShape
    })
    Await.result(graph.run(), 20.seconds)

  // --- phase-13 Slice 1: input time + emitting feedback ---

  /** A one-tick register: holds each tap until the next `Tick`, then emits that `Tick` followed by the held items
   *  re-stamped into the new tick (intraTick preserved) — a fed-back item arrives exactly one tick after its tap. */
  private def oneTickLater: Flow[TimedElement[Timed[Int]], TimedElement[Timed[Int]], org.apache.pekko.NotUsed] =
    Flow[TimedElement[Timed[Int]]].statefulMapConcat { () =>
      var held = Vector.empty[Timed[Int]]
      {
        case tick: TimedControlEvent.Tick =>
          val out: List[TimedElement[Timed[Int]]] =
            tick :: held.toList.map(tp => Timed(tp.event, tick.eventTime, tp.intraTick, tp.usecase))
          held = Vector.empty
          out
        case other: TimedControlEvent  => List[TimedElement[Timed[Int]]](other)
        case tp: Timed[Int] @unchecked => held = held :+ tp; Nil
      }
    }

  /** Run `sampler` in a self-loop through [[oneTickLater]], collecting its forward and consumption planes. */
  private def runCycle[S, Out, Cons](
    sampler: LoopbackComponentSampler[S, Int, Int, Out, Cons, Int],
    inputs:  Vector[Timed[Int]],
    horizon: Long
  ): (Future[ComponentResult[S]], Future[Seq[TimedElement[Timed[Out]]]], Future[Seq[TimedElement[Timed[Cons]]]]) =
    val framed = TickFraming.frame(inputs.iterator, horizon).toVector
    val stageG = ScheduleReleaseTransducer.loopbackComponentOf(sampler, RandomSource.KISS.create(1L))
    RunnableGraph.fromGraph(
      GraphDSL.createGraph(stageG, Sink.seq[TimedElement[Timed[Out]]], Sink.seq[TimedElement[Timed[Cons]]])((_, _, _)) {
        implicit b => (stage, fwdSink, consSink) =>
          import GraphDSL.Implicits.*
          b.add(Source(framed)) ~> stage.in
          stage.tapOut ~> b.add(oneTickLater) ~> stage.fbIn // the cycle
          stage.fwdOut  ~> fwdSink.in
          stage.consOut ~> consSink.in
          ClosedShape
      }
    ).run()

  private def timedOnly[E](s: Seq[TimedElement[Timed[E]]]): Seq[Timed[E]] =
    s.collect { case x: Timed[E] @unchecked => x }

  /** Every timed element sits inside its own tick window: after `Tick(eventTime)`, before `Tick(eventTime + 1)`. */
  private def assertWindowed[E](s: Seq[TimedElement[Timed[E]]]): Unit =
    var current = 0L
    s.foreach {
      case TimedControlEvent.Tick(t)   => current = t.ticks
      case TimedControlEvent.EndOfTime => ()
      case x: Timed[E] @unchecked      => withClue(s"$x in window $current: ")(x.eventTime.ticks shouldBe current)
    }

  private type AtLog = Vector[(String, SimInstant)]

  /** Records the `at` of every sample and every feedback; taps each input so it is fed back one tick later. */
  private final class AtRecorder extends LoopbackComponentSampler[AtLog, Int, Int, Int, Nothing, Int]:
    def initialState: AtLog = Vector.empty
    def sample(in: Int, at: SimInstant, state: AtLog, rng: UniformRandomProvider): LoopbackEmission[AtLog, Int, Nothing, Int] =
      LoopbackEmission(state :+ ("sample" -> at), Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, at: SimInstant, state: AtLog, rng: UniformRandomProvider): FeedbackEmission[AtLog, Int, Nothing, Int] =
      FeedbackEmission(state :+ ("feedback" -> at))

  /** Answers every primary input *and* every fed-back input on the forward plane; taps primaries only. */
  private final class FeedbackAnswerer extends LoopbackComponentSampler[Unit, Int, Int, Int, String, Int]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, state: Unit, rng: UniformRandomProvider): LoopbackEmission[Unit, Int, String, Int] =
      LoopbackEmission((), Scheduled(in, 0.0), List(Scheduled("sample", 0.0)), List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, at: SimInstant, state: Unit, rng: UniformRandomProvider): FeedbackEmission[Unit, Int, String, Int] =
      FeedbackEmission((), Some(Scheduled(fb * 10, 0.25)), List(Scheduled("feedback", 0.0)))

  /** Relays a hop counter around the loop: each feedback re-taps `hops − 1` (after `tapDelay`) until it reaches 1.
   *  State counts feedbacks absorbed. */
  private final class HopRelay(tapDelay: Double) extends LoopbackComponentSampler[Int, Int, Int, Int, Nothing, Int]:
    def initialState: Int = 0
    def sample(in: Int, at: SimInstant, state: Int, rng: UniformRandomProvider): LoopbackEmission[Int, Int, Nothing, Int] =
      LoopbackEmission(state, Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, at: SimInstant, state: Int, rng: UniformRandomProvider): FeedbackEmission[Int, Int, Nothing, Int] =
      FeedbackEmission(state + 1, taps = if fb > 1 then List(Scheduled(fb - 1, tapDelay)) else Nil)

  "The loopback transducer, wired into a delayed self-loop," should {

    "run to completion without deadlock, feeding every emitted effect back" in {
      // inputs 1..5 at ticks 1..5, with trailing empty ticks so every tap has a following boundary to release.
      val result = runLoop(Vector(1, 2, 3, 4, 5), ticks = 9L)
      result.finalState.sorted shouldBe List(1, 2, 3, 4, 5) // every tap reappeared on the feedback input
    }

    "be deterministic across runs" in {
      runLoop(Vector(10, 20, 30), 8L).finalState shouldBe runLoop(Vector(10, 20, 30), 8L).finalState
    }

    "pass each primary and fed-back input's conceptual time as `at`" in {
      val inputs = Vector(Timed(1, SimTime.of(1L), 0.25, "toy"), Timed(2, SimTime.of(2L), 0.5, "toy"))
      val (resultF, fwdF, consF) = runCycle(new AtRecorder, inputs, horizon = 6L)
      val log = Await.result(resultF, 20.seconds).finalState
      Await.result(fwdF, 20.seconds); Await.result(consF, 20.seconds)

      log.collect { case ("sample", at) => at }   shouldBe Vector(SimInstant(1L, 0.25), SimInstant(2L, 0.5))
      log.collect { case ("feedback", at) => at } shouldBe Vector(SimInstant(2L, 0.25), SimInstant(3L, 0.5))
    }

    "release a feedback's forward output and consumption in time order, inside its tick window" in {
      val inputs = Vector(Timed(1, SimTime.of(1L), 0.5, "toy"), Timed(2, SimTime.of(2L), 0.0, "toy"))
      val (resultF, fwdF, consF) = runCycle(new FeedbackAnswerer, inputs, horizon = 6L)
      Await.result(resultF, 20.seconds)
      val fwd  = Await.result(fwdF, 20.seconds)
      val cons = Await.result(consF, 20.seconds)

      // input 1 @ (1, 0.5) is fed back @ (2, 0.5) → answers 10 @ (2, 0.75);
      // input 2 @ (2, 0.0) is fed back @ (3, 0.0) → answers 20 @ (3, 0.25).
      timedOnly(fwd).map(t => (t.event, t.eventTime.ticks, t.intraTick)) shouldBe
        Seq((1, 1L, 0.5), (2, 2L, 0.0), (10, 2L, 0.75), (20, 3L, 0.25))
      assertWindowed(fwd)
      assertWindowed(cons)
      timedOnly(cons).count(_.event == "sample")   shouldBe 2
      timedOnly(cons).count(_.event == "feedback") shouldBe 2
      fwd.last  shouldBe TimedControlEvent.EndOfTime
      cons.last shouldBe TimedControlEvent.EndOfTime
    }

    "keep a loop running when feedback taps reach a later tick" in {
      // 3 hops for the input at tick 1 and 2 hops for the input at tick 2 → 5 feedbacks absorbed.
      val inputs = Vector(Timed(3, SimTime.of(1L), 0.0, "toy"), Timed(2, SimTime.of(2L), 0.0, "toy"))
      val (resultF, fwdF, consF) = runCycle(new HopRelay(tapDelay = 1.0), inputs, horizon = 12L)
      Await.result(resultF, 20.seconds).finalState shouldBe 5
      val fwd = Await.result(fwdF, 20.seconds)
      Await.result(consF, 20.seconds)
      timedOnly(fwd).map(_.event) shouldBe Seq(3, 2) // feedback here emits no forward output
      assertWindowed(fwd)
    }

    "fail the stage when a feedback tap is stamped in its own feedback's tick" in {
      val inputs = Vector(Timed(3, SimTime.of(1L), 0.0, "toy"))
      val (resultF, _, _) = runCycle(new HopRelay(tapDelay = 0.0), inputs, horizon = 6L)
      val ex = intercept[IllegalStateException](Await.result(resultF, 20.seconds))
      ex.getMessage should include ("onFeedback tap")
    }
  }
