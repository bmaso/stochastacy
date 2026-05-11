package stochastacy.workload

/** Pure `Long => Double` and `Long => Boolean` factories for common temporal
 *  patterns. Pass these to `Sampler.deterministic` to obtain a sampler, or
 *  to `CombiningSampler.overlay` as a condition. */
object TemporalShapeFunctions:

  /** Sinusoidal cycle between `min` and `max`. Returns `max` at `peakTick`
   *  and `min` at `peakTick + periodTicks / 2`. */
  def sinusoid(min: Double, max: Double, periodTicks: Long, peakTick: Long): Long => Double =
    tick =>
      val phase = 2.0 * math.Pi * (tick - peakTick).toDouble / periodTicks.toDouble
      min + (max - min) * 0.5 * (1.0 + math.cos(phase))

  /** Multiplicative linear growth factor: 1.0 at tick 0, increasing by
   *  `ratePerTick` each tick. Compose with a base sampler via
   *  `CombiningSampler.product` to apply growth to any Double-valued sampler. */
  def linearFactor(ratePerTick: Double): Long => Double =
    tick => 1.0 + ratePerTick * tick.toDouble

  /** Multiplicative triangular-peak factor: 1.0 outside `[start, end]`,
   *  ramping to `peakMultiplier` at the midpoint. Compose with a base sampler
   *  via `CombiningSampler.product`. */
  def triangularFactor(start: Long, end: Long, peakMultiplier: Double): Long => Double =
    tick =>
      if tick < start || tick > end then 1.0
      else if start == end then peakMultiplier
      else
        val mid       = (start + end) / 2.0
        val halfWidth = (end - start) / 2.0
        1.0 + (peakMultiplier - 1.0) * (1.0 - math.abs(tick.toDouble - mid) / halfWidth)

  /** True for Monday–Friday, false for Saturday–Sunday.
   *  Assumes tick 0 = midnight Monday. Pass to `CombiningSampler.overlay`
   *  as the condition. */
  def weekdays(ticksPerDay: Long): Long => Boolean =
    tick => (tick / ticksPerDay) % 7 < 5
