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

  /** The records for a result, in a deterministic order: per-trial first (trial order), then aggregates. */
  def records(result: MonteCarloResult): Vector[DemoRecord] =
    val gsiNames         = MonteCarloAggregation.gsiNames(result.trials)
    val timeSeriesMetrics = MonteCarloAggregation.timeSeriesMetrics(gsiNames)
    val summaryMetrics    = MonteCarloAggregation.summaryMetrics(gsiNames)
    val trialRecords =
      result.trials.flatMap { trial =>
        val ts = trial.timeSeries.flatMap { point =>
          timeSeriesMetrics.map { (name, extract) =>
            DemoRecord.TrialTimeSeries(result.scenarioId, trial.trialId, point.tick, name, extract(point))
          }
        }
        val summary = summaryMetrics.map { (name, extract) =>
          DemoRecord.TrialSummary(result.scenarioId, trial.trialId, name, extract(trial.summary))
        }
        ts ++ summary
      }

    val aggregateRecords =
      result.aggregateTimeSeries.map { p =>
        DemoRecord.AggregateTimeSeries(result.scenarioId, result.trialCount, p.tick, p.metric, p.statistic.exportName, p.value)
      } ++ result.aggregateSummary.map { s =>
        DemoRecord.AggregateSummary(result.scenarioId, result.trialCount, s.metric, s.statistic.exportName, s.value)
      }

    trialRecords ++ aggregateRecords

  def render(result: MonteCarloResult): String =
    val rs = records(result)
    rs.map(Serialization.write(_)).mkString("", "\n", if rs.nonEmpty then "\n" else "")

  def write(path: Path, result: MonteCarloResult): Unit =
    Files.writeString(path, render(result), StandardCharsets.UTF_8)
