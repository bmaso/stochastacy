package stochastacy.aws.examples.demo

/**
 * Across-trial aggregation for the Order-Tracking ensemble. For each `(tick, metric)` time-series point
 * and each summary metric, it reduces the ensemble to a mean and a (population) standard deviation —
 * matching the legacy demo's `{mean, stddev}` statistic set. The metric name lists here are the single
 * source of truth for both the per-trial and the aggregate JSONL records.
 */
object MonteCarloAggregation:

  private val baseTimeSeriesMetrics: Vector[(String, TrialTimeSeriesPoint => BigDecimal)] = Vector(
    ("ReadCapacityUnits",       (p: TrialTimeSeriesPoint) => p.readCapacityUnits),
    ("WriteCapacityUnits",      (p: TrialTimeSeriesPoint) => p.writeCapacityUnits),
    ("StorageBytes",            (p: TrialTimeSeriesPoint) => BigDecimal(p.storageBytes)),
    ("CumulativeEstimatedCost", (p: TrialTimeSeriesPoint) => p.cumulativeEstimatedCost)
  )

  private val baseSummaryMetrics: Vector[(String, TrialSummary => BigDecimal)] = Vector(
    ("TotalReadCapacityUnits",  (s: TrialSummary) => s.totalReadCapacityUnits),
    ("TotalWriteCapacityUnits", (s: TrialSummary) => s.totalWriteCapacityUnits),
    ("TotalStorageByteTicks",   (s: TrialSummary) => BigDecimal(s.totalStorageByteTicks)),
    ("FinalStorageBytes",       (s: TrialSummary) => BigDecimal(s.finalStorageBytes)),
    ("TotalEstimatedCost",      (s: TrialSummary) => s.totalEstimatedCost)
  )

  /** The GSI names present in the ensemble (sorted), for the per-GSI metric breakout — the union of the
   *  read and write breakouts, so a GSI that is only *maintained* (WCU, never read) is still reported. */
  def gsiNames(trials: Vector[TrialResult]): Vector[String] =
    trials.flatMap(t => t.summary.gsiTotalReadCapacityUnits.keys ++ t.summary.gsiTotalWriteCapacityUnits.keys).distinct.sorted

  /** The per-tick metrics — base plus a per-GSI RCU/WCU pair — as the single source of truth for both the
   *  per-trial and the aggregate records (metric names match the legacy `GSI:<name>:…`). */
  def timeSeriesMetrics(gsiNames: Vector[String]): Vector[(String, TrialTimeSeriesPoint => BigDecimal)] =
    baseTimeSeriesMetrics ++ gsiNames.flatMap { n =>
      Vector(
        (s"GSI:$n:ReadCapacityUnits",  (p: TrialTimeSeriesPoint) => p.gsiReadCapacityUnits.getOrElse(n, BigDecimal(0))),
        (s"GSI:$n:WriteCapacityUnits", (p: TrialTimeSeriesPoint) => p.gsiWriteCapacityUnits.getOrElse(n, BigDecimal(0)))
      )
    }

  def summaryMetrics(gsiNames: Vector[String]): Vector[(String, TrialSummary => BigDecimal)] =
    baseSummaryMetrics ++ gsiNames.flatMap { n =>
      Vector(
        (s"GSI:$n:TotalReadCapacityUnits",  (s: TrialSummary) => s.gsiTotalReadCapacityUnits.getOrElse(n, BigDecimal(0))),
        (s"GSI:$n:TotalWriteCapacityUnits", (s: TrialSummary) => s.gsiTotalWriteCapacityUnits.getOrElse(n, BigDecimal(0)))
      )
    }

  def timeSeries(trials: Vector[TrialResult]): Vector[AggregateTimeSeriesPoint] =
    if trials.isEmpty then Vector.empty
    else
      val metrics      = timeSeriesMetrics(gsiNames(trials))
      val ticks        = trials.head.timeSeries.map(_.tick)
      val pointsByTick = trials.flatMap(_.timeSeries).groupBy(_.tick)
      ticks.flatMap { tick =>
        val points = pointsByTick.getOrElse(tick, Vector.empty)
        metrics.flatMap { (name, extract) =>
          val (mean, sd) = meanAndStdDev(points.map(extract))
          Vector(
            AggregateTimeSeriesPoint(tick, name, AggregateStatistic.Mean,   mean),
            AggregateTimeSeriesPoint(tick, name, AggregateStatistic.StdDev, sd)
          )
        }
      }

  def summary(trials: Vector[TrialResult]): Vector[AggregateSummaryValue] =
    summaryMetrics(gsiNames(trials)).flatMap { (name, extract) =>
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
