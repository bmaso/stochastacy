package stochastacy.graphs

/**
 * Common base trait for elements of "timed event sources". All timed events have:
 * * clock time, which is a number in arbitrary units and with an arbitrary meaning
 * * a "usecase" string, used to differentiate events when placed in mixed streams.
 */
sealed trait TimedEvent:
  val clockTime: Long
  val usecase: String

object TimedEvent:

  /**
   * `Tick` is a distinguished timed event type, used for organizing timed event streams.
   **/
  case class Tick(override val clockTime: Long) extends TimedEvent:
    override val usecase: String = "tick"
  
  /**
   * `EndOfTime` is a distinguished timed event object, used for organizing timed event streams.
   **/
  case object EndOfTime extends TimedEvent:
    override val clockTime: Long = Long.MaxValue
    override val usecase: String = "!"
  
  /**
   * All user-defined timed events extends this trait
   **/
  trait UserTimedEvent extends TimedEvent
