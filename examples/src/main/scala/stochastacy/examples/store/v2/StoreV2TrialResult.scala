package stochastacy.examples.store.v2

import stochastacy.core.component.ResidueSummary
import stochastacy.examples.store.{StoreResponse, StoreState}

/** The numbers one Store Demo V2 trial produces: the datastore's final state and post-horizon residue
 *  (its materialized value, preserved through the interface wrap), plus every client response — served
 *  responses and rejections alike, since a rejection is an in-band `StoreResponse`. Minimal by design:
 *  Slice 1 proves the interface component; statistics and reporting arrive in later slices. */
final case class StoreV2TrialResult(
  finalState:    StoreState,
  durationTicks: Long,
  residue:       ResidueSummary,
  responses:     Vector[StoreResponse]
)
