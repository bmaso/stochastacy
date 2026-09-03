package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Emission
import stochastacy.core.sampler.LogNormalSampler

/** Split-for-heat (Slice 2b): a partition sustained-hot for `windowTicks` grows the effective partition
 *  count (permanently, capped), re-hashing a hot key range across more partitions so it escapes a single
 *  partition's physical-max ceiling up toward the table total. A lone key cannot spread (the AWS single-item
 *  limit); no policy is byte-identical to Slice 2; a policy without adaptive capacity is rejected. */
class HeatSplitSpec extends AnyWordSpec with should.Matchers:

  // Provisioned(read 1000, write 4000) → derive base count 5; physical-max write ceiling 1000; table 4000.
  private val billing = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 4000)
  private val policy  = HeatSplitPolicy(windowTicks = 3, maxPartitionCount = 8) // base 5 → max bump 3

  private def putBehavior(access: (DynamoDbRequest, UniformRandomProvider) => Option[String]) = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")
    override def partitionAccessFor(request: DynamoDbRequest, rng: UniformRandomProvider): Option[String] = access(request, rng)

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)
  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  private def sampler(
    access:  (DynamoDbRequest, UniformRandomProvider) => Option[String],
    heat:    Option[HeatSplitPolicy] = Some(policy),
    adaptive: Boolean = true
  ): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior(access), latency = latency,
      billingMode = billing, adaptiveCapacity = adaptive, heatSplitPolicy = heat
    ))

  private val hotKey: (DynamoDbRequest, UniformRandomProvider) => Option[String] = (_, _) => Some("hot")

  /** Admit 1 KB (1 WCU) writes until one throttles; return (count admitted, state after). */
  private def admits(s: DynamoDbTable.DynamoDbTableSampler, start: TableState): (Int, TableState) =
    var st = start; var n = 0; var done = false
    while !done do
      val e: Emission[TableState, DynamoDbResponse, DynamoDbConsumption] = s.sample(PutItemRequest(1024L), st, rng)
      e.output.event match
        case ThrottledResponse => done = true
        case _                 => st = e.newState; n += 1
    (n, st)

  /** Saturate one tick's worth of writes to the concentrated key, then cross the tick boundary. */
  private def saturateThenTick(s: DynamoDbTable.DynamoDbTableSampler, st: TableState, tick: Long): TableState =
    val (_, saturated) = admits(s, st)
    s.onTick(tick, saturated).newState

  "Split-for-heat detection" should {

    "split after the sustain window of saturated ticks (and not before)" in {
      val s = sampler(hotKey)
      val afterTwo   = (1 to 2).foldLeft(s.initialState)((st, t) => saturateThenTick(s, st, t.toLong))
      afterTwo.heatSplit.bump shouldBe 0                   // 2 < window
      val afterThree = saturateThenTick(s, afterTwo, 3L)
      afterThree.heatSplit.bump shouldBe 1                 // window reached → one split
    }

    "keep splitting on sustained heat, capped at maxPartitionCount" in {
      val s = sampler(hotKey)
      // 15 saturated ticks: splits at ticks 3/6/9 → bump 3 (count 8 = cap); no further growth
      val end = (1 to 15).foldLeft(s.initialState)((st, t) => saturateThenTick(s, st, t.toLong))
      end.heatSplit.bump shouldBe 3
    }

    "reset the sustain counter on a cool tick (no split without sustained heat)" in {
      val s = sampler(hotKey)
      val hot2 = (1 to 2).foldLeft(s.initialState)((st, t) => saturateThenTick(s, st, t.toLong))
      val cool = s.onTick(3L, hot2).newState                // idle tick: no writes → counter resets
      cool.heatSplit.consecutiveWriteHotTicks shouldBe 0
      cool.heatSplit.bump shouldBe 0
    }

    "not split when the table (not a partition) is the bottleneck" in {
      // Table write cap 800 < the per-partition physical max 1000, so the table always binds first: even a
      // fully concentrated key never drives a partition to the 1000 trigger, so no split fires.
      val tableBound = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 800)
      val s = new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
        initialState = TableSummaryState.empty, behavior = putBehavior(hotKey), latency = latency,
        billingMode = tableBound, adaptiveCapacity = true, heatSplitPolicy = Some(policy)
      ))
      val end = (1 to 6).foldLeft(s.initialState) { (st, t) => val (_, sat) = admits(s, st); s.onTick(t.toLong, sat).newState }
      end.heatSplit.bump shouldBe 0
    }
  }

  "A heat-split (more partitions)" should {

    // Two keys colliding on one partition at count 5, separating at count 6.
    val (keyA, keyB) =
      val cs = LazyList.from(0).map(i => s"key-$i")
      (for {
        a <- cs.take(300); b <- cs.take(300)
        if a < b
        if PartitionTopology.partitionOf(a, 5) == PartitionTopology.partitionOf(b, 5)
        if PartitionTopology.partitionOf(a, 6) != PartitionTopology.partitionOf(b, 6)
      } yield (a, b)).head

    val abKey: (DynamoDbRequest, UniformRandomProvider) => Option[String] =
      (_, r) => Some(if r.nextBoolean() then keyA else keyB)

    "let a hot key range escape a single partition's physical-max cap" in {
      val s = sampler(abKey)
      // bump 0 → count 5: both keys share one partition → capped at the physical max 1000.
      admits(s, s.initialState)._1 shouldBe 1000
      // bump 1 → count 6: the keys land on separate partitions → each admits up to its own physical max,
      // so the range admits more than a single partition's 1000 (bounded by the table's 4000).
      admits(s, s.initialState.copy(heatSplit = HeatSplitState(bump = 1)))._1 should be > 1000
    }
  }

  "Split-for-heat gating" should {

    "leave the topology unchanged with no policy (byte-identical to Slice 2)" in {
      val s   = sampler(hotKey, heat = None)
      val end = (1 to 6).foldLeft(s.initialState)((st, t) => saturateThenTick(s, st, t.toLong))
      end.heatSplit.bump shouldBe 0
    }

    "reject a policy without adaptive capacity" in {
      an [IllegalArgumentException] should be thrownBy sampler(hotKey, adaptive = false)
    }
  }

  "HeatSplit.step" should {

    val prov = billing.asInstanceOf[BillingMode.Provisioned]
    def budgetWith(writePartition: Map[Int, BigDecimal]) = ThrottleBudget(writePartition = writePartition)

    "increment the write counter on a saturated partition and reset on a cool tick" in {
      val hot  = HeatSplit.step(policy, prov, 0L, budgetWith(Map(2 -> BigDecimal(1000))), HeatSplitState.initial)
      hot.consecutiveWriteHotTicks shouldBe 1
      hot.bump shouldBe 0
      val cool = HeatSplit.step(policy, prov, 0L, budgetWith(Map.empty), hot)
      cool.consecutiveWriteHotTicks shouldBe 0
    }

    "split (bump +1, counters reset) when the window is reached, and honor the cap" in {
      val hot2  = HeatSplitState(bump = 0, consecutiveWriteHotTicks = 2)
      val split = HeatSplit.step(policy, prov, 0L, budgetWith(Map(0 -> BigDecimal(1000))), hot2)
      split.bump shouldBe 1
      split.consecutiveWriteHotTicks shouldBe 0
      // at the cap (bump 3 → count 8 = max), a further window does not grow the count
      val atCap = HeatSplit.step(policy, prov, 0L, budgetWith(Map(0 -> BigDecimal(1000))), HeatSplitState(bump = 3, consecutiveWriteHotTicks = 2))
      atCap.bump shouldBe 3
    }
  }
