package stochastacy.examples.store

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.ResidueSummary

class StoreTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("StoreTrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private val svc = ServiceConfig(ingressLatencyTicks = 0.05, egressLatencyTicks = 0.05)

  private def run(api: ApiWorkloadConfig, sc: StoreConfig, seed: Long, ticks: Long): StoreTrialResult =
    Await.result(StoreTrialRunner.run(api, sc, svc, seed, ticks), 10.seconds)

  private def runA(api: ApiWorkloadConfig, sc: StoreConfig, adm: AdmissionConfig, seed: Long, ticks: Long, reqTicks: Long = -1L): StoreTrialResult =
    Await.result(StoreTrialRunner.run(api, sc, svc, seed, ticks, adm, reqTicks), 10.seconds)

  /** A single-use-case workload: only `get`s, at the given per-tick mean rate. */
  private def getOnly(rate: Double): ApiWorkloadConfig = ApiWorkloadConfig.getOnly(rate)

  /** Total observation count for a metric across every use-case. */
  private def countOf(r: StoreTrialResult, metric: String): Long =
    r.stats.keys.filter(_.metric == metric).flatMap(r.stats.get).map(_.count).sum

  private def throttleRate(r: StoreTrialResult, uc: String): Double =
    r.stats.get(StoreStatKey(uc, "throttled")).map(_.mean).getOrElse(0.0)

  /** One admission decision fires per request that reached admission — its total count is the
   *  request count, the exact denominator for a 1:1 check. */
  private def decisionCount(r: StoreTrialResult): Long = countOf(r, "throttled")

  private def throttleResponses(r: StoreTrialResult): Int =
    r.responses.count { case ApiError("throttled") => true; case _ => false }

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
      val api = ApiWorkloadConfig(Vector(RequestStream("create", 5.0, CreateEntity(1_024L))))
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

  "StoreTrialRunner admission (Slice 6b)" should {

    "throttle a workload whose mean rate is under capacity, driven by per-tick bursts" in {
      // mean 18 < capacity 20, yet Poisson variance pushes some ticks over the cap.
      val r = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 20), seed = 1L, ticks = 150L)
      throttleRate(r, "get") should be > 0.0
      throttleResponses(r) should be > 0
    }

    "not throttle when capacity comfortably exceeds load" in {
      val r = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 200), seed = 1L, ticks = 100L)
      throttleRate(r, "get") shouldBe 0.0
      throttleResponses(r) shouldBe 0
    }

    "throttle more as capacity tightens against the same load" in {
      val tight = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 10), seed = 1L, ticks = 100L)
      val loose = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 18), seed = 1L, ticks = 100L)
      throttleRate(tight, "get") should be > throttleRate(loose, "get")
    }

    "preserve exact 1:1 integrity — every throttled request still yields one client response (a 429)" in {
      // A padded tail (requests over [1,40], framed over [1,60]) drains every response within horizon,
      // so the count identity is exact.
      val r = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 12), seed = 1L, ticks = 60L, reqTicks = 40L)
      r.residue shouldBe ResidueSummary(0L, 0L)          // datastore fully drained
      r.responses.size.toLong shouldBe decisionCount(r)  // one response per request, 429s included
      throttleResponses(r) should be > 0                  // and throttling really happened
    }

    "surface throttles as ApiError(\"throttled\") and nothing else new" in {
      val r = runA(getOnly(18.0), StoreConfig(), AdmissionConfig(capacityPerTick = 12), seed = 1L, ticks = 60L, reqTicks = 40L)
      val errors = r.responses.collect { case e: ApiError => e }
      errors should not be empty
      errors.foreach(_ shouldBe ApiError("throttled"))
    }

    "be deterministic under throttling" in {
      val api = getOnly(18.0)
      val adm = AdmissionConfig(capacityPerTick = 12)
      runA(api, StoreConfig(), adm, seed = 5L, ticks = 40L) shouldBe
        runA(api, StoreConfig(), adm, seed = 5L, ticks = 40L)
    }
  }
