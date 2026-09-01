package stochastacy.aws.examples.hotkey

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import stochastacy.core.run.MonteCarlo

/** Per-tick across-trial means for one arm. */
final case class TickMean(tick: Long, meanOffered: BigDecimal, meanThrottled: BigDecimal):
  def meanAdmitted: BigDecimal = meanOffered - meanThrottled

/** One arm's Monte Carlo outcome: across-trial means plus the per-tick time series (for JSONL). */
final case class HotKeyResult(
  arm:                     String,
  trialCount:              Int,
  meanTotalOffered:        BigDecimal,
  meanTotalThrottled:      BigDecimal,
  meanFinalPartitionCount: BigDecimal,
  perTick:                 Vector[TickMean]
):
  def meanTotalAdmitted: BigDecimal = meanTotalOffered - meanTotalThrottled
  def throttleRate:      BigDecimal = if meanTotalOffered == 0 then BigDecimal(0) else meanTotalThrottled / meanTotalOffered

/**
 * Runs a hot-key arm as a Monte Carlo ensemble and folds the trials into across-trial means (per total and
 * per tick). `runToFile` additionally streams the per-tick means to a JSONL file — one compact line per
 * tick — so the arm's throttling profile can be charted.
 */
final class HotKeyMonteCarloRunner()(using ActorSystem, Materializer, ExecutionContext):

  private val trialRunner = new HotKeyTrialRunner()

  def run(arm: String, config: HotKeyConfig, seed: Long): Future[HotKeyResult] =
    MonteCarlo.run(config.trialCount, seed, config.parallelism) { trialSeed =>
      trialRunner.runTrial(config, trialId = 0, trialSeed)
    }.map(aggregate(arm, config, _))

  def runToFile(arm: String, config: HotKeyConfig, seed: Long, output: Path): Future[HotKeyResult] =
    run(arm, config, seed).map { result =>
      val writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)
      try result.perTick.foreach(tm => writeLine(writer, arm, tm))
      finally { writer.flush(); writer.close() }
      result
    }

  private def aggregate(arm: String, config: HotKeyConfig, trials: Vector[HotKeyTrialResult]): HotKeyResult =
    val n = BigDecimal(trials.size)
    val meanOffered   = BigDecimal(trials.iterator.map(_.totalOffered).sum) / n
    val meanThrottled = BigDecimal(trials.iterator.map(_.totalThrottled).sum) / n
    val meanFinalPc   = BigDecimal(trials.iterator.map(_.finalPartitionCount.toLong).sum) / n
    val perTick = (1L to config.simulationTicks).map { tick =>
      val idx = (tick - 1L).toInt
      val off = BigDecimal(trials.iterator.map(_.perTick(idx).offered).sum) / n
      val thr = BigDecimal(trials.iterator.map(_.perTick(idx).throttled).sum) / n
      TickMean(tick, off, thr)
    }.toVector
    HotKeyResult(arm, trials.size, meanOffered, meanThrottled, meanFinalPc, perTick)

  private def writeLine(writer: BufferedWriter, arm: String, tm: TickMean): Unit =
    def num(v: BigDecimal): String = v.setScale(3, BigDecimal.RoundingMode.HALF_UP).toString
    writer.write(s"""{"arm":"$arm","tick":${tm.tick},"meanOffered":${num(tm.meanOffered)},"meanThrottled":${num(tm.meanThrottled)},"meanAdmitted":${num(tm.meanAdmitted)}}""")
    writer.newLine()
