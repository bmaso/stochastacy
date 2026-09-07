package stochastacy.aws.examples.hotreplica

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * The hot-replica multi-region demo (phase-11 Slice 4): the depletion arm makes the two inbound links into
 * the rWCU-capped `ap-southeast-1` replica **diverge** (the heavy `us-east-1` stream backs up far worse than
 * the light `eu-west-1` one), while the reconcile arm stays healthy (bounded pending, link-lag latency); the
 * ensemble is deterministic under a fixed seed. The rise-then-drain of a backlog is unit-tested in
 * `RwcuThrottlingSpec` (this continuously-loaded demo never drains).
 */
class HotReplicaSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("HotReplicaSpec")
  private given Materializer         = Materializer.matFromSystem
  private given ExecutionContext     = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)
  private def await[A](f: Future[A]): A = Await.result(f, 5.minutes)

  private val Ticks = 80L
  import HotReplicaConfig.{UsEast, EuWest, ApSoutheast}

  /** Mean pending over the first vs last quarter of a link's per-tick series — a coarse growth probe. */
  private def firstLastQuarter(l: LinkSummary): (Double, Double) =
    val q = math.max(1, l.perTickPending.size / 4)
    val early = l.perTickPending.take(q).map(_._2).sum.toDouble / q
    val late  = l.perTickPending.takeRight(q).map(_._2).sum.toDouble / q
    (early, late)

  "The depletion arm" should {
    "back up both inbound links into the rWCU-capped replica, the heavy one far worse (per-link divergence)" in {
      val config = HotReplicaConfig.depletionDefault(simulationTicks = Ticks, trialCount = 1, parallelism = 1)
      val result = await(new HotReplicaTrialRunner().runTrial(config, 0, 7L))

      val heavy = result.link(UsEast, ApSoutheast)
      val light = result.link(EuWest, ApSoutheast)

      // Both inbound links lag (a real backlog forms on each).
      heavy.pendingMax should be > 20L
      light.pendingMax should be > 0L
      // The heavy stream is the deeper, slower queue — the per-link distinction.
      heavy.pendingMean should be > (light.pendingMean * 3.0)
      heavy.latencyMax  should be > light.latencyMax
      // The heavy backlog is still growing at the end (continuous overload, never drains here).
      val (early, late) = firstLastQuarter(heavy)
      late should be > (early * 2.0)
      // The rWCU ceiling is respected: ap-southeast's inbound is throttled, so it never applies all ~74/tick.
      result.region(ApSoutheast).totalRwcu should be > BigDecimal(0)
    }
  }

  "The reconcile arm" should {
    "keep replication healthy — bounded pending and near-link-lag latency (no unbounded growth)" in {
      val config = HotReplicaConfig.reconcileDefault(simulationTicks = Ticks, trialCount = 1, parallelism = 1)
      val result = await(new HotReplicaTrialRunner().runTrial(config, 0, 7L))

      val heavy = result.link(UsEast, ApSoutheast)
      // Unlimited rWCU ⇒ every write releases at its link lag: latency stays ≈ the (1-tick) lag, and pending
      // is bounded (roughly one lag-window of arrivals), not growing without bound.
      heavy.latencyMax should be <= 3L
      val (early, late) = firstLastQuarter(heavy)
      late should be < (early * 2.0 + 5.0)
    }
  }

  "The ensemble" should {
    "be deterministic under a fixed seed" in {
      val config = HotReplicaConfig.depletionDefault(simulationTicks = 30L, trialCount = 2, parallelism = 1)
      val a = await(new HotReplicaMonteCarloRunner().run(config, 5L))
      val b = await(new HotReplicaMonteCarloRunner().run(config, 5L))
      a.regions shouldBe b.regions
      a.links   shouldBe b.links
    }

    "produce per-region roll-ups and all-to-all per-link metrics (a demo smoke-run)" in {
      val config = HotReplicaConfig.reconcileDefault(simulationTicks = 20L, trialCount = 2, parallelism = 2)
      val result = await(new HotReplicaMonteCarloRunner().run(config, 1L))
      result.regions.map(_.regionName).toSet shouldBe Set(UsEast, EuWest, ApSoutheast)
      result.links.size shouldBe 6 // 3 regions, all-to-all directed
    }
  }
