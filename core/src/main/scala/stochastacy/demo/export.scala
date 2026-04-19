package stochastacy.demo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization

sealed trait DemoExportRecord:
  def recordType: String

object DemoExportRecord:
  final case class TrialTimeSeriesRecord(
                                          recordType: String = "trial-time-series",
                                          scenarioId: String,
                                          trialId: Int,
                                          tick: Long,
                                          metric: String,
                                          value: BigDecimal
                                        ) extends DemoExportRecord

  final case class TrialWindowTimeSeriesRecord(
                                                recordType: String = "trial-window-time-series",
                                                scenarioId: String,
                                                trialId: Int,
                                                windowSizeSeconds: Int,
                                                windowStartTick: Long,
                                                metric: String,
                                                value: BigDecimal
                                              ) extends DemoExportRecord

  final case class AggregateTimeSeriesRecord(
                                              recordType: String = "aggregate-time-series",
                                              scenarioId: String,
                                              trialCount: Int,
                                              tick: Long,
                                              metric: String,
                                              statistic: String,
                                              value: BigDecimal
                                            ) extends DemoExportRecord

  final case class AggregateWindowTimeSeriesRecord(
                                                    recordType: String = "aggregate-window-time-series",
                                                    scenarioId: String,
                                                    trialCount: Int,
                                                    windowSizeSeconds: Int,
                                                    windowStartTick: Long,
                                                    metric: String,
                                                    statistic: String,
                                                    value: BigDecimal
                                                  ) extends DemoExportRecord

  final case class TrialSummaryRecord(
                                       recordType: String = "trial-summary",
                                       scenarioId: String,
                                       trialId: Int,
                                       metric: String,
                                       value: BigDecimal
                                     ) extends DemoExportRecord

  final case class AggregateSummaryRecord(
                                           recordType: String = "aggregate-summary",
                                           scenarioId: String,
                                           trialCount: Int,
                                           metric: String,
                                           statistic: String,
                                           value: BigDecimal
                                         ) extends DemoExportRecord

  def fromTrialResult(trial: TrialResult): Vector[DemoExportRecord] =
    trial.timeSeries.map { point =>
      TrialTimeSeriesRecord(
        scenarioId = trial.scenarioId,
        trialId = trial.trialId,
        tick = point.tick,
        metric = point.metric.toString,
        value = point.value
      )
    } ++ trial.summary.map { summary =>
      TrialSummaryRecord(
        scenarioId = trial.scenarioId,
        trialId = trial.trialId,
        metric = summary.metric.toString,
        value = summary.value
      )
    }

  def fromMonteCarloResult(result: MonteCarloResult): Vector[DemoExportRecord] =
    result.timeSeries.map { point =>
      AggregateTimeSeriesRecord(
        scenarioId = result.scenarioId,
        trialCount = result.trialCount,
        tick = point.tick,
        metric = point.metric.toString,
        statistic = point.statistic.exportName,
        value = point.value
      )
    } ++ result.summary.map { summary =>
      AggregateSummaryRecord(
        scenarioId = result.scenarioId,
        trialCount = result.trialCount,
        metric = summary.metric.toString,
        statistic = summary.statistic.exportName,
        value = summary.value
      )
    }

  def fromWindowedTrialTimeSeries(
                                   scenarioId: String,
                                   trialId: Int,
                                   points: Vector[WindowedTimeSeriesPoint]
                                 ): Vector[DemoExportRecord] =
    points.map { point =>
      TrialWindowTimeSeriesRecord(
        scenarioId = scenarioId,
        trialId = trialId,
        windowSizeSeconds = point.windowSizeSeconds,
        windowStartTick = point.windowStartTick,
        metric = point.metric.toString,
        value = point.value
      )
    }

  def fromAggregatedWindowedTimeSeries(
                                        scenarioId: String,
                                        trialCount: Int,
                                        points: Vector[AggregatedWindowedTimeSeriesPoint]
                                      ): Vector[DemoExportRecord] =
    points.map { point =>
      AggregateWindowTimeSeriesRecord(
        scenarioId = scenarioId,
        trialCount = trialCount,
        windowSizeSeconds = point.windowSizeSeconds,
        windowStartTick = point.windowStartTick,
        metric = point.metric.toString,
        statistic = point.statistic.exportName,
        value = point.value
      )
    }

object DemoJsonlExporter:
  private given DefaultFormats = DefaultFormats

  def render(records: Seq[DemoExportRecord]): String =
    records.map(record => Serialization.write(record)).mkString("", "\n", if records.nonEmpty then "\n" else "")

  def write(path: Path, records: Seq[DemoExportRecord]): Unit =
    Files.writeString(path, render(records), StandardCharsets.UTF_8)
