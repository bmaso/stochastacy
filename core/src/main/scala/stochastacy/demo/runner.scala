package stochastacy.demo

import java.util.concurrent.Executors

import scala.concurrent.{ExecutionContext, Future}

trait SingleTrialRunner[C]:
  def runTrial(config: C, run: TrialRunConfig): Future[TrialResult]

final case class TrialExecutionConfig(
                                       trialCount: Int,
                                       parallelism: Int,
                                       baseSeed: Long
                                     ):
  require(trialCount >= 1, "trialCount must be at least 1")
  require(parallelism >= 1, "parallelism must be at least 1")

trait MultiTrialExecutor[C]:
  def runTrials(config: C, exec: TrialExecutionConfig): Future[Vector[TrialResult]]

final class FutureMultiTrialExecutor[C](
                                         runner: SingleTrialRunner[C]
                                       ) extends MultiTrialExecutor[C]:

  override def runTrials(config: C, exec: TrialExecutionConfig): Future[Vector[TrialResult]] =
    val executor = Executors.newFixedThreadPool(exec.parallelism)
    given ExecutionContext = ExecutionContext.fromExecutorService(executor)

    val trials =
      Vector.tabulate(exec.trialCount) { trialId =>
        TrialRunConfig(
          trialId = trialId,
          seed = deriveSeed(exec.baseSeed, trialId)
        )
      }

    Future
      .sequence(trials.map(run => runner.runTrial(config, run)))
      .map(_.sortBy(_.trialId))
      .andThen { case _ => executor.shutdown() }(ExecutionContext.parasitic)

  private def deriveSeed(baseSeed: Long, trialId: Int): Long =
    baseSeed ^ (0x9E3779B97F4A7C15L * (trialId.toLong + 1L))
