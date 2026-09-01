package stochastacy.aws.examples.demo

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.MonteCarlo

/**
 * Runs a [[MultiTableScenario]] as a Monte Carlo ensemble: `trialCount` reproducible trials from one master
 * seed (via [[MonteCarlo]].stream), aggregating **per table** across trials. Each table folds into its own
 * [[IncrementalAggregator]] over the base metric set (no per-GSI breakout), matching the legacy multi-table
 * `Table:<name>:…` reporting.
 *
 * Mirrors [[SingleTableMonteCarloRunner]]: [[run]] collects the per-trial results (tests/gates at bounded
 * sizes); [[runToFile]] streams each table's records straight to JSONL as trials complete and returns a
 * bounded [[MultiTableRunReport]].
 */
final class MultiTableMonteCarloRunner()(using ActorSystem, Materializer, ExecutionContext):

  private val trialRunner = new MultiTableTrialRunner()

  /** The streaming core: run the ensemble, fold each trial's per-table results into per-table aggregators
   *  via `onTrial`, and return the per-table aggregates. No trials are retained here. Each table's metric
   *  set is derived from its own spec (per-GSI breakout + provisioned + PITR), so a provisioned/PITR table
   *  reports its full metrics alongside on-demand siblings. */
  private def runStreaming(scenario: MultiTableScenario, masterSeed: Long)(
    onTrial: MultiTableTrialResult => Unit
  ): Future[Vector[TableAggregate]] =
    val aggregators = scenario.tables.map { spec =>
      spec.tableName -> new IncrementalAggregator(
        MonteCarloAggregation.timeSeriesMetrics(spec.gsiNames),
        MonteCarloAggregation.summaryMetrics(spec.gsiNames, spec.usesProvisioning, spec.usesPitr)
      )
    }
    val aggByName   = aggregators.toMap
    MonteCarlo
      .stream(scenario.trialCount, masterSeed, scenario.parallelism)(seed => trialRunner.runTrial(scenario, trialId = 0, seed))
      .zipWithIndex // stream emits in seed order, so index == trial id
      .runForeach { (r, i) =>
        val trial = MultiTableTrialResult(i.toInt, r.perTable.map { (name, tr) => (name, tr.copy(trialId = i.toInt)) })
        onTrial(trial)
        trial.perTable.foreach { (name, tr) => aggByName(name).add(tr) }
      }
      .map(_ => aggregators.map { (name, agg) => TableAggregate(name, agg.timeSeries, agg.summary) })

  /** Run the ensemble, collecting per-trial results (bounded-size callers only). */
  def run(scenario: MultiTableScenario, masterSeed: Long): Future[MultiTableMonteCarloResult] =
    val trials = Vector.newBuilder[MultiTableTrialResult]
    runStreaming(scenario, masterSeed)(trials.addOne).map { perTable =>
      MultiTableMonteCarloResult(scenario.scenarioId, scenario.trialCount, perTable, trials.result())
    }

  /** Run the ensemble, streaming each table's per-trial records to `output` as trials complete, then
   *  appending the per-table aggregate records. Returns the per-table aggregates plus the record count. */
  def runToFile(scenario: MultiTableScenario, masterSeed: Long, output: Path): Future[MultiTableRunReport] =
    val writer     = JsonlWriter.open(output)
    val specByName = scenario.tables.map(s => s.tableName -> s).toMap
    runStreaming(scenario, masterSeed) { trial =>
      trial.perTable.foreach { (name, tr) =>
        val spec = specByName(name)
        writer.writeAll(JsonlExport.tableTrialRecords(scenario.scenarioId, name, tr, spec.gsiNames, spec.usesProvisioning, spec.usesPitr))
      }
    }.map { perTable =>
      perTable.foreach { ta =>
        writer.writeAll(JsonlExport.tableAggregateRecords(scenario.scenarioId, ta.tableName, scenario.trialCount, ta.aggregateTimeSeries, ta.aggregateSummary))
      }
      writer.close()
      MultiTableRunReport(scenario.scenarioId, scenario.trialCount, perTable, writer.count)
    }.andThen { case Failure(_) => writer.close() }
