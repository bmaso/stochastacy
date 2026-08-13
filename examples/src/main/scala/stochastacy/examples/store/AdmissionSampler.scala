package stochastacy.examples.store

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.core.component.{ComponentSampler, Emission, Scheduled}

/** The admission gate: a load-aware component between ingress and the datastore. It admits the first
 *  `capacityPerTick` requests to arrive in a tick and throttles the rest — the first component whose
 *  behavior depends on *how many* requests have arrived, not just on the current one.
 *
 *  `onTick` resets the per-tick counter at each tick boundary (the Slice 6a enabler, first real use):
 *  the transducer calls it before that tick's requests are sampled, so each tick starts with fresh
 *  capacity. Decisions are made in stream-arrival order within a tick; intra-tick arrival order is
 *  arbitrary, consistent with the stochastic model.
 *
 *  Latency is a constant (D-2): the burst-sensitive observable is the throttle rate — a workload
 *  whose *mean* rate is under capacity still throttles during Poisson bursts, because throttling
 *  keys off the instantaneous per-tick count. Use-case is not set here; the transducer propagates it
 *  from the request's `Timed` envelope onto every emission. */
final class AdmissionSampler(cfg: AdmissionConfig)
    extends ComponentSampler[AdmissionState, StoreRequest, AdmissionOutcome, AdmissionConsumption]:

  def initialState: AdmissionState = AdmissionState(0)

  override def onTick(tick: Long, state: AdmissionState): AdmissionState = AdmissionState(0)

  def sample(
    in:    StoreRequest,
    state: AdmissionState,
    rng:   UniformRandomProvider
  ): Emission[AdmissionState, AdmissionOutcome, AdmissionConsumption] =
    val lat = cfg.admissionLatencyTicks
    if state.admittedThisTick < cfg.capacityPerTick then
      Emission(
        AdmissionState(state.admittedThisTick + 1),
        Scheduled(Admitted(in), lat),
        List(Scheduled(AdmissionLatency(lat), lat), Scheduled(AdmissionDecision(false), lat))
      )
    else
      Emission(
        state,
        Scheduled(Throttled, lat),
        List(Scheduled(AdmissionLatency(lat), lat), Scheduled(AdmissionDecision(true), lat))
      )
