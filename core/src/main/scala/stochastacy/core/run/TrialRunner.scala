package stochastacy.core.run

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import stochastacy.core.component.Timed
import stochastacy.sim.TimedElement

/** Run *plumbing* for a single trial — a base type for constructing a problem-specific runner, not a
 *  runner that dictates what to do with observations.
 *
 *  It materializes `source → component`, drains the response stream, and hands the **consumption
 *  stream to a caller-supplied `Sink`**, returning the component's materialized result alongside that
 *  sink's materialized value. The component may be any timed component — a `ScheduleReleaseTransducer`
 *  stage (result `ComponentResult`), a circuit (result `CircuitResult`), or a wrapped composition; the
 *  runner passes its result through untouched. What the consumption sink computes — statistics, nothing,
 *  a custom reduction — is entirely the caller's concern. */
object TrialRunner:

  def run[In, Out, Cons, R, M](
    source: Source[TimedElement[Timed[In]], NotUsed],
    component: Graph[
      FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
      Future[R]
    ],
    consumptionSink: Sink[TimedElement[Timed[Cons]], Future[M]]
  )(using system: ActorSystem): Future[(R, M)] =
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(component, consumptionSink)((c, m) => (c, m)) { implicit b => (comp, cons) =>
        import GraphDSL.Implicits.*
        val src = b.add(source)
        src ~> comp.in
        comp.out0 ~> b.add(Sink.ignore)
        comp.out1 ~> cons
        ClosedShape
      }
    )
    given ExecutionContext = system.dispatcher
    val (compF, mF) = graph.run()
    for c <- compF; m <- mF yield (c, m)
