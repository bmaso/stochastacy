package stochastacy.examples.store

/** Stochastic parameters and cost-model coefficients for [[StoreSampler]].
 *
 *  All fields default to plausible values so tests can override just the one dimension under test.
 *  Latency is expressed in **fractional ticks** (the transducer stamps timing from these delays).
 *  Latency is modeled as a deterministic function of work; latency *variance* emerges from
 *  `evaluated` variance (selectivity draws, deep pagination). Distributional jitter and
 *  load-induced latency are deferred (see docs/roadmaps/v2-phase0.md). */
final case class StoreConfig(
  // --- outcome probabilities ---
  hitRate:    Double = 0.9, // Get / Delete hit probability
  createRate: Double = 0.3, // Put create-vs-update probability
  errorRate:  Double = 0.0, // per-request system-error probability

  // --- selectivity realization laws ---
  categoryFraction: Double = 0.40, // CategoryFilter ≈ fraction of entityCount
  pointLookupMean:  Double = 3.0,  // PointLookup ≈ constant count (Poisson mean)

  // --- latency model (fractional ticks) ---
  pointLatency:           Double = 0.05,
  writeLatency:           Double = 0.10,
  queryBaseLatency:       Double = 0.20,
  reportBaseLatency:      Double = 0.30,
  errorLatency:           Double = 0.02,
  latencyPerEvaluatedItem: Double = 1.0e-5,
  sortPenaltyPerItem:      Double = 2.0e-5,

  // --- report ---
  meanGroupBytes: Long = 256L,

  // --- initial state ---
  initialEntities: Long = 100_000L,
  meanEntityBytes: Long = 1_024L
)
