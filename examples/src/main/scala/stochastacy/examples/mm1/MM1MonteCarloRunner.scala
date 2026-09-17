package stochastacy.examples.mm1

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import stochastacy.core.run.MonteCarlo

/** A metric's across-trial distribution: the ensemble mean and the standard error of that mean. */
final case class Estimate(mean: Double, stdErr: Double, trials: Int):
  /** The (approximately 95 %) confidence interval around the mean. */
  def lower: Double = mean - 1.96 * stdErr
  def upper: Double = mean + 1.96 * stdErr
  def contains(value: Double): Boolean = value >= lower && value <= upper

/** The ensemble's estimate of every metric, plus the per-trial results kept for JSONL. */
final case class MM1EnsembleResult(
  config:          MM1Config,
  masterSeed:      Long,
  trials:          Vector[MM1TrialResult],
  pagesPerSession: Estimate,
  sessionDuration: Estimate,
  pageTime:        Estimate,
  meanInSystem:    Estimate,
  busyFraction:    Estimate,
  pageRate:        Estimate
)

/**
 * Runs `trialCount` independent trials from one master seed and folds them into across-trial estimates. Trials stream
 * through `MonteCarlo.stream` in seed order, so results are parallelism-independent.
 */
object MM1MonteCarloRunner:

  def run(config: MM1Config, masterSeed: Long)(using
    system: ActorSystem,
    ec:     ExecutionContext
  ): Future[MM1EnsembleResult] =
    MonteCarlo
      .stream(config.trialCount, masterSeed, config.parallelism) { seed =>
        MM1TrialRunner.run(config, trialId = 0, trialSeed = seed)
      }
      .zipWithIndex
      .map { (result, index) => result.copy(trialId = index.toInt) }
      .runWith(Sink.seq)
      .map { results =>
        val trials = results.toVector
        MM1EnsembleResult(
          config          = config,
          masterSeed      = masterSeed,
          trials          = trials,
          pagesPerSession = estimate(trials.map(_.pagesPerSession)),
          sessionDuration = estimate(trials.map(_.sessionDuration)),
          pageTime        = estimate(trials.map(_.pageTime)),
          meanInSystem    = estimate(trials.map(_.meanInSystem)),
          busyFraction    = estimate(trials.map(_.busyFraction)),
          pageRate        = estimate(trials.map(_.pageRate))
        )
      }

  /** Mean and standard error of the mean (`sd / √n`, with the sample standard deviation). */
  def estimate(values: Vector[Double]): Estimate =
    val n = values.size
    if n == 0 then Estimate(0.0, 0.0, 0)
    else
      val mean = values.sum / n
      val variance = if n < 2 then 0.0 else values.map(v => (v - mean) * (v - mean)).sum / (n - 1).toDouble
      Estimate(mean, math.sqrt(variance / n.toDouble), n)
