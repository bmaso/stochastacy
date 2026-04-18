package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.demo.{DemoJsonlExporter, DemoReportBuilder, FutureMultiTrialExecutor, TrialExecutionConfig}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class OrderTrackingAggregationExportIntegrationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("order-tracking-aggregation-export-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher
  given Formats = DefaultFormats

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  "Order-tracking demo foundations" should {
    "produce trial and aggregate JSONL records from a small multi-trial batch" in {
      val runner = OrderTrackingSingleTrialRunner()
      val executor = FutureMultiTrialExecutor[OrderTrackingScenarioConfig](runner)

      val trials = Await.result(
        executor.runTrials(
          config = OrderTrackingScenarioConfig.phase1Default.copy(
            simulationTicks = 5L,
            trialCount = 3,
            parallelism = 2
          ),
          exec = TrialExecutionConfig(
            trialCount = 3,
            parallelism = 2,
            baseSeed = 123456L
          )
        ),
        20.seconds
      )

      val bundle = DemoReportBuilder.build(trials)
      val jsonl = DemoJsonlExporter.render(bundle.records)
      val recordTypes =
        jsonl.linesIterator.toVector.filter(_.nonEmpty).map { line =>
          (parse(line) \ "recordType").extract[String]
        }.toSet

      recordTypes shouldBe Set(
        "trial-time-series",
        "aggregate-time-series",
        "trial-summary",
        "aggregate-summary"
      )
    }
  }
