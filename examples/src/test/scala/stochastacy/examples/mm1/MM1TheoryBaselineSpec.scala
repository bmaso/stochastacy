package stochastacy.examples.mm1

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.commons.statistics.distribution.NormalDistribution
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * The phase's proof: the MM1 circuit — a paginated client against one FIFO server, a request/response loop that closes
 * inside a tick — reproduces the closed-form results for M/M/1 with Bernoulli feedback, at three loads and in both
 * arms (next page immediately, and after an exponential think time).
 *
 * **What is checked.** Per ensemble: six means (pages per session, number in system, time per page, session duration,
 * busy fraction, page rate); the queue-length distribution; the pages-per-session distribution (6 slots, geometric
 * tail); and Little's law (`L = λW`), which holds independently of the closed forms. Queue-length slots follow a rule
 * stated up front and applied to every load ([[MM1Theory.adequateQueueLevels]]): individual levels only while
 * `P(N = n) ≥ 1 %`, then a tail — a rarely-visited level is a zero-inflated per-trial variable, where a z-score is least
 * trustworthy. That gives 7 slots at ρ = 0.5 and 12 at ρ = 0.8 and 0.9: 140 checks in all.
 *
 * **The criterion.** A check passes when `|estimate − theory| ≤ k · stderr`, where `stderr` is the across-trial
 * standard error (trials are independent, so within-trial autocorrelation is already accounted for) and `k` holds the
 * probability of *any* false failure across all checks to 5 % (Bonferroni: per-check two-sided α = 0.05 / n). `k` is
 * computed from the actual check count, so adding or removing a check can never leave it stale.
 *
 * **The negative control.** A spec that passes proves nothing unless it could have failed. The Slice 5 bug — dividing
 * the window integrals by nominal measured ticks instead of the windows received — is recomputed from the same
 * ensemble, and the criterion must reject it.
 *
 * **Integrity rule.** Every ensemble has a fixed, distinct master seed. A failure here is investigated to its root
 * cause. It is never resolved by changing a seed, loosening `k`, widening a band, or re-running until it passes.
 */
class MM1TheoryBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("MM1TheoryBaselineSpec")
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /** Sized so the negative control's known bias sits ~14 standard errors out — see the roadmap's Slice 6 notes. */
  private val Trials        = 1000
  private val MeasuredTicks = 100L
  private val FamilyAlpha   = 0.05

  private final case class Arm(label: String, config: MM1Config, masterSeed: Long)

  /** μ = 125 and p = 0.6 throughout; λ sets the load. Warm-up is ample: the transient relaxes in about
   *  1/(μ(1−√ρ)²) ticks — roughly 3 at ρ = 0.9. */
  private val arms: Vector[Arm] =
    val loads  = Vector((0.5, 25.0, 20L), (0.8, 40.0, 20L), (0.9, 45.0, 30L))
    val thinks = Vector(None -> "immediate", Some(0.02) -> "think")
    for
      ((rho, lambda, warmup), li) <- loads.zipWithIndex
      ((think, name), ti)         <- thinks.zipWithIndex
    yield
      val label  = f"rho=$rho%.1f $name"
      val config = MM1Config(scenarioId = s"baseline $label", sessionsPerTick = lambda, thinkTimeMean = think,
                             warmupTicks = warmup, simulationTicks = warmup + MeasuredTicks,
                             queueLevels = MM1Theory.adequateQueueLevels(rho),
                             trialCount = Trials, parallelism = 8)
      require(math.abs(config.rho - rho) < 1e-9, s"$label: configured rho ${config.rho} is not $rho")
      Arm(label, config, masterSeed = 20260916L + 7919L * (li * 2 + ti)) // a distinct master seed per ensemble

  private lazy val ensembles: Vector[(Arm, MM1EnsembleResult)] =
    arms.map(arm => arm -> Await.result(MM1MonteCarloRunner.run(arm.config, arm.masterSeed), 10.minutes))

  private final case class Check(label: String, estimate: Estimate, theory: Double):
    def z: Double =
      if estimate.stdErr > 0.0 then (estimate.mean - theory) / estimate.stdErr
      else if estimate.mean == theory then 0.0
      else Double.PositiveInfinity

  private def checksFor(arm: Arm, r: MM1EnsembleResult): Vector[Check] =
    val c = arm.config
    def check(name: String, e: Estimate, theory: Double) = Check(s"${arm.label}: $name", e, theory)
    def slots(of: MM1TrialResult => Vector[Double], size: Int): Vector[Estimate] =
      Vector.tabulate(size)(i => MM1MonteCarloRunner.estimate(r.trials.map(t => of(t)(i))))

    val means = Vector(
      check("pages per session", r.pagesPerSession, MM1Theory.pagesPerSession(c)),
      check("mean number in system", r.meanInSystem, MM1Theory.meanInSystem(c)),
      check("time per page", r.pageTime, MM1Theory.pageTime(c)),
      check("session duration", r.sessionDuration, MM1Theory.sessionDuration(c)),
      check("busy fraction", r.busyFraction, MM1Theory.busyFraction(c)),
      check("page rate", r.pageRate, MM1Theory.pageRate(c))
    )
    val queue = slots(_.queueLevelFractions, c.queueLevels).zipWithIndex.map { (e, i) =>
      check(if i < c.queueLevels - 1 then s"P(N = $i)" else s"P(N ≥ $i)", e, MM1Theory.inSystemSlotProbability(c, i))
    }
    val pages = slots(_.pagesDistribution, c.pageLevels).zipWithIndex.map { (e, i) =>
      check(if i < c.pageLevels - 1 then s"P(pages = ${i + 1})" else s"P(pages ≥ ${i + 1})", e, MM1Theory.pagesSlotProbability(c, i))
    }
    // Little's law from per-trial differences, which cancels the correlation between L, λ and W within a trial.
    val little = check("Little's law  L − λW",
      MM1MonteCarloRunner.estimate(r.trials.map(t => t.meanInSystem - t.pageRate * t.pageTime)), 0.0)

    means ++ queue ++ pages :+ little

  private lazy val checks: Vector[Check] = ensembles.flatMap(checksFor)

  /** The Bonferroni multiplier for the actual number of checks. */
  private lazy val k: Double =
    NormalDistribution.of(0.0, 1.0).inverseCumulativeProbability(1.0 - FamilyAlpha / (2.0 * checks.size.toDouble))

  "The MM1 circuit" should {

    "reproduce every closed form within a family-wise 5 % band, at three loads and in both arms" in {
      // Six means, the queue slots the adequacy rule keeps, the pages slots, and Little's law — per ensemble.
      checks should have size arms.map(a => 6 + a.config.queueLevels + a.config.pageLevels + 1).sum.toLong
      checks should have size 140L
      val largest = checks.sortBy(ch => -math.abs(ch.z)).take(5)
      info(f"${checks.size} checks, k = $k%.3f; largest |z|: " + largest.map(ch => f"${ch.label} ${ch.z}%+.2f").mkString("; "))

      val outside = checks.filter(ch => math.abs(ch.z) > k)
      withClue(outside.map { ch =>
        f"\n  ${ch.label}: estimate ${ch.estimate.mean}%.6f ± ${ch.estimate.stdErr}%.6f vs theory ${ch.theory}%.6f (z ${ch.z}%+.2f)"
      }.mkString) {
        outside shouldBe empty
      }
    }

    "leave no cohort session unfinished at the horizon" in {
      // The cohort stops starting a margin before the horizon precisely so that none is cut off; a non-zero count here
      // would mean the per-session metrics are again drawn from a length-biased population.
      ensembles.foreach { (arm, r) =>
        withClue(s"${arm.label}: ")(r.trials.map(_.sessionsInFlight).sum shouldBe 0L)
      }
    }

    "reject the known Slice 5 bias — the negative control" in {
      val (arm, r) = ensembles.find(_._1.label == "rho=0.8 immediate").getOrElse(fail("no rho=0.8 immediate ensemble"))
      val c        = arm.config
      val biased   = MM1MonteCarloRunner.estimate(r.trials.map(t => t.busyFraction * t.windowsMeasured / c.measuredTicks))
      val z        = (biased.mean - c.rho) / biased.stdErr
      info(f"biased busy fraction ${biased.mean}%.5f ± ${biased.stdErr}%.5f vs ρ ${c.rho}%.1f: z $z%+.2f against k = $k%.3f")
      math.abs(z) should be > k
    }
  }
