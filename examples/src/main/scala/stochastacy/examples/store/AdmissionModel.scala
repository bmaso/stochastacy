package stochastacy.examples.store

/** Configuration for the admission tier. A hard per-tick capacity: the first `capacityPerTick`
 *  requests to arrive in a tick are admitted, the rest throttled. `admissionLatencyTicks` is the
 *  constant cost of the admission check itself (D-2: no queueing-latency model — the burst-sensitive
 *  observable is the throttle rate, not admission latency). */
final case class AdmissionConfig(
  capacityPerTick:       Int    = 20,
  admissionLatencyTicks: Double = 0.02
)

/** Admission's forward output: either the request is admitted (and carried on to the datastore) or
 *  it is throttled. The wrapped `StoreRequest` lets the admitted branch recover the request without
 *  re-deriving it. */
sealed trait AdmissionOutcome
final case class Admitted(request: StoreRequest) extends AdmissionOutcome
case object Throttled extends AdmissionOutcome

/** Admission's observation vocabulary. `AdmissionDecision` is emitted exactly once per request
 *  (admit or throttle), so its observation count is the request count reaching admission and its
 *  mean is the throttle rate. */
sealed trait AdmissionConsumption
final case class AdmissionLatency(ticks: Double)      extends AdmissionConsumption
final case class AdmissionDecision(throttled: Boolean) extends AdmissionConsumption

/** Bounded per-tick admission state: how many requests have been admitted in the current tick.
 *  Reset to 0 at each tick boundary via `AdmissionSampler.onTick` (the Slice 6a hook). */
final case class AdmissionState(admittedThisTick: Int)
