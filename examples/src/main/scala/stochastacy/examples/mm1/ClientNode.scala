package stochastacy.examples.mm1

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.core.component.{FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, Scheduled}
import stochastacy.sim.SimInstant

/**
 * The paginating client: a session's first page on arrival, then — on each response — the next page with probability
 * `continueProb`, or a [[MM1Fact.SessionCompleted]] fact when the session ends. This is the **feedback** half of the
 * loop: the next request is produced by `onFeedback` from the response, and with no think time it is emitted at the
 * response's own instant, so a whole session can run inside a single tick.
 *
 * Stateless: the session's start time travels on the request and returns on the response, so nothing per-session is
 * held here.
 */
final class ClientNode(config: MM1Config)
    extends LoopbackComponentSampler[Unit, Session, PageResponse, PageRequest, MM1Fact, Nothing]:

  def initialState: Unit = ()

  def sample(in: Session, at: SimInstant, state: Unit, rng: UniformRandomProvider)
      : LoopbackEmission[Unit, PageRequest, MM1Fact, Nothing] =
    LoopbackEmission((), Scheduled(PageRequest(in.id, 1, at.toDouble), 0.0), Nil)

  def onFeedback(fb: PageResponse, at: SimInstant, state: Unit, rng: UniformRandomProvider)
      : FeedbackEmission[Unit, PageRequest, MM1Fact, Nothing] =
    if rng.nextDouble() < config.continueProb then
      val think = config.thinkTimeMean.fold(0.0)(mean => Exponential.draw(1.0 / mean, rng))
      FeedbackEmission((), output = Some(Scheduled(PageRequest(fb.session, fb.page + 1, fb.sessionStartedAt), think)))
    else
      FeedbackEmission((), consumption =
        List(Scheduled(MM1Fact.SessionCompleted(fb.page, fb.sessionStartedAt, at.toDouble - fb.sessionStartedAt), 0.0)))
