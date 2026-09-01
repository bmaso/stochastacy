package stochastacy.aws.examples.demo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization

/**
 * JSONL export for the Monte Carlo result — one JSON object per line, in the legacy demo's record shape
 * (so the existing Grafana dashboard/queries still bind): `trial-time-series`, `trial-summary`,
 * `aggregate-time-series`, `aggregate-summary`, with the same field and metric names.
 */
sealed trait DemoRecord

object DemoRecord:
  final case class TrialTimeSeries(scenarioId: String, trialId: Int, tick: Long, metric: String, value: BigDecimal,
                                   recordType: String = "trial-time-series") extends DemoRecord
  final case class TrialSummary(scenarioId: String, trialId: Int, metric: String, value: BigDecimal,
                                recordType: String = "trial-summary") extends DemoRecord
  final case class AggregateTimeSeries(scenarioId: String, trialCount: Int, tick: Long, metric: String,
                                       statistic: String, value: BigDecimal,
                                       recordType: String = "aggregate-time-series") extends DemoRecord
  final case class AggregateSummary(scenarioId: String, trialCount: Int, metric: String, statistic: String,
                                    value: BigDecimal, recordType: String = "aggregate-summary") extends DemoRecord

object JsonlExport:
  private given DefaultFormats = DefaultFormats

  /** The per-trial records (time series then summary) for ONE trial, given the ensemble's GSI column set.
   *  This is the streaming unit — the writer serializes a trial's records as it completes, never holding
   *  more than one trial's worth. */
  def trialRecords(scenarioId: String, trial: TrialResult, gsiNames: Vector[String], provisioned: Boolean = false, pitr: Boolean = false): Vector[DemoRecord] =
    val ts = trial.timeSeries.flatMap { point =>
      MonteCarloAggregation.timeSeriesMetrics(gsiNames).map { (name, extract) =>
        DemoRecord.TrialTimeSeries(scenarioId, trial.trialId, point.tick, name, extract(point))
      }
    }
    val summary = MonteCarloAggregation.summaryMetrics(gsiNames, provisioned, pitr).map { (name, extract) =>
      DemoRecord.TrialSummary(scenarioId, trial.trialId, name, extract(trial.summary))
    }
    ts ++ summary

  /** The aggregate records (time series then summary) for the ensemble. */
  def aggregateRecords(
    scenarioId:          String,
    trialCount:          Int,
    aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
    aggregateSummary:    Vector[AggregateSummaryValue]
  ): Vector[DemoRecord] =
    aggregateTimeSeries.map { p =>
      DemoRecord.AggregateTimeSeries(scenarioId, trialCount, p.tick, p.metric, p.statistic.exportName, p.value)
    } ++ aggregateSummary.map { s =>
      DemoRecord.AggregateSummary(scenarioId, trialCount, s.metric, s.statistic.exportName, s.value)
    }

  /** The per-trial records for ONE table of a multi-table trial: the **base** metrics only (no per-GSI
   *  breakout), each named `Table:<tableName>:<metric>` — the legacy multi-table record shape. */
  def tableTrialRecords(scenarioId: String, tableName: String, trial: TrialResult,
                        gsiNames: Vector[String] = Vector.empty, provisioned: Boolean = false, pitr: Boolean = false): Vector[DemoRecord] =
    val ts = trial.timeSeries.flatMap { point =>
      MonteCarloAggregation.timeSeriesMetrics(gsiNames).map { (name, extract) =>
        DemoRecord.TrialTimeSeries(scenarioId, trial.trialId, point.tick, s"Table:$tableName:$name", extract(point))
      }
    }
    val summary = MonteCarloAggregation.summaryMetrics(gsiNames, provisioned, pitr).map { (name, extract) =>
      DemoRecord.TrialSummary(scenarioId, trial.trialId, s"Table:$tableName:$name", extract(trial.summary))
    }
    ts ++ summary

  /** The aggregate records for ONE table of a multi-table ensemble, named `Table:<tableName>:<metric>`. */
  def tableAggregateRecords(
    scenarioId:          String,
    tableName:           String,
    trialCount:          Int,
    aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
    aggregateSummary:    Vector[AggregateSummaryValue]
  ): Vector[DemoRecord] =
    aggregateTimeSeries.map { p =>
      DemoRecord.AggregateTimeSeries(scenarioId, trialCount, p.tick, s"Table:$tableName:${p.metric}", p.statistic.exportName, p.value)
    } ++ aggregateSummary.map { s =>
      DemoRecord.AggregateSummary(scenarioId, trialCount, s"Table:$tableName:${s.metric}", s.statistic.exportName, s.value)
    }

  /** Serialize one record to its JSONL line (no trailing newline). */
  def line(record: DemoRecord): String = Serialization.write(record)

  /** The records for a collected result, in a deterministic order: per-trial first (trial order), then
   *  aggregates. Batch convenience over the granular builders (used by tests). */
  def records(result: MonteCarloResult): Vector[DemoRecord] =
    val gsiNames    = MonteCarloAggregation.gsiNames(result.trials)
    val provisioned = MonteCarloAggregation.hasProvisioning(result.trials)
    val pitr        = MonteCarloAggregation.hasPitr(result.trials)
    result.trials.flatMap(trialRecords(result.scenarioId, _, gsiNames, provisioned, pitr)) ++
      aggregateRecords(result.scenarioId, result.trialCount, result.aggregateTimeSeries, result.aggregateSummary)

  def render(result: MonteCarloResult): String =
    val rs = records(result)
    rs.map(line).mkString("", "\n", if rs.nonEmpty then "\n" else "")

  def write(path: Path, result: MonteCarloResult): Unit =
    Files.writeString(path, render(result), StandardCharsets.UTF_8)
