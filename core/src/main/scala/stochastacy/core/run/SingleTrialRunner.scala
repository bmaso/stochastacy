package stochastacy.core.run

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.sim.{TimedElement, TimedEvent}

/** Runs a single simulation trial: materialize a `source → component` graph, drain the output
 *  streams, and produce the component's materialized `ComponentResult` as a [[TrialResult]].
 *
 *  One materialized run = one Monte Carlo trial — the unit the multi-trial executor (Slice 7) will
 *  collect N of. The component's response/consumption streams are drained (Slice 4 folds them into
 *  observation statistics). */
object SingleTrialRunner:

  def run[S, Req <: TimedEvent, Resp, Cons](
    source:        Source[TimedElement[Req], NotUsed],
    component: Graph[
      FanOutShape2[TimedElement[Req], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]],
      Future[ComponentResult[S]]
    ],
    durationTicks: Long
  )(using system: ActorSystem): Future[TrialResult[S]] =
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(component) { implicit b => comp =>
        import GraphDSL.Implicits.*
        val src = b.add(source)
        src ~> comp.in
        comp.out0 ~> b.add(Sink.ignore)
        comp.out1 ~> b.add(Sink.ignore)
        ClosedShape
      }
    )
    given ExecutionContext = system.dispatcher
    graph.run().map(cr => TrialResult(cr.finalState, durationTicks, cr.residue))
