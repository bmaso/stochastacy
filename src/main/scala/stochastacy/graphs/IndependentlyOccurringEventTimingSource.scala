package stochastacy.graphs

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.{ContinuousDistribution, DiscreteDistribution, ExponentialDistribution}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/**
 * Infinite source of `Double` values with an exponential distribution of `1/avgEventsPerTimeUnit`. Intended to be
 * used to generate an infinite stream of values representing the delay between events that occur at an average rate
 * of `avgEventsPerTimeUnit`. The generated values are in arbitrary "time units". Note that the return value will
 * usually be fractional if `eventsPerTimeUnit < 1`.
 */
class IndependentlyOccurringEventTimingSource:

  def apply[TE >: TimedEvent](avgEventsPerTimeUnit: Double, eventGenerator: (Long) => TE, rnd: UniformRandomProvider): Source[TE, NotUsed] = {

    val sampler: ContinuousDistribution.Sampler = ExponentialDistribution.of(1/avgEventsPerTimeUnit).createSampler(rnd)

    Source.unfold(()) { _ =>
      Some((), eventGenerator(sampler.sample().toLong)) }
  }
