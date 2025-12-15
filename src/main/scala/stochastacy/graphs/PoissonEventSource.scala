package stochastacy.graphs

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{DiscreteDistribution, PoissonDistribution}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

/**
 * An infinite stream of event counts across contiguous time windows, where the events are independent and occur
 * at a fixed average rate per time window.
 * 
 * Note that Poisson and exponential distributions are duals. The Poisson distribution is the distribution of
 * aggregate counts of independent events across fixed-size windows. The exponential distribution is the
 * distribution of the individual time between independent events.
 */
object PoissonEventSource:
  case class EventCount(count: Int)

  def apply(avgEventsPerTimeUnit: Double, rnd: UniformRandomProvider): Source[EventCount, NotUsed] =

    val sampler: DiscreteDistribution.Sampler = {
      PoissonDistribution.of(avgEventsPerTimeUnit).createSampler(rnd) }

    Source.unfold(()) { _ =>
        Some((), EventCount(sampler.sample())) }
