package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{Admit, Emission, InterfaceSampler, Reject, Scheduled}
import stochastacy.core.sampler.{BernoulliSampler, StatelessSampler}

/** A chaos-failure gate: an **independent per-request draw** decides whether to reject the request
 *  (with a 503-style response) or admit it. Unlike the throttle and token-bucket gates, the decision
 *  does *not* depend on arrival volume — it is load-independent, the mechanism that models "the service
 *  just fails sometimes."
 *
 *  Failure is drawn from a `StatelessSampler[Boolean]` (reuse `BernoulliSampler`); the gate threads the
 *  current tick through its state via `onTick`, so a sampler whose probability varies with tick yields
 *  a time-varying failure rate (an incident window). `ChaosGate.constant(p, resp)` is the fixed-rate
 *  special case. The domain supplies the rejection response. */
final class ChaosGate[Req, Resp](
  fail:           StatelessSampler[Boolean],
  rejectResponse: Resp,
  latencyTicks:   Double = 0.0
) extends InterfaceSampler[Long, Req, Resp]:

  def initialState: Long = 0L

  override def onTick(tick: Long, state: Long): Long = tick

  def sample(req: Req, state: Long, rng: UniformRandomProvider) =
    val (failed, _) = fail.sample(state, rng, ())
    if failed then Emission(state, Scheduled(Reject(rejectResponse), latencyTicks), Nil)
    else           Emission(state, Scheduled(Admit(req), latencyTicks), Nil)

object ChaosGate:
  /** Constant failure probability — a special case of the general sampler-driven gate. */
  def constant[Req, Resp](failureProbability: Double, rejectResponse: Resp): ChaosGate[Req, Resp] =
    new ChaosGate(BernoulliSampler(_ => failureProbability), rejectResponse)
