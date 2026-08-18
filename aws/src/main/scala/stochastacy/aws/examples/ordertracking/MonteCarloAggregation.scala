package stochastacy.aws.examples.ordertracking

/**
 * Across-trial aggregation for the Order-Tracking ensemble. For each `(tick, metric)` time-series point
 * and each summary metric, it reduces the ensemble to a mean and a (population) standard deviation —
 * matching the legacy demo's `{mean, stddev}` statistic set. The metric name lists here are the single
 * source of truth for both the per-trial and the aggregate JSONL records.
 */
object MonteCarloAggregation:

  /** The per-tick metrics, in export order: name → extractor. */
  val timeSeriesMetrics: Vector[(String, TrialTimeSeriesPoint => BigDecimal)] = Vector(
    ("ReadCapacityUnits",       (p: TrialTimeSeriesPoint) => p.readCapacityUnits),
    ("WriteCapacityUnits",      (p: TrialTimeSeriesPoint) => p.writeCapacityUnits),
    ("StorageBytes",            (p: TrialTimeSeriesPoint) => BigDecimal(p.storageBytes)),
    ("CumulativeEstimatedCost", (p: TrialTimeSeriesPoint) => p.cumulativeEstimatedCost)
  )

  /** The summary metrics, in export order: name → extractor. */
  val summaryMetrics: Vector[(String, TrialSummary => BigDecimal)] = Vector(
    ("TotalReadCapacityUnits",  (s: TrialSummary) => s.totalReadCapacityUnits),
    ("TotalWriteCapacityUnits", (s: TrialSummary) => s.totalWriteCapacityUnits),
    ("TotalStorageByteTicks",   (s: TrialSummary) => BigDecimal(s.totalStorageByteTicks)),
    ("FinalStorageBytes",       (s: TrialSummary) => BigDecimal(s.finalStorageBytes)),
    ("TotalEstimatedCost",      (s: TrialSummary) => s.totalEstimatedCost)
  )

  def timeSeries(trials: Vector[OrderTrackingTrialResult]): Vector[AggregateTimeSeriesPoint] =
    if trials.isEmpty then Vector.empty
    else
      val ticks         = trials.head.timeSeries.map(_.tick)
      val pointsByTick  = trials.flatMap(_.timeSeries).groupBy(_.tick)
      ticks.flatMap { tick =>
        val points = pointsByTick.getOrElse(tick, Vector.empty)
        timeSeriesMetrics.flatMap { (name, extract) =>
          val (mean, sd) = meanAndStdDev(points.map(extract))
          Vector(
            AggregateTimeSeriesPoint(tick, name, AggregateStatistic.Mean,   mean),
            AggregateTimeSeriesPoint(tick, name, AggregateStatistic.StdDev, sd)
          )
        }
      }

  def summary(trials: Vector[OrderTrackingTrialResult]): Vector[AggregateSummaryValue] =
    summaryMetrics.flatMap { (name, extract) =>
      val (mean, sd) = meanAndStdDev(trials.map(t => extract(t.summary)))
      Vector(
        AggregateSummaryValue(name, AggregateStatistic.Mean,   mean),
        AggregateSummaryValue(name, AggregateStatistic.StdDev, sd)
      )
    }

  /** Mean and population standard deviation (÷N; 0 for fewer than two values) — the legacy convention. */
  private def meanAndStdDev(values: Seq[BigDecimal]): (BigDecimal, BigDecimal) =
    val n = values.size
    if n == 0 then (BigDecimal(0), BigDecimal(0))
    else
      val mean     = values.sum / BigDecimal(n)
      val variance = if n < 2 then BigDecimal(0) else values.map(x => (x - mean).pow(2)).sum / BigDecimal(n)
      (mean, BigDecimal.decimal(math.sqrt(variance.toDouble)))
