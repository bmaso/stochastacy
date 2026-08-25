package stochastacy.aws.examples.demo

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.MonteCarlo

/**
 * Runs a [[SingleTableScenario]] as a Monte Carlo ensemble: `scenario.trialCount` reproducible trials from
 * one master seed (via the core [[MonteCarlo]] executor, order-stable and parallelism-independent),
 * aggregating across trials incrementally.
 *
 * Two entry points share one streaming core ([[runStreaming]], which folds each trial into an
 * [[IncrementalAggregator]] and releases it):
 *   - [[run]] additionally collects the per-trial results and returns a [[MonteCarloResult]] — for tests
 *     and gates at bounded sizes;
 *   - [[runToFile]] instead streams each trial's records straight to a JSONL file as it completes and
 *     returns a bounded [[MonteCarloRunReport]] — so a large run's memory stays flat and the file grows
 *     during the run rather than being buffered whole.
 */
final class SingleTableMonteCarloRunner()(using ActorSystem, Materializer, ExecutionContext):

  private val trialRunner = new SingleTableTrialRunner()

  /** The streaming core: run the ensemble, fold each (index-tagged) trial into the aggregator via
   *  `onTrial`, and return the across-trial aggregates. No trials are retained here. */
  private def runStreaming(scenario: SingleTableScenario, masterSeed: Long)(
    onTrial: TrialResult => Unit
  ): Future[IncrementalAggregator] =
    val gsiNames = scenario.globalSecondaryIndexes.map(_.indexName)
    val agg = new IncrementalAggregator(
      MonteCarloAggregation.timeSeriesMetrics(gsiNames),
      MonteCarloAggregation.summaryMetrics(gsiNames)
    )
    MonteCarlo
      .stream(scenario.trialCount, masterSeed, scenario.parallelism)(seed => trialRunner.runTrial(scenario, trialId = 0, seed))
      .zipWithIndex // stream emits in seed order, so index == trial id
      .runForeach { (r, i) =>
        val trial = r.copy(trialId = i.toInt)
        onTrial(trial)
        agg.add(trial)
      }
      .map(_ => agg)

  /** Run the ensemble, collecting per-trial results (bounded-size callers only). */
  def run(scenario: SingleTableScenario, masterSeed: Long): Future[MonteCarloResult] =
    val trials = Vector.newBuilder[TrialResult]
    runStreaming(scenario, masterSeed)(trials.addOne).map { agg =>
      MonteCarloResult(
        scenarioId          = scenario.scenarioId,
        trialCount          = scenario.trialCount,
        trials              = trials.result(),
        aggregateTimeSeries = agg.timeSeries,
        aggregateSummary    = agg.summary
      )
    }

  /** Run the ensemble, streaming per-trial records to `output` as trials complete, then appending the
   *  aggregate records. Returns the bounded across-trial aggregates plus the record count. */
  def runToFile(scenario: SingleTableScenario, masterSeed: Long, output: Path): Future[MonteCarloRunReport] =
    val gsiNames = scenario.globalSecondaryIndexes.map(_.indexName)
    val writer   = JsonlWriter.open(output)
    runStreaming(scenario, masterSeed) { trial =>
      writer.writeAll(JsonlExport.trialRecords(scenario.scenarioId, trial, gsiNames))
    }.map { agg =>
      val aggregateTimeSeries = agg.timeSeries
      val aggregateSummary    = agg.summary
      writer.writeAll(JsonlExport.aggregateRecords(scenario.scenarioId, scenario.trialCount, aggregateTimeSeries, aggregateSummary))
      writer.close()
      MonteCarloRunReport(scenario.scenarioId, scenario.trialCount, aggregateTimeSeries, aggregateSummary, writer.count)
    }.andThen { case Failure(_) => writer.close() }
