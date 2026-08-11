package stochastacy.examples.store

import stochastacy.core.component.ResidueSummary
import stochastacy.core.stats.Statistics

/** The numbers one store trial exists to produce: final state, run duration, the post-horizon
 *  residue diagnostic, and per-(use-case, metric) statistics (latency p50/p99, throughput, …). */
final case class StoreTrialResult(
  finalState:    StoreState,
  durationTicks: Long,
  residue:       ResidueSummary,
  stats:         Statistics[StoreStatKey]
)
