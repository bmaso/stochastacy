package stochastacy.examples.store

/** Configuration for the service tier (ingress + egress). Latencies are in fractional ticks and
 *  deterministic, mirroring the datastore's style; load-induced latency is the admission component's
 *  job (Slice 6). */
final case class ServiceConfig(
  ingressLatencyTicks: Double = 0.05,
  egressLatencyTicks:  Double = 0.05
)

/** The service tier's observation vocabulary. Stage-agnostic — the runner (Slice 5c) tags a
 *  `ServiceLatency` as ingress vs egress by which stream it arrived on. */
sealed trait ServiceConsumption
final case class ServiceLatency(ticks: Double) extends ServiceConsumption
