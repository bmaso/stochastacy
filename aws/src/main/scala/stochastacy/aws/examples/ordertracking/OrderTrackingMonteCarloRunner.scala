package stochastacy.aws.examples.ordertracking

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.MonteCarlo

/**
 * Runs the Order-Tracking scenario as a Monte Carlo ensemble: `config.trialCount` reproducible trials
 * from one master seed (via the core [[MonteCarlo]] executor, order-stable and parallelism-independent),
 * then aggregates them across trials.
 */
final class OrderTrackingMonteCarloRunner()(using ActorSystem, Materializer, ExecutionContext):

  private val trialRunner = new OrderTrackingTrialRunner()

  def run(config: OrderTrackingConfig, masterSeed: Long): Future[OrderTrackingMonteCarloResult] =
    MonteCarlo
      .run(config.trialCount, masterSeed, config.parallelism)(seed => trialRunner.runTrial(config, trialId = 0, seed))
      .map { results =>
        // MonteCarlo hands the callback only a seed; results are order-stable, so index == trial id.
        val trials = results.zipWithIndex.map { (r, i) => r.copy(trialId = i) }
        OrderTrackingMonteCarloResult(
          scenarioId          = config.scenarioId,
          trialCount          = config.trialCount,
          trials              = trials,
          aggregateTimeSeries = MonteCarloAggregation.timeSeries(trials),
          aggregateSummary    = MonteCarloAggregation.summary(trials)
        )
      }
