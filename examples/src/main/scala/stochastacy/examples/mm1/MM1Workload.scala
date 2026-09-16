package stochastacy.examples.mm1

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.ExponentialDistribution

import stochastacy.core.component.Timed
import stochastacy.sim.SimTime

/**
 * Session arrivals as a Poisson process of rate `sessionsPerTick`: exponential inter-arrival times accumulated from
 * conceptual time 1.0 (the start of tick 1) up to the horizon. Each arrival is stamped at its exact position — tick
 * `floor(τ)`, intra-tick `τ − floor(τ)` — so the stream is sorted overall *and* within each tick, which is what a
 * circuit's conceptual-time dispatch expects.
 */
object MM1Workload:

  def arrivals(config: MM1Config, rng: UniformRandomProvider): Iterator[Timed[Session]] =
    val interArrival = ExponentialDistribution.of(1.0 / config.sessionsPerTick).createSampler(rng)
    val horizon      = config.simulationTicks.toDouble + 1.0

    Iterator.unfold((1.0, 0L)) { (time, id) =>
      val next = time + interArrival.sample()
      if next >= horizon then None
      else
        val tick = math.floor(next).toLong
        Some((Timed(Session(id), SimTime.of(tick), next - tick.toDouble, config.scenarioId), (next, id + 1L)))
    }
