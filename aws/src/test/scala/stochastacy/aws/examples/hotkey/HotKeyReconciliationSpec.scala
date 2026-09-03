package stochastacy.aws.examples.hotkey

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.HeatSplitPolicy

/**
 * Hybrid reconciliation gate for phase-10's hot-partition model. The legacy hot-partition / adaptive /
 * dynamic-topology models are unreferenceable from this module and hash differently (`MurmurHash3` vs
 * `String.hashCode`), so — as agreed for this phase — the reconcile is **internal + transitive + documented**
 * rather than a live legacy run.
 *
 * **What reconciles tight.** On a *well-distributed* workload the per-partition machinery is **inert**: the
 * control arm (per-partition access on) reproduces the **table-level-only** path (access off) to within a tight
 * tolerance, because when the table ceiling binds first the admitted load never drives a partition to its
 * physical max. The access-off path *is* the phase-6/8 table-level path already reconciled against the legacy,
 * so the control arm inherits that reconcile transitively.
 *
 * **Documented divergences — deliberate v2 improvements (phase-2/6/9 posture), all bounded & directional:**
 *   - **Instant vs lagged adaptive.** v2 adaptive is instant and always-on, so the hot arm with adaptive **on**
 *     throttles strictly fewer requests than the fair-share baseline (**off**). The legacy's *lagged* adaptive
 *     would land *between* v2-off and v2-on (relief, but delayed) — v2-on relieves the most.
 *   - **Derived vs configured topology.** v2 *derives* the partition count from capacity + storage; the legacy
 *     *configures* `tablePartitionCount`.
 *   - **Split-for-heat as topology growth.** Sustained heat grows v2's effective partition count (reconciling
 *     in *direction* with the legacy `maybeGrowTopology`), though a lone hot key cannot be spread — the AWS
 *     single-item limit — so it grows without further relief.
 */
class HotKeyReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("HotKeyReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val runner = new HotKeyTrialRunner()
  private def await[A](f: Future[A]): A = Await.result(f, 2.minutes)

  // Well-distributed and table-saturating: puts 6000/tick exceed the 4000 WCU table cap, so the table ceiling
  // binds first — admitted load spreads to ~800/partition (< the 1000 physical max), so the per-partition
  // check never fires and all throttling is table-level.
  private val control = HotKeyConfig(
    simulationTicks = 20L, trialCount = 1, hotFraction = 0.0, putsPerTick = 6000.0, getsPerTick = 0.0,
    initialItems = 1000L, coldKeySpace = 100000, heatSplitPolicy = None
  )

  // Concentrated: a single hot key, puts 3000/tick below the table cap → throttling is per-partition while
  // the table has aggregate spare.
  private val hot = HotKeyConfig(
    simulationTicks = 20L, trialCount = 1, hotKeyCount = 1, hotFraction = 0.6, putsPerTick = 3000.0,
    getsPerTick = 0.0, initialItems = 1000L, coldKeySpace = 1000,
    heatSplitPolicy = Some(HeatSplitPolicy(windowTicks = 3, maxPartitionCount = 20))
  )

  "The control arm (well-distributed)" should {
    "reconcile tight with the table-level-only path — per-partition modeling is inert" in {
      val withAccess    = await(runner.runTrial(control, 0, 1L))                                  // per-partition path active
      val tableLevelOnly = await(runner.runTrial(control.copy(partitionAccessEnabled = false), 0, 1L)) // phase-6/8 path
      tableLevelOnly.totalThrottled should be > 0L                                                 // the table ceiling does bind
      val delta = (BigDecimal(withAccess.totalThrottled) - BigDecimal(tableLevelOnly.totalThrottled)).abs
      val rel   = delta / BigDecimal(tableLevelOnly.totalThrottled)
      rel should be <= BigDecimal("0.02")                                                          // within ~2 %
    }
  }

  "The hot arm (concentrated key)" should {
    "throttle strictly fewer with instant adaptive on than with the fair-share baseline (direction)" in {
      val on  = await(runner.runTrial(hot, 0, 1L))
      val off = await(runner.runTrial(hot.copy(adaptiveCapacity = false, heatSplitPolicy = None), 0, 1L))
      on.totalThrottled should be < off.totalThrottled
    }

    "grow the effective partition count under sustained heat (split-for-heat direction)" in {
      await(runner.runTrial(hot, 0, 1L)).finalPartitionCount should be > hot.basePartitionCount
    }
  }
