package stochastacy.examples.mm1

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/** The demo end to end on a small ensemble: it runs, it reproduces exactly from its seed, its JSONL has one line per
 *  trial, and its counts conserve. Theory assertions are the next slice's spec. */
class MM1DemoSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("MM1DemoSpec")
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val config = MM1Config(simulationTicks = 120L, warmupTicks = 24L, trialCount = 12, parallelism = 4)

  private def run(c: MM1Config, seed: Long = 1L): MM1EnsembleResult =
    Await.result(MM1MonteCarloRunner.run(c, seed), 5.minutes)

  "The MM1 demo" should {

    "produce one result per trial, with measurements in a plausible range" in {
      val r = run(config)
      r.trials should have size config.trialCount.toLong
      r.trials.map(_.trialId) shouldBe (0 until config.trialCount).toVector
      all(r.trials.map(_.sessionsMeasured)) should be > 0L
      all(r.trials.map(_.pagesMeasured)) should be > 0L
      r.busyFraction.mean should (be > 0.0 and be < 1.0)
      r.pagesPerSession.mean should be > 1.0
    }

    "be reproducible from its seed" in {
      MM1Report.jsonl(run(config, seed = 7L)) shouldBe MM1Report.jsonl(run(config, seed = 7L))
      MM1Report.jsonl(run(config, seed = 8L)) should not be MM1Report.jsonl(run(config, seed = 7L))
    }

    "write one JSONL line per trial, carrying the configuration and every metric" in {
      val lines = MM1Report.jsonl(run(config)).linesIterator.toVector
      lines should have size config.trialCount.toLong
      lines.head should include ("\"scenario\":\"mm1-immediate\"")
      Seq("pages_per_session", "session_duration", "page_time", "mean_in_system", "busy_fraction", "page_rate",
          "sessions_measured", "sessions_in_flight", "pages_measured", "rho", "warmup_ticks")
        .foreach(key => lines.head should include (s"\"$key\":"))
    }

    "conserve pages: every page served is a page requested, and sessions account for their pages" in {
      val r = run(config)
      r.trials.foreach { t =>
        // Every measured session's pages were served, so the served count is at least the sessions' pages.
        val sessionPages = t.pagesPerSession * t.sessionsMeasured.toDouble
        t.pagesMeasured.toDouble should be >= sessionPages - 1e-9
        t.pageRate should be > 0.0
      }
    }

    "integrate over the windows actually delivered — every measured window except the final, residue one" in {
      // Regression guard for a found bias: the final window closes at the flush tick and its integral has no later
      // boundary to be released at. The runner must divide by what it received, or both integrals read low.
      val r = run(config)
      val expected = config.simulationTicks - config.warmupTicks - 1L
      all(r.trials.map(_.windowsMeasured)) shouldBe expected
    }

    "run the think-time arm, leaving the server's own load unchanged" in {
      val immediate = run(config)
      val thinking  = run(config.copy(scenarioId = "mm1-think-time", thinkTimeMean = Some(0.02)))
      // Think time adds an infinite-server stage: sessions take longer, but the server sees the same offered load.
      thinking.sessionDuration.mean should be > immediate.sessionDuration.mean
      thinking.busyFraction.mean shouldBe (immediate.busyFraction.mean +- 0.05)
      thinking.pageRate.mean shouldBe (immediate.pageRate.mean +- 5.0)
    }
  }
