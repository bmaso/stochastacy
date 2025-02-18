package stochastacy

/**
 * A time window of a certain size that begin at a particular number of milliseconds since
 * some implicit "beginning of time".
 */
case class TimeWindow(windowSize: WindowSize, windowStart: Long)
