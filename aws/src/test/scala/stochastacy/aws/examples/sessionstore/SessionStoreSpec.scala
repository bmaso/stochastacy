package stochastacy.aws.examples.sessionstore

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

/** The session-store TTL demo end to end: storage plateaus (creations ≈ expiries once the TTL horizon is
 *  reached) rather than rising unbounded, and the run is reproducible. Small/fast — the full-scale run
 *  lives in the `@main` demo. */
class SessionStoreSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("SessionStoreSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val P = 8
  // A small ensemble: constant create rate, TTL of P ticks, run for 3P ticks so there is a long plateau.
  private val ttlOn = SessionStoreConfig(
    scenarioId = "session-ttl-test", simulationTicks = 3L * P, trialCount = 4, parallelism = 2,
    sessionsPerTick = 60.0, validationsPerTick = 10.0, sessionBytes = 400L, ttlPeriodTicks = Some(P)
  )

  private def run(scenario: SessionStoreConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(scenario, seed), 90.seconds)

  private def finalStorage(result: MonteCarloResult): BigDecimal =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue("FinalStorageBytes", AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  private def storageAt(result: MonteCarloResult, tick: Long): BigDecimal =
    result.aggregateTimeSeries.collectFirst { case AggregateTimeSeriesPoint(`tick`, "StorageBytes", AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  "The session-store TTL demo, end to end," should {

    "climb toward the TTL horizon, then plateau" in {
      val result = run(ttlOn, seed = 1L)
      val early  = storageAt(result, P / 2L)      // mid-climb
      val atTtl  = storageAt(result, P.toLong)    // near the peak of the climb
      val late   = storageAt(result, 3L * P)      // deep in the plateau

      atTtl should be > (early * BigDecimal(1.4))                 // still climbing before the horizon
      (late - atTtl).abs should be <= (atTtl * BigDecimal(0.25))  // flat afterward — creations ≈ expiries
    }

    "cap storage far below an identical no-TTL run" in {
      val withTtl    = finalStorage(run(ttlOn, seed = 2L))
      val withoutTtl = finalStorage(run(ttlOn.copy(scenarioId = "no-ttl-test", ttlPeriodTicks = None), seed = 2L))
      // over 3P ticks, no-TTL accumulates ~3× the TTL plateau; require a clear, robust gap
      withoutTtl should be > (withTtl * BigDecimal(1.8))
    }

    "be reproducible under a fixed seed" in {
      run(ttlOn, seed = 7L) shouldBe run(ttlOn, seed = 7L)
    }
  }
