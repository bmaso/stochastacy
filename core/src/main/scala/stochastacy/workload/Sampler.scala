package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider

trait Sampler[S, T]:
  def initialState: S
  def sample(tick: Long, rng: UniformRandomProvider, state: S): (T, S)

type StatelessSampler[T] = Sampler[Unit, T]

object Sampler:
  def stateless[T](f: (Long, UniformRandomProvider) => T): StatelessSampler[T] =
    new Sampler[Unit, T]:
      val initialState: Unit = ()
      def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (T, Unit) =
        (f(tick, rng), ())

  def deterministic[T](f: Long => T): StatelessSampler[T] =
    stateless((tick, _) => f(tick))
