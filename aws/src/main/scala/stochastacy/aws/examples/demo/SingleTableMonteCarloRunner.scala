package stochastacy.aws.examples.demo

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.MonteCarlo

/**
 * Runs a [[SingleTableScenario]] as a Monte Carlo ensemble: `scenario.trialCount` reproducible trials from
 * one master seed (via the core [[MonteCarlo]] executor, order-stable and parallelism-independent), then
 * aggregates them across trials.
 */
final class SingleTableMonteCarloRunner()(using ActorSystem, Materializer, ExecutionContext):

  private val trialRunner = new SingleTableTrialRunner()

  def run(scenario: SingleTableScenario, masterSeed: Long): Future[MonteCarloResult] =
    MonteCarlo
      .run(scenario.trialCount, masterSeed, scenario.parallelism)(seed => trialRunner.runTrial(scenario, trialId = 0, seed))
      .map { results =>
        // MonteCarlo hands the callback only a seed; results are order-stable, so index == trial id.
        val trials = results.zipWithIndex.map { (r, i) => r.copy(trialId = i) }
        MonteCarloResult(
          scenarioId          = scenario.scenarioId,
          trialCount          = scenario.trialCount,
          trials              = trials,
          aggregateTimeSeries = MonteCarloAggregation.timeSeries(trials),
          aggregateSummary    = MonteCarloAggregation.summary(trials)
        )
      }
