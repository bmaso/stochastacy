package stochastacy.core.sampler

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.{
  BinomialDistribution, LogNormalDistribution, NormalDistribution,
  PoissonDistribution, UniformContinuousDistribution
}

case class PoissonSampler(lambda: Long => Double) extends Sampler[Unit, Int]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Int, Unit) =
    val l = math.max(0.0, lambda(tick))
    val n = if l == 0.0 then 0 else PoissonDistribution.of(l).createSampler(rng).sample()
    (n, ())

object PoissonSampler:
  def constant(lambda: Double): PoissonSampler = PoissonSampler(_ => lambda)

case class NormalSampler(mean: Long => Double, stddev: Long => Double) extends Sampler[Unit, Double]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Double, Unit) =
    val sd = math.max(Double.MinPositiveValue, stddev(tick))
    (NormalDistribution.of(mean(tick), sd).createSampler(rng).sample(), ())

object NormalSampler:
  def constant(mean: Double, stddev: Double): NormalSampler = NormalSampler(_ => mean, _ => stddev)

case class LogNormalSampler(mu: Long => Double, sigma: Long => Double) extends Sampler[Unit, Double]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Double, Unit) =
    val s = math.max(Double.MinPositiveValue, sigma(tick))
    (LogNormalDistribution.of(mu(tick), s).createSampler(rng).sample(), ())

object LogNormalSampler:
  def constant(mu: Double, sigma: Double): LogNormalSampler = LogNormalSampler(_ => mu, _ => sigma)

case class BinomialSampler(n: Long => Int, p: Long => Double) extends Sampler[Unit, Int]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Int, Unit) =
    val trials = math.max(0, n(tick))
    val prob   = math.min(1.0, math.max(0.0, p(tick)))
    (BinomialDistribution.of(trials, prob).createSampler(rng).sample(), ())

object BinomialSampler:
  def constant(n: Int, p: Double): BinomialSampler = BinomialSampler(_ => n, _ => p)

case class UniformSampler(min: Long => Double, max: Long => Double) extends Sampler[Unit, Double]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Double, Unit) =
    val lo = min(tick)
    val hi = max(tick)
    val v  = if lo >= hi then lo else UniformContinuousDistribution.of(lo, hi).createSampler(rng).sample()
    (v, ())

object UniformSampler:
  def constant(min: Double, max: Double): UniformSampler = UniformSampler(_ => min, _ => max)

case class BernoulliSampler(p: Long => Double) extends Sampler[Unit, Boolean]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (Boolean, Unit) =
    (rng.nextDouble() < math.min(1.0, math.max(0.0, p(tick))), ())

object BernoulliSampler:
  def constant(p: Double): BernoulliSampler = BernoulliSampler(_ => p)

case class ConstantSampler[T](value: T) extends Sampler[Unit, T]:
  val initialState: Unit = ()
  def sample(tick: Long, rng: UniformRandomProvider, state: Unit): (T, Unit) = (value, ())
