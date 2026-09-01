package stochastacy.aws.dynamodb

/**
 * Split-for-heat: DynamoDB splits a physical partition that receives *sustained* high throughput into child
 * partitions, redistributing its key range so the heat spreads across more partitions (each still capped at
 * the per-partition physical max). We model this at partition-key granularity as a **permanent bump to the
 * effective partition count**: a hot range of many keys re-hashes across the split-created partitions and so
 * escapes a single partition's physical-max ceiling, up toward the table total. A lone super-hot key cannot
 * spread (it still hashes to one partition — the AWS single-item limit). A faithful analogue of the legacy
 * `maybeGrowTopology` (`partitionCount += 1` on `consecutiveHotTicks ≥ window`).
 *
 * Opt-in (like the legacy `dynamicPartitionTopologyConfig`): the policy bundles the sustain window, the
 * per-partition "hot" trigger (default = the physical max — a partition is hot once it saturates), and a
 * hard cap on the effective count (bounding runaway on an unspittable single key). Meaningful only under
 * adaptive capacity: with adaptive off the per-partition ceiling is the fair share (`capacity / count`),
 * which growing the count only shrinks — so `DynamoDbTable.Config` requires `adaptiveCapacity` when a policy
 * is set.
 *
 * The LSI limitation AWS documents ("no partition split *within an item collection* when the table has an
 * LSI") governs *sort-key* splits below this partition-key-granularity model, so it is not gated here.
 */
final case class HeatSplitPolicy(
  windowTicks:              Int,                                        // consecutive sustained-hot ticks before a split
  maxPartitionCount:        Int,                                        // hard cap on the effective partition count
  readTriggerPerPartition:  BigDecimal = PartitionTopology.RcuPerPartition,
  writeTriggerPerPartition: BigDecimal = PartitionTopology.WcuPerPartition
):
  require(windowTicks > 0,       s"windowTicks must be positive, got $windowTicks")
  require(maxPartitionCount > 0, s"maxPartitionCount must be positive, got $maxPartitionCount")

/**
 * The evolving split-for-heat topology state, threaded in [[TableState]]. `bump` is the permanent extra
 * partition count accumulated from heat-splits; the two counters track how many consecutive ticks a
 * partition has been sustained-hot (reset on a cool tick, and on a split).
 */
final case class HeatSplitState(
  bump:                     Int = 0,
  consecutiveReadHotTicks:  Int = 0,
  consecutiveWriteHotTicks: Int = 0
)

object HeatSplitState:
  val initial: HeatSplitState = HeatSplitState()

object HeatSplit:

  /**
   * The tick-boundary transition. Reads the just-completed tick's maximum per-partition admitted demand: a
   * partition at/above the trigger increments the sustained counter, else it resets to zero. When either
   * dimension reaches the window and the current effective count is still below the cap, split — bump the
   * count by one and reset both counters (the window restarts after a split). Uses the completed tick's
   * provisioned ceilings and storage to size the base (derived) count.
   */
  def step(
    policy:      HeatSplitPolicy,
    provisioned: BillingMode.Provisioned,
    storageBytes: Long,
    budget:      ThrottleBudget,
    state:       HeatSplitState
  ): HeatSplitState =
    val maxRead  = budget.readPartition.values.maxOption.getOrElse(BigDecimal(0))
    val maxWrite = budget.writePartition.values.maxOption.getOrElse(BigDecimal(0))
    val rHot     = if maxRead  >= policy.readTriggerPerPartition  then state.consecutiveReadHotTicks  + 1 else 0
    val wHot     = if maxWrite >= policy.writeTriggerPerPartition then state.consecutiveWriteHotTicks + 1 else 0

    val baseCount = PartitionTopology.derive(provisioned.readCapacityUnits, provisioned.writeCapacityUnits, storageBytes)
    val curCount  = math.min(baseCount + state.bump, policy.maxPartitionCount)

    if (rHot >= policy.windowTicks || wHot >= policy.windowTicks) && curCount < policy.maxPartitionCount then
      HeatSplitState(bump = state.bump + 1) // split: grow the count, restart the window
    else
      state.copy(consecutiveReadHotTicks = rHot, consecutiveWriteHotTicks = wHot)
