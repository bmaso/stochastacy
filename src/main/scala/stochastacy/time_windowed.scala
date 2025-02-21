package stochastacy

sealed trait WindowSize:
  val millis: Long

object WindowSize:

  object `1s` extends WindowSize:
    override val millis = 1000L

  object `5s` extends WindowSize:
    override val millis = 5000L

  object `10s` extends WindowSize:
    override val millis = 10000L
  
  object `30s` extends WindowSize:
    override val millis = 30000L

  object `1m` extends WindowSize:
    override val millis = 60000L
  
  object `5m` extends WindowSize:
    override val millis = 300000L
  
  object `10m` extends WindowSize:
    override val millis = 600000L

  object `1h` extends WindowSize:
    override val millis = 3600000L

/**
 * A time window of a certain size that begin at a particular number of milliseconds since
 * some implicit "beginning of time".
 */
case class TimeWindow(windowSize: WindowSize, windowStart: Long)

/**
 * Meant to act as a stochastic description of a set of events that co-occur within a given time window  
 */
trait TimeWindowedEvents:
  val window: TimeWindow
