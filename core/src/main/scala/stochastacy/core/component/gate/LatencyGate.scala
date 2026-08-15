package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{Admit, Emission, InterfaceSampler, Scheduled}
import stochastacy.core.sampler.{ConstantSampler, StatelessSampler}

/** A latency gate: admits *every* request and adds a latency drawn per request from a distribution.
 *  The pure decorator — it never rejects — so it exercises the interface's admit-only path.
 *
 *  Latency is a `StatelessSampler[Double]` (fractional ticks), so real workloads use a distribution
 *  (e.g. `LogNormalSampler.constant(mu, sigma)`); constant latency is the [[LatencyGate.constant]]
 *  special case. The gate threads the current tick through its state via `onTick`, so a sampler whose
 *  parameters vary with tick yields time-varying latency (load- or time-of-day-dependent). Draws are
 *  clamped to `>= 0` — latency cannot be negative. */
final class LatencyGate[Req, Resp](latency: StatelessSampler[Double])
    extends InterfaceSampler[Long, Req, Resp]:

  def initialState: Long = 0L

  override def onTick(tick: Long, state: Long): Long = tick

  def sample(req: Req, state: Long, rng: UniformRandomProvider) =
    val (drawn, _) = latency.sample(state, rng, ())
    Emission(state, Scheduled(Admit(req), math.max(0.0, drawn)), Nil)

object LatencyGate:
  /** Constant latency — a special case of the general sampler-driven gate. */
  def constant[Req, Resp](latencyTicks: Double): LatencyGate[Req, Resp] =
    new LatencyGate(ConstantSampler(latencyTicks))
