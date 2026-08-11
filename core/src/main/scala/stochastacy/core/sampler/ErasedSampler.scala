package stochastacy.core.sampler

import org.apache.commons.rng.UniformRandomProvider

/** Adapts a stateful `Sampler[S, T]` into a `StatelessSampler[T]` by managing its own
 *  typed state internally as a mutable variable.
 *
 *  The `Unit` state parameter received by `sample` is ignored; the internal state
 *  advances on every call. This adapter is single-use and not thread-safe. It is
 *  designed for use in `RequestShapeDefinition.rate`, where `WorkloadRequestStream`
 *  guarantees single-call-per-tick semantics in tick order. */
final class ErasedSampler[T] private (
  private var _state: Any,
  private val _impl:  (Long, UniformRandomProvider, Any) => (T, Any)
) extends Sampler[Unit, T]:

  def initialState: Unit = ()

  def sample(tick: Long, rng: UniformRandomProvider, s: Unit): (T, Unit) =
    val (value, newState) = _impl(tick, rng, _state)
    _state = newState
    (value, ())

object ErasedSampler:
  def of[S, T](sampler: Sampler[S, T]): ErasedSampler[T] =
    new ErasedSampler[T](
      sampler.initialState,
      (tick, rng, s) =>
        val (v, ns) = sampler.sample(tick, rng, s.asInstanceOf[S])
        (v, ns.asInstanceOf[Any])
    )
