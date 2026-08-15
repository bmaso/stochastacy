package stochastacy.examples.store.v2

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.examples.store.{ApiWorkloadConfig, StoreConfig, StoreReport}

class StoreV2ReportSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreV2ReportSpec")
  override def afterAll(): Unit = system.terminate()

  private val edge = EdgeConfig(rateLimiter = RateLimiter.FlatThrottle(12), chaosProbability = 0.2)
  private lazy val result =
    Await.result(
      StoreV2MonteCarloRunner.run(ApiWorkloadConfig.getOnly(18.0), StoreConfig(), edge, masterSeed = 1L, simulationTicks = 40L, trialCount = 4),
      60.seconds
    )

  "StoreV2Report" should {

    "summarize the per-gate outcome rates" in {
      val s = StoreV2Report.summary(result)
      s should include ("throttled (429)")
      s should include ("chaos (503)")
      s should include ("served")
    }

    "export JSONL (reusing the original demo's exporter) whose line count matches the records" in {
      val jsonl = StoreReport.jsonl(result)
      jsonl.split("\n").length shouldBe (StoreReport.pooledLines(result).size + StoreReport.acrossTrialLines(result).size)
    }
  }
