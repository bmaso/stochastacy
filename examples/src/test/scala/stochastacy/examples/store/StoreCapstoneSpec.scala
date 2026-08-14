package stochastacy.examples.store

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.stats.Statistic

/** The Slice 8 capstone: the store simulator *visibly* exhibits its three emergent behaviors under a
 *  realistic multi-stream Monte Carlo workload, and the run exports inspectable output. */
class StoreCapstoneSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreCapstoneSpec")
  override def afterAll(): Unit = system.terminate()
  private given formats: DefaultFormats = DefaultFormats

  private val api   = ApiWorkloadConfig.capstone
  private val store = StoreConfig(initialEntities = 1_000L, createRate = 0.9, latencyPerEvaluatedItem = 5.0e-4)
  private val svc   = ServiceConfig()
  private val adm   = AdmissionConfig(capacityPerTick = 18)

  private def runCapstone(seed: Long = 1L, ticks: Long = 200L, trials: Int = 6, window: Long = 50L): StoreMonteCarloResult =
    Await.result(
      StoreMonteCarloRunner.run(api, store, svc, seed, ticks, trials, adm, parallelism = 4, requestTicks = -1L, windowTicks = window),
      180.seconds
    )

  private def combined(r: StoreMonteCarloResult, uc: String, metric: String): Option[Statistic] =
    r.pooled.keys.filter(k => k.usecase == uc && k.metric == metric).flatMap(r.pooled.get).reduceOption(_ combine _)

  private def byWindow(r: StoreMonteCarloResult, uc: String, metric: String): Seq[(Int, Statistic)] =
    r.pooled.keys.filter(k => k.usecase == uc && k.metric == metric).toSeq
      .flatMap(k => r.pooled.get(k).map(k.window -> _)).sortBy(_._1)

  // One ensemble, reused across assertions (each run is a few seconds).
  private lazy val result: StoreMonteCarloResult = runCapstone()

  "The store capstone" should {

    "span multiple time windows" in {
      result.pooled.keys.map(_.window).toSet.size should be > 1
    }

    "exhibit the cardinality-driven cost rise: late-window report latency exceeds early-window" in {
      val rise = byWindow(result, "report", "latency")
      rise.size should be >= 2
      rise.last._2.mean should be > rise.head._2.mean
    }

    "exhibit the deep-offset cost cliff: list.offset evaluates far more than list.keyset" in {
      // The cliff is fundamentally about work evaluated; latency tracks it.
      val offWork = combined(result, "list.offset", "work.items").map(_.mean).getOrElse(0.0)
      val keyWork = combined(result, "list.keyset", "work.items").map(_.mean).getOrElse(0.0)
      offWork should be > (keyWork * 3.0)

      val offP99 = combined(result, "list.offset", "latency").map(_.p99).getOrElse(0.0)
      val keyP99 = combined(result, "list.keyset", "latency").map(_.p99).getOrElse(0.0)
      offP99 should be > keyP99
    }

    "throttle under offered load beyond admission capacity" in {
      val throttled = result.pooled.keys.filter(_.metric == "throttled").flatMap(result.pooled.get).map(_.mean)
      throttled.exists(_ > 0.0) shouldBe true
    }

    "expose run-to-run variance across trials (Slice 7 (b))" in {
      // Per-trial report latency p99 varies from trial to trial.
      val key = result.pooled.keys.find(k => k.usecase == "report" && k.metric == "latency").get
      result.acrossTrials(key, _.p99).stddev should be > 0.0
    }

    "export JSONL whose line count matches the emitted records" in {
      val jsonl = StoreReport.jsonl(result)
      jsonl.split("\n").length shouldBe (StoreReport.pooledLines(result).size + StoreReport.acrossTrialLines(result).size)
    }

    "export a summary reporting all three findings" in {
      val s = StoreReport.summary(result)
      s should include ("cardinality rise")
      s should include ("deep-offset cliff")
      s should include ("throttling")
    }

    "round-trip: written JSONL lines parse back as JSON with the expected fields" in {
      val lines = StoreReport.pooledLines(result)
      lines should not be empty
      val j = parse(lines.head)
      (j \ "kind").extract[String] shouldBe "pooled"
      (j \ "count").extract[Long] should be >= 0L
      (j \ "usecase").extract[String] should not be empty
    }

    "be deterministic given a fixed master seed" in {
      runCapstone(seed = 42L, ticks = 60L, trials = 3, window = 20L) shouldBe
        runCapstone(seed = 42L, ticks = 60L, trials = 3, window = 20L)
    }
  }
