package stochastacy.graphs

/**
 * Common base trait for all events exchanged in a stochastacy simulation runnable graph. All events
 * have two things in common:
 *
 * * event time, which is a simulated time in arbitrary units and with an arbitrary meaning at this abstraction
 *   level. The value is opaque in physical interpretation, but it is assumed that
 *
 *     * times are comparable, and a "higher" number represents a later point in time
 *     * A collection of events can be partitioned meaningfully by a partitioning of the
 *       time-line into windowed segments
 *
 * * a "usecase" value, used to differentiate events when placed in mixed streams. This is a mechanism
 *   for representing a mixed combination of events. Simulation components process streams of mixed
 *   use-case, timed events
 */
trait TimedEvent:
  val eventTime: SimTime
  val usecase: Any

object TimedEvent:

  /**
   * `Tick` is a distinguished timed event type, used for organizing timed event streams.
   **/
  case class Tick(override val eventTime: SimTime) extends TimedEvent:
    override val usecase: Any = CoordinatedTimingUsecase

  /**
   * `EndOfTime` is a distinguished timed event object, used for organizing timed event streams.
   **/
  case object EndOfTime extends TimedEvent:
    override val eventTime: SimTime = SimTime.of(Long.MaxValue)
    override val usecase: Any = CoordinatedTimingUsecase

/** Use-case for control events within time streams: tick, "end-of-time, and "beginning-of-time" events */
object CoordinatedTimingUsecase

/**
 * Simulation time in arbitrary "sim time" units.
 **/
opaque type SimTime = Long

extension (t: SimTime)
  def nextTime: SimTime = t + 1L
  def prevTime: SimTime = t - 1L
  def gt(other: SimTime): Boolean = t > other
  def gte(other: SimTime): Boolean = t >= other

object SimTime:
  def of(ticks: Long): SimTime = ticks
