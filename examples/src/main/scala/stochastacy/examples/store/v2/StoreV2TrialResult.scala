package stochastacy.examples.store.v2

import stochastacy.core.component.ResidueSummary
import stochastacy.core.stats.Statistics
import stochastacy.examples.store.{StoreResponse, StoreStatKey, StoreState}

/** The numbers one Store Demo V2 trial produces: the datastore's final state and post-horizon residue
 *  (its materialized value, preserved through the interface wrap); per-`(usecase, metric, window)`
 *  statistics folding the datastore's consumption *and* the edge's terminal outcomes (`outcome.served`
 *  / `outcome.throttled` / `outcome.chaos`, each 0/1 so its mean is a rate); and every client response
 *  — served responses and rejections alike, since a rejection is an in-band `StoreResponse`. */
final case class StoreV2TrialResult(
  finalState:    StoreState,
  durationTicks: Long,
  residue:       ResidueSummary,
  stats:         Statistics[StoreStatKey],
  responses:     Vector[StoreResponse]
)
