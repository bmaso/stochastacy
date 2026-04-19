package stochastacy.demo

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class FutureMultiTrialExecutorSpec extends AnyWordSpec with should.Matchers:

  "FutureMultiTrialExecutor" should {
    "return the requested number of results in trial-id order" in {
      val executor = FutureMultiTrialExecutor[String](OrderedRunner)

      val results = Await.result(
        executor.runTrials(
          config = "scenario",
          exec = TrialExecutionConfig(
            trialCount = 3,
            parallelism = 2,
            baseSeed = 1234L
          )
        ),
        5.seconds
      )

      results.map(_.trialId) shouldBe Vector(0, 1, 2)
      results.map(_.scenarioId) shouldBe Vector("scenario", "scenario", "scenario")
    }

    "work with sequential execution when parallelism is 1" in {
      val executor = FutureMultiTrialExecutor[String](OrderedRunner)

      val results = Await.result(
        executor.runTrials(
          config = "scenario",
          exec = TrialExecutionConfig(
            trialCount = 2,
            parallelism = 1,
            baseSeed = 42L
          )
        ),
        5.seconds
      )

      results.map(_.trialId) shouldBe Vector(0, 1)
    }

    "derive distinct seeds for different trial ids" in {
      val executor = FutureMultiTrialExecutor[String](SeedEchoRunner)

      val results = Await.result(
        executor.runTrials(
          config = "scenario",
          exec = TrialExecutionConfig(
            trialCount = 3,
            parallelism = 2,
            baseSeed = 99L
          )
        ),
        5.seconds
      )

      results.flatMap(_.summary.collect {
        case TrialSummaryValue(DemoMetric.TotalEstimatedCost, value) => value
      }).distinct.size shouldBe 3
    }

    "fail the returned future when a single trial fails" in {
      val executor = FutureMultiTrialExecutor[String](FailingRunner)

      val thrown = the[RuntimeException] thrownBy {
        Await.result(
          executor.runTrials(
            config = "scenario",
            exec = TrialExecutionConfig(
              trialCount = 3,
              parallelism = 2,
              baseSeed = 123L
            )
          ),
          5.seconds
        )
      }

      thrown.getMessage shouldBe "boom"
    }
  }

  private object OrderedRunner extends SingleTrialRunner[String]:
    override def runTrial(config: String, run: TrialRunConfig): Future[TrialResult] =
      Future.successful(
        TrialResult(
          scenarioId = config,
          trialId = run.trialId,
          timeSeries = Vector.empty,
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(run.seed))
          )
        )
      )

  private object SeedEchoRunner extends SingleTrialRunner[String]:
    override def runTrial(config: String, run: TrialRunConfig): Future[TrialResult] =
      Future.successful(
        TrialResult(
          scenarioId = config,
          trialId = run.trialId,
          timeSeries = Vector.empty,
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(run.seed))
          )
        )
      )

  private object FailingRunner extends SingleTrialRunner[String]:
    override def runTrial(config: String, run: TrialRunConfig): Future[TrialResult] =
      if run.trialId == 1 then Future.failed(RuntimeException("boom"))
      else OrderedRunner.runTrial(config, run)
