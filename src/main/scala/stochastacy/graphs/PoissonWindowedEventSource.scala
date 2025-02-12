package stochastacy.graphs

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{DiscreteDistribution, PoissonDistribution}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

object PoissonWindowedEventSource:
  case class EventCount(windowStartMs: Long, count: Int)

  def apply(totalDuration: FiniteDuration, windowSize: FiniteDuration, eventsPerSecond: Double): Source[EventCount, NotUsed] =
    require(totalDuration.toMillis % windowSize.toMillis == 0,
      "Total duration must be a multiple of window duration")

    val sampler: DiscreteDistribution.Sampler = {
      val rng: UniformRandomProvider = RandomSource.KISS.create()
      PoissonDistribution.of(eventsPerSecond * (windowSize.toMillis / 1000.0)).createSampler(rng)
    }

    val numWindows = totalDuration.toMillis / windowSize.toMillis

    Source.unfold(0) { windowIndex =>
      if windowIndex >= numWindows then None
      else
        val windowStartMs = windowIndex * windowSize.toMillis
        val eventCount = sampler.sample()

        Some(windowIndex + 1, EventCount(windowStartMs, eventCount))
    }
