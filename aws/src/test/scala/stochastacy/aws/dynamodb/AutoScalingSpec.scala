package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.sampler.LogNormalSampler

/** Reactive auto-scaling (Slice 2): a provisioned table's base capacity tracks a target utilization, with a
 *  reaction delay and asymmetric cooldowns, clamped to `[min, max]`; no-policy tables are byte-identical. */
class AutoScalingSpec extends AnyWordSpec with should.Matchers:

  private val Base = ThrottleBudget.BaseKey

  private val basePolicy = AutoScalingPolicy(
    targetUtilization = 0.7, evaluationWindowTicks = 3,
    scaleUpReactionDelayTicks = 2, scaleDownReactionDelayTicks = 2,
    scaleUpCooldownTicks = 2, scaleDownCooldownTicks = 2,
    minReadCapacityUnits = 1, maxReadCapacityUnits = 1000,
    minWriteCapacityUnits = 10, maxWriteCapacityUnits = 1000
  )

  /** Drive `AutoScaler.step` for `ticks` ticks at a fixed per-tick consumed read/write, returning the write
   *  capacity after each tick. */
  private def driveWrite(policy: AutoScalingPolicy, initialWrite: Long, consumedWrite: BigDecimal, ticks: Int): Vector[Long] =
    var cap = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = initialWrite)
    var st  = AutoScalingState.initial
    (1 to ticks).map { t =>
      val budget   = ThrottleBudget(write = Map(Base -> consumedWrite))
      val (c, s)   = AutoScaler.step(policy, t.toLong, cap, budget, st)
      cap = c; st = s
      c.writeCapacityUnits
    }.toVector

  "AutoScaler.step" should {

    "scale up toward the target (ceil(consumed / target)) after the reaction delay" in {
      // consumed 20 against 10 provisioned → util 2.0; target-tracking → ceil(20/0.7) = 29
      val caps = driveWrite(basePolicy, initialWrite = 10, consumedWrite = BigDecimal(20), ticks = 15)
      caps.head    shouldBe 10L   // no change before the window fills
      caps.last    shouldBe 29L   // settled at the target-tracking capacity
      caps.max     shouldBe 29L   // never overshoots
    }

    "clamp a scale-up at maxWriteCapacityUnits" in {
      val caps = driveWrite(basePolicy.copy(maxWriteCapacityUnits = 25), initialWrite = 10, consumedWrite = BigDecimal(20), ticks = 15)
      caps.last shouldBe 25L
    }

    "scale down toward the target after the (longer) cooldown, clamped at min" in {
      // consumed 10 against 100 provisioned → util 0.1 < 0.35 → down; ceil(10/0.7) = 15
      driveWrite(basePolicy, initialWrite = 100, consumedWrite = BigDecimal(10), ticks = 15).last shouldBe 15L
      // clamp at min
      driveWrite(basePolicy.copy(minWriteCapacityUnits = 20), initialWrite = 100, consumedWrite = BigDecimal(10), ticks = 15).last shouldBe 20L
    }

    "hold capacity when utilization sits at the target" in {
      // consumed 7 against 10 → util 0.7 exactly: not above target, not below the down threshold
      driveWrite(basePolicy, initialWrite = 10, consumedWrite = BigDecimal(7), ticks = 15).distinct shouldBe Vector(10L)
    }

    "not schedule a second change while one is pending" in {
      // capacity stays at the initial value through the window + reaction delay, changing exactly once
      val caps = driveWrite(basePolicy, initialWrite = 10, consumedWrite = BigDecimal(20), ticks = 15)
      caps.distinct shouldBe Vector(10L, 29L) // one transition, no intermediate jumps
    }
  }

  // --- sampler-level integration ---

  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(policy: Option[AutoScalingPolicy]): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior, latency = latency,
      billingMode = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 3),
      autoScalingPolicy = policy
    ))

  private def writeCap(st: TableState): Long = st.billingMode match
    case p: BillingMode.Provisioned => p.writeCapacityUnits
    case _                          => -1L

  "A provisioned table with an auto-scaling policy, driven through onTick," should {

    "raise its base write capacity under sustained high utilization" in {
      val policy = AutoScalingPolicy(
        targetUtilization = 0.7, evaluationWindowTicks = 2,
        scaleUpReactionDelayTicks = 1, scaleDownReactionDelayTicks = 1,
        scaleUpCooldownTicks = 1, scaleDownCooldownTicks = 1,
        minReadCapacityUnits = 1, maxReadCapacityUnits = 1000,
        minWriteCapacityUnits = 1, maxWriteCapacityUnits = 1000
      )
      val s = sampler(Some(policy))
      var st = s.initialState
      (1 to 10).foreach { t =>
        (1 to 3).foreach { _ => // admit the full ceiling (3 WCU) → util 1.0
          val e = s.sample(PutItemRequest(1024L), st, rng)
          if e.output.event.isInstanceOf[PutItemResponse] then st = e.newState
        }
        st = s.onTick(t.toLong, st).newState
      }
      writeCap(st) should be > 3L // scaled up from the initial ceiling
    }

    "leave capacity unchanged with no policy (byte-identical to phase-6 provisioned)" in {
      val s = sampler(None)
      var st = s.initialState
      (1 to 10).foreach { t =>
        (1 to 3).foreach { _ =>
          val e = s.sample(PutItemRequest(1024L), st, rng)
          if e.output.event.isInstanceOf[PutItemResponse] then st = e.newState
        }
        st = s.onTick(t.toLong, st).newState
      }
      writeCap(st) shouldBe 3L
    }
  }
