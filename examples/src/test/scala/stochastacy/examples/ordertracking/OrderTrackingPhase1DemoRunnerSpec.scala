package stochastacy.examples.ordertracking

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

class OrderTrackingPhase1DemoRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given ActorSystem = ActorSystem("order-tracking-phase1-demo-runner-test")
  given Materializer = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher
  given Formats = DefaultFormats

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  "OrderTrackingPhase1DemoRunner" should {
    "produce non-empty JSONL output in stdout mode" in {
      val options = OrderTrackingPhase1DemoOptions(
        outputPath = None,
        trialCount = 2,
        parallelism = 2,
        simulationTicks = 5L
      )

      val bundle = Await.result(
        OrderTrackingPhase1DemoRunner.run(options),
        20.seconds
      )
      val rendered = OrderTrackingPhase1DemoRunner.emit(options, bundle)

      rendered should not be empty
      val recordTypes =
        rendered.linesIterator.toVector.filter(_.nonEmpty).map { line =>
          (parse(line) \ "recordType").extract[String]
        }.toSet

      recordTypes shouldBe Set(
        "trial-time-series",
        "aggregate-time-series",
        "trial-summary",
        "aggregate-summary"
      )
    }

    "write JSONL to a file when an output path is provided" in {
      val tempFile = Files.createTempFile("order-tracking-demo-", ".jsonl")
      val options = OrderTrackingPhase1DemoOptions(
        outputPath = Some(tempFile),
        trialCount = 2,
        parallelism = 1,
        simulationTicks = 4L
      )

      val bundle = Await.result(
        OrderTrackingPhase1DemoRunner.run(options),
        20.seconds
      )
      val message = OrderTrackingPhase1DemoRunner.emit(options, bundle)
      val written = Files.readString(tempFile)

      message should include("wrote")
      written should not be empty
    }

    "remain deterministic for the same fixed inputs" in {
      val options = OrderTrackingPhase1DemoOptions(
        outputPath = None,
        trialCount = 2,
        parallelism = 2,
        simulationTicks = 6L
      )

      val first = Await.result(OrderTrackingPhase1DemoRunner.run(options), 20.seconds)
      val second = Await.result(OrderTrackingPhase1DemoRunner.run(options), 20.seconds)

      first shouldBe second
    }

    "produce a Grafana view URL containing the dashboard uid and variables" in {
      val url = OrderTrackingGrafanaView.url(
        grafanaBaseUrl = "http://localhost:3000",
        batchId = "batch-1",
        scenarioId = "order-tracking-phase1"
      )

      url should include("/d/ips-phase1-order-tracking/")
      url should include("var-batch_id=batch-1")
      url should include("var-scenarioId=order-tracking-phase1")
    }
  }
