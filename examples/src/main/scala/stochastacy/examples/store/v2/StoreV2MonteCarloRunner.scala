package stochastacy.examples.store.v2

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import stochastacy.core.run.MonteCarlo
import stochastacy.examples.store.{ApiWorkloadConfig, StoreConfig, StoreMonteCarloResult}

/** Runs a Monte Carlo ensemble of the Store Demo V2 edge — `trialCount` independent trials from one
 *  master seed — and aggregates into a [[StoreMonteCarloResult]] (reused from the original demo). Each
 *  trial is projected to its statistics immediately, so the ensemble never retains full trial results.
 *  Deterministic given `masterSeed`, and identical for any `parallelism`. */
object StoreV2MonteCarloRunner:

  def run(
    apiCfg:          ApiWorkloadConfig,
    storeCfg:        StoreConfig,
    edge:            EdgeConfig,
    masterSeed:      Long,
    simulationTicks: Long,
    trialCount:      Int,
    parallelism:     Int  = 4,
    requestTicks:    Long = -1L,
    windowTicks:     Long = Long.MaxValue
  )(using system: ActorSystem): Future[StoreMonteCarloResult] =
    given ExecutionContext = system.dispatcher
    MonteCarlo.run(trialCount, masterSeed, parallelism) { seed =>
      StoreV2TrialRunner.run(apiCfg, storeCfg, edge, seed, simulationTicks, requestTicks, windowTicks).map(_.stats)
    }.map(StoreMonteCarloResult(trialCount, _))
