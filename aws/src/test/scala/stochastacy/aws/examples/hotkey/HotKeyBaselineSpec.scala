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
 * Baseline (characterization) gate for phase-10's hot-partition model, describing the model on its own terms.
 * The gate is **internal + transitive + documented**: it pins each property against the model's own arms rather
 * than an external run.
 *
 * **What holds tight.** On a *well-distributed* workload the per-partition machinery is **inert**: the control
 * arm (per-partition access on) matches the **table-level-only** path (access off) to within a tight tolerance,
 * because when the table ceiling binds first the admitted load never drives a partition to its physical max.
 *
 * **Model characteristics — all bounded & directional:**
 *   - **Instant adaptive.** Adaptive capacity is instant and always-on, so the hot arm with adaptive **on**
 *     throttles strictly fewer requests than the fair-share baseline (**off**).
 *   - **Derived topology.** The partition count is *derived* from capacity + storage.
 *   - **Split-for-heat as topology growth.** Sustained heat grows the effective partition count, though a lone
 *     hot key cannot be spread — the AWS single-item limit — so it grows without further relief.
 */
class HotKeyBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("HotKeyBaselineSpec")
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
    "match the table-level-only path tightly — per-partition modeling is inert" in {
      val withAccess    = await(runner.runTrial(control, 0, 1L))                                  // per-partition path active
      val tableLevelOnly = await(runner.runTrial(control.copy(partitionAccessEnabled = false), 0, 1L)) // table-level path
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
