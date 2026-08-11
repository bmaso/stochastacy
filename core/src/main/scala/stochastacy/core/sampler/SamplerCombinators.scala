package stochastacy.core.sampler

import org.apache.commons.rng.UniformRandomProvider

/** 1-kinded sampler. Wraps a base sampler with a tick transform applied before
 *  sampling and an output transform applied after. The output transform receives
 *  the *original* tick (before transformation), not the transformed tick. */
case class MappedSampler[S, A, B](
  base:            Sampler[S, A],
  tickTransform:   Long => Long,
  outputTransform: (Long, A) => B
) extends Sampler[S, B]:
  def initialState: S = base.initialState
  def sample(tick: Long, rng: UniformRandomProvider, state: S): (B, S) =
    val (a, newState) = base.sample(tickTransform(tick), rng, state)
    (outputTransform(tick, a), newState)

object MappedSampler:
  def periodic[S, T](base: Sampler[S, T], period: Long): MappedSampler[S, T, T] =
    MappedSampler(base, tick => tick % period, (_, v) => v)

  def shift[S, T](base: Sampler[S, T], offset: Long): MappedSampler[S, T, T] =
    MappedSampler(base, tick => tick - offset, (_, v) => v)

  def stretch[S, T](base: Sampler[S, T], factor: Long): MappedSampler[S, T, T] =
    MappedSampler(base, tick => tick / factor, (_, v) => v)

/** 2-kinded sampler. Draws from two independent base samplers on every tick and
 *  combines their outputs. The combine function receives the original tick. */
case class CombiningSampler[SA, SB, A, B, C](
  baseA:         Sampler[SA, A],
  baseB:         Sampler[SB, B],
  combineOutput: (Long, A, B) => C
) extends Sampler[(SA, SB), C]:
  def initialState: (SA, SB) = (baseA.initialState, baseB.initialState)
  def sample(tick: Long, rng: UniformRandomProvider, state: (SA, SB)): (C, (SA, SB)) =
    val (a, newSA) = baseA.sample(tick, rng, state._1)
    val (b, newSB) = baseB.sample(tick, rng, state._2)
    (combineOutput(tick, a, b), (newSA, newSB))

object CombiningSampler:
  def sum[SA, SB](
    baseA: Sampler[SA, Double],
    baseB: Sampler[SB, Double]
  ): CombiningSampler[SA, SB, Double, Double, Double] =
    CombiningSampler(baseA, baseB, (_, a, b) => a + b)

  def product[SA, SB](
    baseA: Sampler[SA, Double],
    baseB: Sampler[SB, Double]
  ): CombiningSampler[SA, SB, Double, Double, Double] =
    CombiningSampler(baseA, baseB, (_, a, b) => a * b)

  def overlay[SA, SB, T](
    baseA:     Sampler[SA, T],
    baseB:     Sampler[SB, T],
    condition: Long => Boolean
  ): CombiningSampler[SA, SB, T, T, T] =
    CombiningSampler(baseA, baseB, (tick, a, b) => if condition(tick) then a else b)
