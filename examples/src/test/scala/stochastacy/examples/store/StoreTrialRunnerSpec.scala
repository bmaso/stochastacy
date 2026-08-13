package stochastacy.examples.store

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class StoreTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreTrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private val svc = ServiceConfig(ingressLatencyTicks = 0.05, egressLatencyTicks = 0.05)

  private def run(api: ApiWorkloadConfig, sc: StoreConfig, seed: Long, ticks: Long): StoreTrialResult =
    Await.result(StoreTrialRunner.run(api, sc, svc, seed, ticks), 10.seconds)

  /** Total observation count for a metric across every use-case. */
  private def countOf(r: StoreTrialResult, metric: String): Long =
    r.stats.keys.filter(_.metric == metric).flatMap(r.stats.get).map(_.count).sum

  "StoreTrialRunner (full pipeline)" should {

    "run api-workload -> ingress -> datastore -> egress end-to-end and complete" in {
      val result = run(ApiWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 50L)
      result.durationTicks shouldBe 50L
      result.finalState.entityCount should be >= 0L
      result.finalState.totalBytes should be >= 0L
      result.residue.total should be >= 0L
      result.responses should not be empty
    }

    "grow entity count under create-only writes with no deletes" in {
      val api = ApiWorkloadConfig(getPerTick = 0.0, updatePerTick = 0.0, deletePerTick = 0.0, listPerTick = 0.0, reportPerTick = 0.0, createPerTick = 5.0)
      val sc  = StoreConfig(createRate = 1.0)
      run(api, sc, seed = 1L, ticks = 50L).finalState.entityCount should be > sc.initialEntities
    }

    "record latency statistics for all three pipeline stages" in {
      val result = run(ApiWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 50L)
      for metric <- Seq("ingress.latency", "latency", "egress.latency") do
        val s = result.stats.get(StoreStatKey("get", metric))
        s.map(_.count).getOrElse(0L) should be > 0L
      // ingress/egress latency are the configured constants
      result.stats.get(StoreStatKey("get", "ingress.latency")).map(_.mean).getOrElse(0.0) shouldBe (svc.ingressLatencyTicks +- 1e-9)
      result.stats.get(StoreStatKey("get", "egress.latency")).map(_.mean).getOrElse(0.0) shouldBe (svc.egressLatencyTicks +- 1e-9)
    }

    "preserve 1:1 integrity: one client response per request across all four stages" in {
      val result = run(ApiWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 50L)
      // every ApiRequest crosses ingress (ingress.latency), and every response crosses egress.
      countOf(result, "ingress.latency") shouldBe countOf(result, "egress.latency")
      result.responses.size.toLong shouldBe countOf(result, "egress.latency")
    }

    "show the emergent cost ordering: datastore report p99 exceeds get p99" in {
      val result   = run(ApiWorkloadConfig(), StoreConfig(), seed = 1L, ticks = 100L)
      val getP99    = result.stats.get(StoreStatKey("get", "latency")).map(_.p99)
      val reportP99 = result.stats.get(StoreStatKey("report", "latency")).map(_.p99)
      getP99 shouldBe defined
      reportP99 shouldBe defined
      reportP99.get should be > getP99.get // reports evaluate the whole set; gets are O(1)
    }

    "be deterministic given a fixed seed" in {
      run(ApiWorkloadConfig(), StoreConfig(), seed = 7L, ticks = 30L) shouldBe
        run(ApiWorkloadConfig(), StoreConfig(), seed = 7L, ticks = 30L)
    }
  }
