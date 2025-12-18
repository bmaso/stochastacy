package stochastacy.graphs

/**
 * Common base trait for elements of "timed event sources". All timed events have:
 *
 * * clock time, which is a number in arbitrary units and with an arbitrary meaning at this abstraction
 *   level. The value is opaque in physical interpretation, but it is assumed that
 *
 *     * a higher number represents a later point in time
 *     * A collection of events can be partitioned meaningfully by a partitioning of the
 *       time-line into windowed segments
 *
 * * a "usecase" value, used to differentiate events when placed in mixed streams. This is a mechanism
 *   for representing a mixed combination of streams of stochastic variables
 */
sealed trait TimedEvent:
  type U

  val clockTime: Long
  val usecase: U

object TimedEvent:

  /**
   * `Tick` is a distinguished timed event type, used for organizing timed event streams.
   **/
  case class Tick(override val clockTime: Long) extends TimedEvent:
    override type U = CoordinatedTimingUsecase.type 
    override val usecase: this.U = CoordinatedTimingUsecase

  object CoordinatedTimingUsecase

  /**
   * `EndOfTime` is a distinguished timed event object, used for organizing timed event streams.
   **/
  case object EndOfTime extends TimedEvent:
    override type U = CoordinatedTimingUsecase.type

    override val clockTime: Long = Long.MaxValue
    override val usecase: this.U = CoordinatedTimingUsecase

  /**
   * All user-defined timed events extends this trait
   **/
  trait UserTimedEvent extends TimedEvent
