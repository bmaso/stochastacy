package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.{TimeWindow, TimeWindowing, WindowSize}

import scala.concurrent.duration.*


object TimeWindowingSource:

  /**
   * Creates a `Source[TimeWindow]` of regularly sized, contiguous `TimeWindow` instances starting
   * at `startTimeMs`. The final element ends less than `windowSize.millis` milliseconds
   * from the instant `windowDuration` in the future from `startTimeMs`.
   *
   * Note that `startTimeMs` is an offset from an arbitrary "0" point in time.
   **/
  def apply(windowing: TimeWindowing): Source[TimeWindow, NotUsed] = {
    val windowSizeMs = windowing.windowSize.millis.toMillis
    val windowDurationMs = windowing.windowDuration.toMillis

    Source.unfold(windowing.startTimeMs)((s: Long) =>
      if (s + windowSizeMs > windowDurationMs) None
      else Some((s + windowSizeMs, TimeWindow(windowing.windowSize, s)))
    )
  }
