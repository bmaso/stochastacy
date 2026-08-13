package stochastacy.examples.store

import stochastacy.core.component.ResidueSummary
import stochastacy.core.stats.Statistics

/** The numbers one store trial exists to produce: the datastore's final state, run duration, its
 *  post-horizon residue diagnostic, per-(use-case, metric) statistics across all pipeline stages
 *  (`ingress.latency` / `latency` / `egress.latency`, throughput, …), and the collected client
 *  responses.
 *
 *  `residue` reflects the (stateful) datastore stage; the stateless ingress/egress residues are not
 *  aggregated. Collecting every `ApiResponse` is fine here (small example) and buys 1:1 integrity
 *  checks; on larger examples it would be a deliberate, cost-bearing choice rather than a default. */
final case class StoreTrialResult(
  finalState:    StoreState,
  durationTicks: Long,
  residue:       ResidueSummary,
  stats:         Statistics[StoreStatKey],
  responses:     Vector[ApiResponse]
)
