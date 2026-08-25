package stochastacy.aws.examples.demo

/** The across-trial statistics emitted for each metric — matching the legacy demo's set. */
enum AggregateStatistic:
  case Mean
  case StdDev

  def exportName: String = this match
    case AggregateStatistic.Mean   => "mean"
    case AggregateStatistic.StdDev => "stddev"

/** One aggregated per-tick metric value across the ensemble. */
final case class AggregateTimeSeriesPoint(
  tick:      Long,
  metric:    String,
  statistic: AggregateStatistic,
  value:     BigDecimal
)

/** One aggregated summary metric value across the ensemble. */
final case class AggregateSummaryValue(
  metric:    String,
  statistic: AggregateStatistic,
  value:     BigDecimal
)

/** The result of a Monte Carlo ensemble: the per-trial results plus their across-trial aggregates. Used by
 *  the collecting `run` (tests/gates at bounded sizes); the streaming `runToFile` returns a
 *  [[MonteCarloRunReport]] instead, holding no per-trial data. */
final case class MonteCarloResult(
  scenarioId:          String,
  trialCount:          Int,
  trials:              Vector[TrialResult],
  aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
  aggregateSummary:    Vector[AggregateSummaryValue]
)

/** The result of a **streaming** Monte Carlo run written straight to JSONL: the across-trial aggregates
 *  (bounded — `O(ticks × metrics)`) plus how many records were written. Carries no per-trial data, so its
 *  memory does not grow with the trial count. */
final case class MonteCarloRunReport(
  scenarioId:          String,
  trialCount:          Int,
  aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
  aggregateSummary:    Vector[AggregateSummaryValue],
  recordsWritten:      Long
)
