package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{Admit, Emission, InterfaceSampler, Reject, Scheduled}

/** A flat per-tick rate gate: admits the first `capacityPerTick` requests to arrive in a tick and
 *  rejects the rest, resetting the counter at each tick boundary. The domain supplies the response a
 *  rejection returns (e.g. a 429). Generic over the request/response types — carries no domain
 *  knowledge beyond that one response value. The generic form of the store demo's `AdmissionSampler`.
 *
 *  Because the cap is instantaneous per tick, a workload whose *mean* rate is under capacity still
 *  throttles during bursts — throttling keys off the per-tick count, not the mean. */
final class FlatThrottleGate[Req, Resp](
  capacityPerTick: Int,
  rejectResponse:  Resp,
  latencyTicks:    Double = 0.0
) extends InterfaceSampler[FlatThrottleGate.State, Req, Resp]:

  def initialState: FlatThrottleGate.State = FlatThrottleGate.State(0)

  override def onTick(tick: Long, state: FlatThrottleGate.State): FlatThrottleGate.State =
    FlatThrottleGate.State(0)

  def sample(req: Req, state: FlatThrottleGate.State, rng: UniformRandomProvider) =
    if state.admittedThisTick < capacityPerTick then
      Emission(FlatThrottleGate.State(state.admittedThisTick + 1), Scheduled(Admit(req), latencyTicks), Nil)
    else
      Emission(state, Scheduled(Reject(rejectResponse), latencyTicks), Nil)

object FlatThrottleGate:
  final case class State(admittedThisTick: Int)
