package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Emission
import stochastacy.core.sampler.LogNormalSampler

/** Burst capacity (Slice 1): a provisioned table banks unused capacity (up to `ceiling × burstWindowTicks`)
 *  and spends it on a later spike before throttling; burst-off tables are byte-identical. */
class BurstCapacitySpec extends AnyWordSpec with should.Matchers:

  private val Base = ThrottleBudget.BaseKey

  "ThrottleBudget.rollForward" should {

    "bank a target's unused capacity, capped at ceiling × burstWindowTicks" in {
      val p = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)
      // one idle tick banks the full write ceiling (3); reads too (100)
      val once = ThrottleBudget.empty.rollForward(p, Nil, burstWindowTicks = 5)
      once.writeBank(Base) shouldBe BigDecimal(3)
      once.readBank(Base)  shouldBe BigDecimal(100)
      // banking never exceeds the cap (3 × 5 = 15): from a near-full bank it clamps
      val nearFull = ThrottleBudget(writeBank = Map(Base -> BigDecimal(14))).rollForward(p, Nil, 5)
      nearFull.writeBank(Base) shouldBe BigDecimal(15) // 14 + 3, capped at 15
    }

    "drain the bank when a tick admitted more than the ceiling" in {
      val p = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)
      // a tick that admitted 12 WCU against a ceiling of 3, holding a bank of 9
      val spent = ThrottleBudget(write = Map(Base -> BigDecimal(12)), writeBank = Map(Base -> BigDecimal(9)))
      spent.rollForward(p, Nil, 5).writeBank(Base) shouldBe BigDecimal(0) // 9 + 3 − 12 = 0
    }

    "bank each idle GSI's own ceiling" in {
      val p = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 100, gsiWriteCapacityUnits = Map("g" -> 2L))
      val rolled = ThrottleBudget.empty.rollForward(p, List("g"), burstWindowTicks = 4)
      rolled.writeBank("g") shouldBe BigDecimal(2)
    }
  }

  "ThrottleBudget per-partition tallies" should {
    "throttle a partition at its ceiling and accumulate admitted demand" in {
      val b = ThrottleBudget().addPartition(partitionId = 2, readDemand = BigDecimal(0), writeDemand = BigDecimal(600))
      b.writePartition(2) shouldBe BigDecimal(600)
      // ceiling 800: another 200 fits (600+200), 300 does not
      b.partitionOverBudget(2, BigDecimal(0), BigDecimal(200), BigDecimal(1000), BigDecimal(800)) shouldBe false
      b.partitionOverBudget(2, BigDecimal(0), BigDecimal(300), BigDecimal(1000), BigDecimal(800)) shouldBe true
      // a different partition is unaffected
      b.partitionOverBudget(3, BigDecimal(0), BigDecimal(300), BigDecimal(1000), BigDecimal(800)) shouldBe false
    }

    "clear the per-partition tallies at the tick boundary (rollForward)" in {
      val p = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 4000)
      val spent = ThrottleBudget().addPartition(1, BigDecimal(0), BigDecimal(800))
      spent.rollForward(p, Nil, burstWindowTicks = 5).writePartition shouldBe empty // reset next tick
    }
  }

  "ThrottleBudget.overBudget with a bank" should {
    "admit demand up to ceiling + bank, and preserve the bank across add" in {
      val p = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)
      val b = ThrottleBudget(write = Map(Base -> BigDecimal(2)), writeBank = Map(Base -> BigDecimal(5)))
      // admitted 2, ceiling 3, bank 5 → headroom to 8: a demand of 4 fits (2+4 ≤ 8), 7 does not
      b.overBudget(Map.empty, Map(Base -> BigDecimal(4)), p) shouldBe false
      b.overBudget(Map.empty, Map(Base -> BigDecimal(7)), p) shouldBe true
      // add keeps the bank intact
      b.add(Map.empty, Map(Base -> BigDecimal(1))).writeBank(Base) shouldBe BigDecimal(5)
    }
  }

  // --- sampler-level: burst through the running table's throttle path ---

  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(billing: BillingMode, burstWindowTicks: Int = 0): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior, latency = latency,
      billingMode = billing, burstWindowTicks = burstWindowTicks
    ))

  /** Admit 1 KB writes until one throttles; return how many were admitted. */
  private def admitsUntilThrottle(s: DynamoDbTable.DynamoDbTableSampler, start: TableState): (Int, TableState) =
    var st = start
    var n  = 0
    var done = false
    while !done do
      val e: Emission[TableState, DynamoDbResponse, DynamoDbConsumption] = s.sample(PutItemRequest(1024L), st, rng)
      e.output.event match
        case ThrottledResponse => done = true
        case _                 => st = e.newState; n += 1
    (n, st)

  private def idleTicks(s: DynamoDbTable.DynamoDbTableSampler, start: TableState, ticks: Int): TableState =
    (1 to ticks).foldLeft(start) { (st, t) => s.onTick(t.toLong, st).newState }

  "A provisioned table with burst capacity" should {

    "spend banked capacity on a spike that a bare ceiling would throttle" in {
      val s  = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3), burstWindowTicks = 5)
      val st = idleTicks(s, s.initialState, 3) // banks write 3+3+3 = 9
      // one tick now admits ceiling + bank = 3 + 9 = 12 writes, where without burst only 3 would admit
      val (admitted, _) = admitsUntilThrottle(s, st)
      admitted shouldBe 12
    }

    "cap the bank at ceiling × burstWindowTicks no matter how long it idles" in {
      val s  = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3), burstWindowTicks = 5)
      val st = idleTicks(s, s.initialState, 20) // bank would be 60 uncapped; capped at 15
      val (admitted, _) = admitsUntilThrottle(s, st)
      admitted shouldBe (3 + 15) // ceiling + cap
    }

    "drain the bank, then throttle at the bare ceiling next tick" in {
      val s   = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3), burstWindowTicks = 5)
      val st0 = idleTicks(s, s.initialState, 3)      // bank 9
      val (_, st1) = admitsUntilThrottle(s, st0)     // spends the bank (admits 12)
      val st2 = s.onTick(99L, st1).newState          // rollForward: 9 + 3 − 12 = 0
      val (admitted, _) = admitsUntilThrottle(s, st2)
      admitted shouldBe 3                             // ceiling only — bank exhausted
    }

    "be byte-identical to phase-6 throttling when burst is off (idle ticks bank nothing)" in {
      val s  = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3), burstWindowTicks = 0)
      val st = idleTicks(s, s.initialState, 5)        // no banking
      val (admitted, _) = admitsUntilThrottle(s, st)
      admitted shouldBe 3                             // exactly the ceiling, as before burst existed
    }

    "ignore burst on an on-demand table (uncapped admits regardless)" in {
      val s  = sampler(BillingMode.OnDemand, burstWindowTicks = 5)
      val st = idleTicks(s, s.initialState, 3)
      // on-demand never throttles; admit a comfortable batch without hitting ThrottledResponse
      var cur = st
      (1 to 50).foreach { _ =>
        val e = s.sample(PutItemRequest(1024L), cur, rng)
        e.output.event shouldBe a[PutItemResponse]
        cur = e.newState
      }
    }
  }
