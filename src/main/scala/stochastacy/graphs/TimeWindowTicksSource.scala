package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.TimeWindow

object TimeWindowTicksSource:

  /**
   * Creates a `Source` of tuples:
   * * _1: a `TimedEvent.Tick`
   *   * first tuple has tick with clock time `timeWindow.windowStart`, incrementing by `clockIncrementMs` in each element
   * * _2: total number of ticks in the time window
   *   * computed by time window millis / `clockIncrementMs`
   * * _3: original `TimeWindow`
   **/
  def apply(timeWindow: TimeWindow, clockIncrementMs: Long = 1000L): Source[(TimedEvent.Tick, Long, TimeWindow), NotUsed] =
    val windowSizeMs = timeWindow.windowSize.millis.toMillis
    val totalTicks = windowSizeMs / clockIncrementMs
    Source.unfold(timeWindow.windowStart)(t =>
      if t < windowSizeMs then Some((t + clockIncrementMs, (TimedEvent.Tick(t + timeWindow.windowStart), totalTicks, timeWindow)))
      else None)
