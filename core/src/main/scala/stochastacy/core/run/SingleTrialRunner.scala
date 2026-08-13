package stochastacy.core.run

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.sim.TimedElement

/** The trivial single-trial runner: run a component and report its `ComponentResult` as a
 *  [[TrialResult]], discarding the consumption stream. A convenience over [[TrialRunner]] for
 *  components with no observations to fold; simulators that produce statistics write their own
 *  runner on top of `TrialRunner` (see the store example). */
object SingleTrialRunner:

  def run[S, In, Out, Cons](
    source:        Source[TimedElement[Timed[In]], NotUsed],
    component: Graph[
      FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
      Future[ComponentResult[S]]
    ],
    durationTicks: Long
  )(using system: ActorSystem): Future[TrialResult[S]] =
    given ExecutionContext = system.dispatcher
    TrialRunner
      .run(source, component, Sink.ignore)
      .map { case (cr, _) => TrialResult(cr.finalState, durationTicks, cr.residue) }
