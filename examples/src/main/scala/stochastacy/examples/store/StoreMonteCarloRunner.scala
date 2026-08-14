package stochastacy.examples.store

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import stochastacy.core.run.MonteCarlo

/** Runs a Monte Carlo ensemble of the store pipeline: `trialCount` independent single trials from one
 *  master seed, aggregated into a [[StoreMonteCarloResult]]. The store's problem-specific MC entry
 *  point — the generic [[MonteCarlo]] executor owns fan-out/seeding/parallelism; this owns the thin
 *  "reduce each trial to its statistics" step and hands them to the result for aggregation.
 *
 *  Each trial is projected to its `Statistics` immediately, so the ensemble never retains the trials'
 *  full result objects (with their response vectors). Deterministic given `masterSeed`, and identical
 *  for any `parallelism`. */
object StoreMonteCarloRunner:

  def run(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    serviceCfg:      ServiceConfig,
    masterSeed:      Long,
    simulationTicks: Long,
    trialCount:      Int,
    admissionCfg:    AdmissionConfig = AdmissionConfig(),
    parallelism:     Int             = 4,
    requestTicks:    Long            = -1L,
    windowTicks:     Long            = Long.MaxValue
  )(using system: ActorSystem): Future[StoreMonteCarloResult] =
    given ExecutionContext = system.dispatcher
    MonteCarlo.run(trialCount, masterSeed, parallelism) { seed =>
      StoreTrialRunner
        .run(apiCfg, storeCfg, serviceCfg, seed, simulationTicks, admissionCfg, requestTicks, windowTicks)
        .map(_.stats)
    }.map(StoreMonteCarloResult(trialCount, _))
