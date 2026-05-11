package stochastacy.demo

final case class DemoExportBundle(
                                   trials: Vector[TrialResult],
                                   aggregate: MonteCarloResult,
                                   records: Vector[DemoExportRecord]
                                 )

object DemoReportBuilder:
  def build(trials: Vector[TrialResult]): DemoExportBundle =
    val aggregate = MonteCarloAggregator.aggregate(trials)
    val windowedTrialRecords =
      WindowSizeSeconds.phase1Values.flatMap { windowSize =>
        trials.flatMap { trial =>
          DemoExportRecord.fromWindowedTrialTimeSeries(
            scenarioId = trial.scenarioId,
            trialId = trial.trialId,
            points = TimeWindowRollups.rollupTrialTimeSeries(trial.timeSeries, windowSize)
          ).collect {
            case record: DemoExportRecord.TrialWindowTimeSeriesRecord => record
          }
        }
      }
    val windowedAggregateRecords =
      WindowSizeSeconds.phase1Values.flatMap { windowSize =>
        DemoExportRecord.fromAggregatedWindowedTimeSeries(
          scenarioId = aggregate.scenarioId,
          trialCount = aggregate.trialCount,
          points = TimeWindowRollups.aggregateWindowedTrials(trials, windowSize)
        ).collect {
          case record: DemoExportRecord.AggregateWindowTimeSeriesRecord => record
        }
      }

    val trialTimeSeriesRecords =
      trials.flatMap { trial =>
        DemoExportRecord.fromTrialResult(trial).collect {
          case record: DemoExportRecord.TrialTimeSeriesRecord => record
        }
      }
    val aggregateTimeSeriesRecords =
      DemoExportRecord.fromMonteCarloResult(aggregate).collect {
        case record: DemoExportRecord.AggregateTimeSeriesRecord => record
      }
    val trialSummaryRecords =
      trials.flatMap { trial =>
        DemoExportRecord.fromTrialResult(trial).collect {
          case record: DemoExportRecord.TrialSummaryRecord => record
        }
      }
    val aggregateSummaryRecords =
      DemoExportRecord.fromMonteCarloResult(aggregate).collect {
        case record: DemoExportRecord.AggregateSummaryRecord => record
      }

    DemoExportBundle(
      trials = trials,
      aggregate = aggregate,
      records =
        trialTimeSeriesRecords ++
          aggregateTimeSeriesRecords ++
          trialSummaryRecords ++
          aggregateSummaryRecords ++
          windowedTrialRecords ++
          windowedAggregateRecords
    )
