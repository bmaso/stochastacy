package stochastacy.core.component.circuit

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.core.component.{ComponentSampler, Emission, FeedbackEmission, LoopbackComponentSampler, LoopbackEmission,
  ScheduleReleaseTransducer, Scheduled, Timed}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.sim.{SimInstant, TimedElement}

/**
 * A throughput microbenchmark for the circuit engine — **recorded, not gated** (not part of `sbt test`). Run with
 * `sbt "core/Test/runMain stochastacy.core.component.circuit.CircuitThroughputBenchmark"`.
 *
 *  (a) **Calendar dispatch:** two nodes relaying a hop counter to each other at zero delay — one input per tick
 *      carrying a million hops, so ~10 M dispatches over 10 windows.
 *  (b) **One-node circuit vs `componentOf`:** the same simple sampler over ~1 M framed inputs (10 k per tick).
 *
 * Each measurement runs once to warm up, then once timed.
 */
object CircuitThroughputBenchmark:

  /** Relays a hop counter: a primary taps its value; each feedback taps `hops − 1` until zero. State counts dispatches. */
  private final class Relay extends LoopbackComponentSampler[Long, Long, Long, Long, Nothing, Long]:
    def initialState: Long = 0L
    def sample(in: Long, at: SimInstant, s: Long, rng: UniformRandomProvider): LoopbackEmission[Long, Long, Nothing, Long] =
      LoopbackEmission(s + 1, Scheduled(in, 0.0), Nil, List(Scheduled(in, 0.0)))
    def onFeedback(fb: Long, at: SimInstant, s: Long, rng: UniformRandomProvider): FeedbackEmission[Long, Long, Nothing, Long] =
      FeedbackEmission(s + 1, taps = if fb > 0L then List(Scheduled(fb - 1L, 0.0)) else Nil)

  private final class Simple extends ComponentSampler[Long, Long, Long, Long]:
    def initialState: Long = 0L
    def sample(in: Long, at: SimInstant, s: Long, rng: UniformRandomProvider): Emission[Long, Long, Long] =
      Emission(s + 1, Scheduled(in, 0.01), List(Scheduled(in, 0.0)))

  private def drain[In, Out, Cons, M](component: Component[In, Out, Cons, M], input: Vector[TimedElement[Timed[In]]])(using
    ActorSystem
  ): M =
    val mat: Future[M] = RunnableGraph.fromGraph(
      GraphDSL.createGraph(component) { implicit b => c =>
        import GraphDSL.Implicits.*
        b.add(Source(input)) ~> c.in
        c.out0 ~> b.add(Sink.ignore)
        c.out1 ~> b.add(Sink.ignore)
        ClosedShape
      }
    ).run()
    Await.result(mat, 30.minutes)

  private def timed[A](label: String, events: Long)(body: => A): A =
    body // warm-up
    val start   = System.nanoTime()
    val result  = body
    val seconds = (System.nanoTime() - start) / 1e9
    println(f"$label%-48s ${events}%,12d events in $seconds%7.2f s = ${events / seconds}%,14.0f events/s")
    result

  def main(args: Array[String]): Unit =
    given system: ActorSystem = ActorSystem("CircuitThroughputBenchmark")
    try
      // (a) calendar dispatch: 10 windows × (1 primary + 1 M feedback hops)
      val hops    = 1_000_000L
      val windows = 10L
      val ring = framed((1L to windows).map(t => (t, 0.5, hops)), horizon = windows)
      val relayPlan = () =>
        CircuitPlan(
          Vector(ErasedNode.of("A", new Relay, RandomSource.KISS.create(1L)), ErasedNode.of("B", new Relay, RandomSource.KISS.create(2L))),
          Map(
            RouteSource.CircuitInput                    -> Vector(Route(RouteTarget.NodePort(0, Port.In))),
            RouteSource.NodePlane(0, CircuitPlane.Taps) -> Vector(Route(RouteTarget.NodePort(1, Port.Fb))),
            RouteSource.NodePlane(1, CircuitPlane.Taps) -> Vector(Route(RouteTarget.NodePort(0, Port.Fb)))
          ),
          maxEventsPerWindow = 2 * hops + 10)
      val relayResult = timed("(a) calendar: zero-delay two-node relay", windows * (hops + 2)) {
        drain(CircuitStage.componentOf[Long, Long, Nothing](relayPlan()), ring)
      }
      // Each Relay's state counts its own dispatches — verify the relay really ran every hop, not just the expected count.
      val dispatched = relayResult.nodeStates.map(_.asInstanceOf[Long]).sum
      println(f"    verified dispatches (sum of node states): $dispatched%,d (expected ${windows * (hops + 2)}%,d)")
      require(dispatched == windows * (hops + 2), s"relay dispatched $dispatched, expected ${windows * (hops + 2)}")

      // (b) one-node circuit vs componentOf over ~1 M inputs
      val perTick = 10_000
      val ticks   = 100L
      val dense = framed((1L to ticks).flatMap(t => (0 until perTick).map(i => (t, i.toDouble / perTick, i.toLong))), horizon = ticks)
      timed("(b) componentOf (transducer)", dense.size.toLong) {
        drain(ScheduleReleaseTransducer.componentOf(new Simple, RandomSource.KISS.create(1L)), dense)
      }
      timed("(b) one-node circuit", dense.size.toLong) {
        drain(CircuitStage.componentOf[Long, Long, Long](oneNodePlan(ErasedNode.of("simple", new Simple, RandomSource.KISS.create(1L)))), dense)
      }
    finally Await.result(system.terminate(), 1.minute)
