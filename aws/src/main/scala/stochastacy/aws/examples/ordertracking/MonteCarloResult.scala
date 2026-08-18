package stochastacy.aws.examples.ordertracking

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

/** The result of a Monte Carlo ensemble: the per-trial results plus their across-trial aggregates. */
final case class OrderTrackingMonteCarloResult(
  scenarioId:          String,
  trialCount:          Int,
  trials:              Vector[OrderTrackingTrialResult],
  aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
  aggregateSummary:    Vector[AggregateSummaryValue]
)
