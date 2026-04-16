package stochastacy.graphs

import org.apache.pekko.stream.scaladsl.Source

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

/** Base type for all clock/timing events that can appear in a timed event stream. */
sealed trait TimedControlEvent extends TimedEvent:
  override val usecase = CoordinatedTimingUsecase

object TimedControlEvent:
  /** A tick advances the logical clock. */
  final case class Tick(override val eventTime: SimTime) extends TimedControlEvent

  /** End-of-stream marker for timed event streams. */
  case object EndOfTime extends TimedControlEvent:
    override val eventTime: SimTime = SimTime.of(Long.MaxValue)

/** Use-case for control events within time streams: tick, "end-of-time, and "beginning-of-time" events */
object CoordinatedTimingUsecase


/**
 * Definition of type encoding a Pekko `Source` of elements of type `X :< TimedEvent` OR of
 * type `TimedControlEvent`, but no other types of values.
 */
type TimedElement[X <: TimedEvent] = X | TimedControlEvent

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
