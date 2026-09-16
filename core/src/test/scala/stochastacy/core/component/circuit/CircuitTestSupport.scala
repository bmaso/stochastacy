package stochastacy.core.component.circuit

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}

import stochastacy.core.component.Timed
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedElement}

/** Shared helpers for the circuit specs and benchmark: run any `in → (forward, consumption)` component over an input
 *  stream, build framed inputs, and wire the one-node anchor plan. */
object CircuitTestSupport:

  type Component[In, Out, Cons, M] =
    Graph[FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]], Future[M]]

  final case class Outcome[Out, Cons, M](mat: M, fwd: Seq[TimedElement[Timed[Out]]], cons: Seq[TimedElement[Timed[Cons]]])

  /** Materialize `component` over `input`, collecting both planes; returns the three futures (for failure tests). */
  def runFutures[In, Out, Cons, M](component: Component[In, Out, Cons, M], input: Seq[TimedElement[Timed[In]]])(using
    ActorSystem
  ): (Future[M], Future[Seq[TimedElement[Timed[Out]]]], Future[Seq[TimedElement[Timed[Cons]]]]) =
    RunnableGraph.fromGraph(
      GraphDSL.createGraph(component, Sink.seq[TimedElement[Timed[Out]]], Sink.seq[TimedElement[Timed[Cons]]])((_, _, _)) {
        implicit b => (c, fwdSink, consSink) =>
          import GraphDSL.Implicits.*
          b.add(Source(input.toVector)) ~> c.in
          c.out0 ~> fwdSink.in
          c.out1 ~> consSink.in
          ClosedShape
      }
    ).run()

  /** Materialize `component` over `input` and await its materialized value and both collected planes. */
  def runCircuit[In, Out, Cons, M](component: Component[In, Out, Cons, M], input: Seq[TimedElement[Timed[In]]],
                            timeout: FiniteDuration = 30.seconds)(using ActorSystem): Outcome[Out, Cons, M] =
    val (m, f, c) = runFutures(component, input)
    Outcome(Await.result(m, timeout), Await.result(f, timeout), Await.result(c, timeout))

  /** Frame `(tick, intraTick, payload)` inputs — ticks nondecreasing, as `TickFraming` requires — over `[1, horizon]`. */
  def framed[In](inputs: Seq[(Long, Double, In)], horizon: Long, usecase: Any = "uc"): Vector[TimedElement[Timed[In]]] =
    TickFraming.frame(inputs.iterator.map((t, i, p) => Timed(p, SimTime.of(t), i, usecase)), horizon).toVector

  /** The anchor wiring: input → node `In`; node `Out` → forward outlet; node `Consumption` → consumption outlet. */
  def oneNodePlan(node: ErasedNode): CircuitPlan =
    CircuitPlan(
      Vector(node),
      Map(
        RouteSource.CircuitInput                           -> Vector(Route(RouteTarget.NodePort(0, Port.In))),
        RouteSource.NodePlane(0, CircuitPlane.Out)         -> Vector(Route(RouteTarget.ForwardOutlet)),
        RouteSource.NodePlane(0, CircuitPlane.Consumption) -> Vector(Route(RouteTarget.ConsumptionOutlet))
      )
    )

  def timedOnly[E](s: Seq[TimedElement[Timed[E]]]): Seq[Timed[E]] =
    s.collect { case x: Timed[E] @unchecked => x }

  // --- a shared anchor fixture: RNG-drawn delays over dense, within-tick-sorted input ---

  final case class AnchorReq(n: Int)
  final case class AnchorResp(id: Int)
  final case class AnchorCons(kind: String)

  /** Response and consumption delays drawn from the node's RNG; state counts requests. */
  final class RandomLatencyToy extends stochastacy.core.component.ComponentSampler[Int, AnchorReq, AnchorResp, AnchorCons]:
    def initialState: Int = 0
    def sample(in: AnchorReq, at: stochastacy.sim.SimInstant, s: Int, rng: org.apache.commons.rng.UniformRandomProvider)
        : stochastacy.core.component.Emission[Int, AnchorResp, AnchorCons] =
      stochastacy.core.component.Emission(s + 1,
        stochastacy.core.component.Scheduled(AnchorResp(s), rng.nextDouble() * 2.5),
        List(stochastacy.core.component.Scheduled(AnchorCons("work"), rng.nextDouble())))

  /** 400 requests, 20 per tick, sorted within each tick, over 25 ticks. */
  val denseAnchorInput: Vector[TimedElement[Timed[AnchorReq]]] =
    framed((1 to 400).map(i => (((i - 1) / 20 + 1).toLong, ((i - 1) % 20) / 20.0 + 0.01, AnchorReq(i))), horizon = 25L)
