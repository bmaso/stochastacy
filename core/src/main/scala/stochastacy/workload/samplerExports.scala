package stochastacy.workload

/** Transitional re-export shim.
 *
 *  The `Sampler` machinery has moved to `stochastacy.core.sampler` as part of v2/phase0
 *  (see docs/roadmaps/v2-phase0.md). These exports keep the `ips`-era workload/AWS code
 *  compiling against the old `stochastacy.workload` names without edits. This file is
 *  deleted when the `ips` code is ported to the new core API in a later phase.
 */
export stochastacy.core.sampler.{
  Sampler,
  StatelessSampler,
  PoissonSampler,
  NormalSampler,
  LogNormalSampler,
  BinomialSampler,
  UniformSampler,
  BernoulliSampler,
  ConstantSampler,
  MappedSampler,
  CombiningSampler,
  TemporalShapeFunctions,
  RandomBurstSampler,
  ErasedSampler
}
