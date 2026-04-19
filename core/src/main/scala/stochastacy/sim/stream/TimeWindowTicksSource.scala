package stochastacy.sim.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.sim.{SimTime, SimTimeWindow, TimedControlEvent}

object TimeWindowTicksSource:

  /**
   * Creates a `Source` of tuples:
   * * _1: a `TimedEvent.Tick`
   *   * first tuple has tick with clock time `timeWindow.windowStart`
   * * _2: the 0-based index of this tick within the window
   * * _3: total number of ticks in the time window
   * * _4: original `TimeWindow`
   **/
  def apply(timeWindow: SimTimeWindow): Source[(TimedControlEvent.Tick, Int, Long, SimTimeWindow), NotUsed] =
    val windowSize = timeWindow.windowSize
    Source.unfold(0) ({ idx =>
      if idx < timeWindow.windowSize.ticks then
        Some((idx + 1, (TimedControlEvent.Tick(SimTime.of(idx)), idx, windowSize.ticks, timeWindow)))
      else
        None })
