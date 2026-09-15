package stochastacy.sim

/**
 * A point in **conceptual simulation time**: a tick plus a fractional position within it, `intraTick ∈ [0, 1)` —
 * the same `(eventTime, intraTick)` pair every [[TimedEvent]] carries on the wire, as a value. Samplers receive
 * their input's instant as `at`, so time-aware behavior (continuous token refill, arrival-based queueing) can read
 * *when* an input happened; they still schedule their outputs by delay, never by absolute time.
 */
final case class SimInstant(tick: Long, intraTick: Double):
  require(intraTick >= 0.0 && intraTick < 1.0, s"intraTick must be in [0, 1): $intraTick")

  /** Conceptual time as a single number, `tick + intraTick` — for arithmetic (e.g. elapsed time between instants). */
  def toDouble: Double = tick.toDouble + intraTick

  /** The instant `delay` fractional ticks later, by the transducer's rawOffset rule:
   *  `raw = intraTick + delay`, `tick += floor(raw)`, `intraTick = raw − floor(raw)`. */
  def plus(delay: Double): SimInstant =
    val raw = intraTick + delay
    val fl  = math.floor(raw)
    SimInstant(tick + fl.toLong, raw - fl)

object SimInstant:
  /** The conceptual time of a timed event. */
  def of(e: TimedEvent): SimInstant = SimInstant(e.eventTime.ticks, e.intraTick)

  given Ordering[SimInstant] = Ordering.by[SimInstant, (Long, Double)](i => (i.tick, i.intraTick))
