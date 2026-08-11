package stochastacy.core.run

import stochastacy.core.component.ResidueSummary

/** The result of one Monte Carlo trial — the materialized value of a single simulation run.
 *
 *  Skeleton form for Slice 3: final component state, simulated duration, and the post-horizon
 *  residue diagnostic. Enriched with observation statistics (Slice 4) and cross-trial aggregation
 *  (Slice 7). A single `S` suffices while there is one stateful component; multi-component runs
 *  generalize `finalState` to a keyed map (Slice 5). */
final case class TrialResult[S](
  finalState:    S,
  durationTicks: Long,
  residue:       ResidueSummary
)
