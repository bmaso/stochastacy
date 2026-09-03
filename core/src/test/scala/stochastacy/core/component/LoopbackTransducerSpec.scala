package stochastacy.core.component

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

/** De-risking prototype for the loopback transducer (phase-11 Slice 1): a toy component wired into a
 *  delayed self-loop must run to completion (no deadlock) and see each effect it emits reappear on its
 *  feedback input, deterministically. */
class LoopbackTransducerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("LoopbackTransducerSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /** Emits its input as both a forward output and a tap; accumulates every fed-back value into its state. */
  private final class ToyLoopback extends LoopbackComponentSampler[List[Int], Int, Int, Int, Nothing, Int]:
    def initialState: List[Int] = Nil
    def sample(in: Int, state: List[Int], rng: UniformRandomProvider): LoopbackEmission[List[Int], Int, Nothing, Int] =
      LoopbackEmission(state, Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Int, state: List[Int], rng: UniformRandomProvider): TickEmission[List[Int], Nothing] =
      TickEmission(fb :: state, Nil)

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

  "The loopback transducer, wired into a delayed self-loop," should {

    "run to completion without deadlock, feeding every emitted effect back" in {
      // inputs 1..5 at ticks 1..5, with trailing empty ticks so every tap has a following boundary to release.
      val result = runLoop(Vector(1, 2, 3, 4, 5), ticks = 9L)
      result.finalState.sorted shouldBe List(1, 2, 3, 4, 5) // every tap reappeared on the feedback input
    }

    "be deterministic across runs" in {
      runLoop(Vector(10, 20, 30), 8L).finalState shouldBe runLoop(Vector(10, 20, 30), 8L).finalState
    }
  }
