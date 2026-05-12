package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.PoissonDistribution

/** Wraps a lambda-producing sampler and adds a stochastic burst pattern.
 *
 *  On each tick, if no burst is active, a new burst triggers with probability `probability`.
 *  While a burst is active (for `durationTicks` consecutive ticks), `burstAmount(tick)` is
 *  added to the inner lambda before the Poisson draw. The effect is additive in lambda-space:
 *  the burst adds extra expected traffic on top of the base rate.
 *
 *  State is `(ticksRemaining: Int, innerState: S)`. `ticksRemaining == 0` means no active burst. */
case class RandomBurstSampler[S](
  inner:         Sampler[S, Double],
  probability:   Double,
  durationTicks: Int,
  burstAmount:   Long => Double
) extends Sampler[(Int, S), Int]:

  def initialState: (Int, S) = (0, inner.initialState)

  def sample(tick: Long, rng: UniformRandomProvider, state: (Int, S)): (Int, (Int, S)) =
    val (ticksRemaining, innerState) = state
    val (baseLambda, newInnerState)  = inner.sample(tick, rng, innerState)
    val (active, newTicks) =
      if ticksRemaining > 0 then (true, ticksRemaining - 1)
      else if rng.nextDouble() < probability then (true, durationTicks - 1)
      else (false, 0)
    val totalLambda = baseLambda + (if active then burstAmount(tick) else 0.0)
    val count =
      if totalLambda <= 0.0 then 0
      else PoissonDistribution.of(totalLambda).createSampler(rng).sample()
    (count, (newTicks, newInnerState))

object RandomBurstSampler:
  def constant[S](
    inner:         Sampler[S, Double],
    probability:   Double,
    durationTicks: Int,
    burstAmount:   Double
  ): RandomBurstSampler[S] =
    RandomBurstSampler(inner, probability, durationTicks, _ => burstAmount)
