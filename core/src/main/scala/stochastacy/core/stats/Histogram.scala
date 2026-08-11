package stochastacy.core.stats

/** A mergeable, deterministic log-bucket histogram over non-negative values.
 *
 *  Buckets are geometric: bucket `i` covers `[MinValue·Base^i, MinValue·Base^(i+1))`. Counts are a
 *  sparse `Map[bucketIndex, count]`, so a histogram costs memory proportional to the number of
 *  *distinct* buckets touched (a few, for clustered values), not the bucket space. `combine` adds
 *  bucket counts, which is **exactly associative and commutative** — the property the cross-tick
 *  and (Slice 7) cross-trial aggregation relies on. `quantile` interpolates linearly within the
 *  bucket, so estimates carry a bounded relative error (~`Base - 1`).
 *
 *  Values `≤ MinValue` (including 0) fall in bucket 0; values `≥ MaxValue` clamp to the top bucket.
 *  Observed store metrics (latency, item counts, byte counts) are all non-negative and fit the
 *  `[MinValue, MaxValue]` span comfortably. */
final case class Histogram(counts: Map[Int, Long]):

  def observe(value: Double): Histogram =
    val i = Histogram.bucketIndex(value)
    Histogram(counts.updatedWith(i)(c => Some(c.getOrElse(0L) + 1L)))

  def combine(other: Histogram): Histogram =
    val merged = other.counts.foldLeft(counts) { case (acc, (k, v)) =>
      acc.updatedWith(k)(c => Some(c.getOrElse(0L) + v))
    }
    Histogram(merged)

  def totalCount: Long = counts.valuesIterator.sum

  /** Estimate the `q`-quantile (`q ∈ [0,1]`) by linear interpolation within the containing bucket. */
  def quantile(q: Double): Double =
    val total = totalCount
    if total == 0L then 0.0
    else
      val target     = q * total.toDouble
      val sortedKeys = counts.keys.toArray.sorted
      var cum        = 0.0
      var idx        = 0
      var result     = Double.NaN
      while idx < sortedKeys.length && result.isNaN do
        val k = sortedKeys(idx)
        val c = counts(k).toDouble
        if cum + c >= target then
          val lower = Histogram.lowerBound(k)
          val upper = Histogram.lowerBound(k + 1)
          val frac  = if c == 0.0 then 0.0 else (target - cum) / c
          result = lower + frac * (upper - lower)
        else
          cum += c
          idx += 1
      if result.isNaN then Histogram.lowerBound(sortedKeys.last + 1) else result

object Histogram:
  val MinValue: Double = 1e-9
  val MaxValue: Double = 1e12
  val Base:     Double = 1.08

  private val logBase = math.log(Base)
  private val logMin  = math.log(MinValue)
  val BucketCount: Int = math.ceil((math.log(MaxValue) - logMin) / logBase).toInt

  val empty: Histogram = Histogram(Map.empty)

  def bucketIndex(value: Double): Int =
    if value <= MinValue then 0
    else
      val i = math.floor((math.log(value) - logMin) / logBase).toInt
      math.min(math.max(i, 0), BucketCount - 1)

  def lowerBound(i: Int): Double = MinValue * math.pow(Base, i.toDouble)
