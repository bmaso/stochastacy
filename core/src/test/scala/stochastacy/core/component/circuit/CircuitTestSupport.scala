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
