package stochastacy

import scala.concurrent.duration.*
import scala.util.CommandLineParser.FromString

sealed trait WindowSize:
  val ticks: Long
  val strRep: String
  override def toString: String = strRep

object WindowSize:

  object `1s` extends WindowSize:
    override val ticks = 1L
    override val strRep = "1s"

  object `5s` extends WindowSize:
    override val ticks = 5L
    override val strRep = "5s"

  object `10s` extends WindowSize:
    override val ticks = 10L
    override val strRep = "10s"

  object `30s` extends WindowSize:
    override val ticks = 30L
    override val strRep = "30s"

  object `1m` extends WindowSize:
    override val ticks = 60L
    override val strRep = "1m"

  object `5m` extends WindowSize:
    override val ticks = 300L
    override val strRep = "5m"

  object `10m` extends WindowSize:
    override val ticks = 600L
    override val strRep = "10m"

  object `1h` extends WindowSize:
    override val ticks = 3600L
    override val strRep = "1h"

  given FromString[WindowSize] with
    def fromString(str: String): WindowSize =
      str match
        case `1s`.strRep => `1s`
        case `5s`.strRep => `5s`
        case `10s`.strRep => `10s`
        case `30s`.strRep => `30s`
        case `1m`.strRep => `1m`
        case `5m`.strRep => `5m`
        case `10m`.strRep => `10m`
        case `1h`.strRep => `1h`

/**
 * A time window of a certain size that begin at a particular number of ticks since
 * some implicit "beginning of time".
 */
case class SimTimeWindow(val windowSize: WindowSize, val windowStartTick: Long)
