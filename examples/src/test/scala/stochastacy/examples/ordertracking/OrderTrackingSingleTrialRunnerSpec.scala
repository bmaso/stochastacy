package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.demo.{DemoMetric, TrialRunConfig}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class OrderTrackingSingleTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("order-tracking-single-trial-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  "OrderTrackingSingleTrialRunner" should {
    "return a non-empty result with the required time-series and summary metrics" in {
      val runner = OrderTrackingSingleTrialRunner()
      val config = OrderTrackingScenarioConfig.phase2Default.copy(
        simulationTicks = 6L,
        trialCount = 2,
        parallelism = 2
      )

      val result = Await.result(
        runner.runTrial(
          config = config,
          run = TrialRunConfig(
            trialId = 0,
            seed = 12345L
          )
        ),
        10.seconds
      )

      result.scenarioId shouldBe config.scenarioId
      result.timeSeries should not be empty
      result.summary should not be empty

      val timeSeriesMetrics = result.timeSeries.map(_.metric).toSet
      timeSeriesMetrics should contain allOf (
        DemoMetric.ReadCapacityUnits,
        DemoMetric.WriteCapacityUnits,
        DemoMetric.StorageBytes,
        DemoMetric.CumulativeEstimatedCost
      )
      config.globalSecondaryIndexNames.foreach { indexName =>
        timeSeriesMetrics should contain(DemoMetric.GsiReadCapacityUnits(indexName))
        timeSeriesMetrics should contain(DemoMetric.GsiWriteCapacityUnits(indexName))
      }

      val summaryMetrics = result.summary.map(_.metric).toSet
      summaryMetrics should contain allOf (
        DemoMetric.TotalReadCapacityUnits,
        DemoMetric.TotalWriteCapacityUnits,
        DemoMetric.TotalStorageByteTicks,
        DemoMetric.FinalStorageBytes,
        DemoMetric.TotalEstimatedCost
      )
      config.globalSecondaryIndexNames.foreach { indexName =>
        summaryMetrics should contain(DemoMetric.TotalGsiReadCapacityUnits(indexName))
        summaryMetrics should contain(DemoMetric.TotalGsiWriteCapacityUnits(indexName))
      }
    }

    "produce summary totals that are consistent with the per-tick series" in {
      val runner = OrderTrackingSingleTrialRunner()
      val config = OrderTrackingScenarioConfig.phase2Default.copy(
        simulationTicks = 5L
      )

      val result = Await.result(
        runner.runTrial(
          config = config,
          run = TrialRunConfig(
            trialId = 1,
            seed = 98765L
          )
        ),
        10.seconds
      )

      val readSeriesTotal =
        result.timeSeries.collect {
          case point if point.metric == DemoMetric.ReadCapacityUnits => point.value
        }.sum

      val writeSeriesTotal =
        result.timeSeries.collect {
          case point if point.metric == DemoMetric.WriteCapacityUnits => point.value
        }.sum

      val lastStorage =
        result.timeSeries.collect {
          case point if point.metric == DemoMetric.StorageBytes => point
        }.last.value

      val lastCumulativeCost =
        result.timeSeries.collect {
          case point if point.metric == DemoMetric.CumulativeEstimatedCost => point
        }.last.value

      val summaryMap = result.summary.map(s => s.metric -> s.value).toMap

      summaryMap(DemoMetric.TotalReadCapacityUnits) shouldBe readSeriesTotal
      summaryMap(DemoMetric.TotalWriteCapacityUnits) shouldBe writeSeriesTotal
      summaryMap(DemoMetric.FinalStorageBytes) shouldBe lastStorage
      summaryMap(DemoMetric.TotalEstimatedCost) shouldBe lastCumulativeCost

      config.globalSecondaryIndexNames.foreach { indexName =>
        val gsiReadSeriesTotal =
          result.timeSeries.collect {
            case point if point.metric == DemoMetric.GsiReadCapacityUnits(indexName) => point.value
          }.sum
        val gsiWriteSeriesTotal =
          result.timeSeries.collect {
            case point if point.metric == DemoMetric.GsiWriteCapacityUnits(indexName) => point.value
          }.sum

        summaryMap(DemoMetric.TotalGsiReadCapacityUnits(indexName)) shouldBe gsiReadSeriesTotal
        summaryMap(DemoMetric.TotalGsiWriteCapacityUnits(indexName)) shouldBe gsiWriteSeriesTotal
      }
    }

    "be deterministic for the same config and seed" in {
      val runner = OrderTrackingSingleTrialRunner()
      val config = OrderTrackingScenarioConfig.phase2Default.copy(
        simulationTicks = 8L
      )
      val run = TrialRunConfig(
        trialId = 4,
        seed = 24680L
      )

      val first = Await.result(runner.runTrial(config, run), 10.seconds)
      val second = Await.result(runner.runTrial(config, run), 10.seconds)

      first shouldBe second
    }
  }
