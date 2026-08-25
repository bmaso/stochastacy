package stochastacy.aws.examples.demo

import scala.collection.mutable

/**
 * Across-trial aggregation done **incrementally** — fold one [[TrialResult]] at a time and release it, so
 * peak memory is `O(ticks × metrics)`, independent of the trial count. For each `(tick, metric)` series
 * point and each summary metric it keeps a running `(count, sum, sumOfSquares)`, from which mean and
 * population standard deviation fall out in one pass:
 *
 *   mean = sum / count,   variance = sumSq / count − mean²   (= Σ(x−mean)²/count, the legacy convention)
 *
 * This is algebraically identical to the batch [[MonteCarloAggregation]] two-pass computation (means are
 * exact; the variance identity matches to `BigDecimal` precision), so the two agree — the batch API is a
 * thin wrapper over this. The variance is clamped at zero to absorb any last-digit rounding before `sqrt`.
 *
 * Constructed with the metric extractor lists (from [[MonteCarloAggregation.timeSeriesMetrics]] /
 * `summaryMetrics`), so the column set — including write-only GSIs — is fixed up front.
 */
final class IncrementalAggregator(
  timeSeriesMetrics: Vector[(String, TrialTimeSeriesPoint => BigDecimal)],
  summaryMetrics:    Vector[(String, TrialSummary => BigDecimal)]
):
  // per (tick, metric) and per summary-metric: (count, sum, sumOfSquares)
  private val tsAcc  = mutable.Map.empty[(Long, String), Accum]
  private val sumAcc = mutable.Map.empty[String, Accum]

  private final class Accum:
    var count: Long       = 0L
    var sum:   BigDecimal = BigDecimal(0)
    var sumSq: BigDecimal = BigDecimal(0)
    def add(v: BigDecimal): Unit = { count += 1; sum += v; sumSq += v * v }

  private def bumpTs(key: (Long, String), v: BigDecimal): Unit =
    tsAcc.getOrElseUpdate(key, new Accum).add(v)
  private def bumpSum(key: String, v: BigDecimal): Unit =
    sumAcc.getOrElseUpdate(key, new Accum).add(v)

  /** Fold one trial into the running accumulators, then it may be discarded. */
  def add(trial: TrialResult): Unit =
    trial.timeSeries.foreach { point =>
      timeSeriesMetrics.foreach { (name, extract) => bumpTs((point.tick, name), extract(point)) }
    }
    summaryMetrics.foreach { (name, extract) => bumpSum(name, extract(trial.summary)) }

  private def meanAndStdDev(a: Accum): (BigDecimal, BigDecimal) =
    if a.count == 0 then (BigDecimal(0), BigDecimal(0))
    else
      val mean     = a.sum / BigDecimal(a.count)
      val variance = if a.count < 2 then BigDecimal(0) else (a.sumSq / BigDecimal(a.count) - mean * mean).max(BigDecimal(0))
      (mean, BigDecimal.decimal(math.sqrt(variance.toDouble)))

  /** The across-trial per-tick aggregates — ticks ascending, metrics in list order, Mean then StdDev. */
  def timeSeries: Vector[AggregateTimeSeriesPoint] =
    val ticks = tsAcc.keys.map(_._1).toVector.distinct.sorted
    ticks.flatMap { tick =>
      timeSeriesMetrics.flatMap { (name, _) =>
        val (mean, sd) = meanAndStdDev(tsAcc.getOrElse((tick, name), new Accum))
        Vector(
          AggregateTimeSeriesPoint(tick, name, AggregateStatistic.Mean,   mean),
          AggregateTimeSeriesPoint(tick, name, AggregateStatistic.StdDev, sd)
        )
      }
    }

  /** The across-trial summary aggregates — metrics in list order, Mean then StdDev. */
  def summary: Vector[AggregateSummaryValue] =
    summaryMetrics.flatMap { (name, _) =>
      val (mean, sd) = meanAndStdDev(sumAcc.getOrElse(name, new Accum))
      Vector(
        AggregateSummaryValue(name, AggregateStatistic.Mean,   mean),
        AggregateSummaryValue(name, AggregateStatistic.StdDev, sd)
      )
    }
