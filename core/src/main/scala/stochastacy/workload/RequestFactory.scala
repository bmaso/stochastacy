package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.sim.TimedEvent

/**
 * Mints a single request of type `Req`.
 *
 * This is the extension point that makes the workload arrival layer usable from outside
 * this repository: a downstream project defines its own request type and its own
 * `RequestFactory` implementations, and reuses `WorkloadRequestStream` — and with it the
 * arrival protocol (`Tick` framing, the `EndOfTime` terminal sentinel, independent RNG
 * streams, and the intra-tick arrival draw) — unchanged.
 *
 * Note the return type is `Req`, NOT `TimedElement[Req]`. A factory only ever produces
 * business events; interleaving `Tick` and `EndOfTime` control events is the job of
 * `WorkloadRequestStream`. Widening to `TimedElement[Req]` is automatic at the call sites,
 * since `TimedElement[X] = X | TimedControlEvent`.
 *
 * Implementations are values held inside a `WorkloadFlow`, so they carry their own
 * parameter-sampling configuration (for example, an item-size sampler). They are expected
 * to be immutable and safe to call repeatedly; all per-call variation arrives through
 * `tick`, `rng`, and `intraTick`.
 */
trait RequestFactory[Req <: TimedEvent]:

  /**
   * @param tick      the tick window this request arrives in
   * @param usecase   use-case tag stamped on the request, inherited from the workload
   * @param flowId    id of the originating flow, so downstream responses can be attributed
   * @param rng       parameter RNG for this flow — independent of rate and arrival RNGs
   * @param intraTick arrival position within the tick, drawn from Uniform(0, 1)
   */
  def build(
    tick:      Long,
    usecase:   String,
    flowId:    String,
    rng:       UniformRandomProvider,
    intraTick: Double
  ): Req
