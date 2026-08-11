package stochastacy.core.stats

/** A mergeable summary of a stream of numeric samples: additive moments (for `mean`/`stddev`) plus a
 *  mergeable [[Histogram]] (for quantiles). `combine` is associative, so per-tick and cross-trial
 *  aggregation are just folds — the key property that makes non-additive quantiles (p99) tractable
 *  across trials. */
final case class Statistic(count: Long, sum: Double, sumSq: Double, histogram: Histogram):

  def observe(value: Double): Statistic =
    Statistic(count + 1L, sum + value, sumSq + value * value, histogram.observe(value))

  def combine(other: Statistic): Statistic =
    Statistic(count + other.count, sum + other.sum, sumSq + other.sumSq, histogram.combine(other.histogram))

  def mean: Double = if count == 0L then 0.0 else sum / count.toDouble

  def variance: Double =
    if count == 0L then 0.0 else math.max(0.0, sumSq / count.toDouble - mean * mean)

  def stddev: Double = math.sqrt(variance)

  def quantile(q: Double): Double = histogram.quantile(q)
  def p50: Double = quantile(0.50)
  def p99: Double = quantile(0.99)

object Statistic:
  val empty: Statistic = Statistic(0L, 0.0, 0.0, Histogram.empty)
  def of(value: Double): Statistic = empty.observe(value)
