package stochastacy.aws.examples.hotkey

import stochastacy.aws.dynamodb.{BillingMode, HeatSplitPolicy, PartitionTopology, TableSummaryState}

/**
 * A **hot-key** scenario, purpose-built to exercise phase-10's spatial-capacity model: a provisioned table
 * whose workload concentrates access on a small set of *hot* keys, so one physical partition throttles while
 * the table still has aggregate spare — and instant adaptive capacity relieves it, split-for-heat grows the
 * topology under sustained heat.
 *
 * The billing is sized so the per-partition physical max (1000 WCU) sits **below** the table total, so a hot
 * partition genuinely binds (a well-distributed workload would only ever meet the table ceiling). The
 * `adaptiveCapacity` toggle and `heatSplitPolicy` are set **directly** on the `DynamoDbTable.Config` by the
 * runner — this demo deliberately does *not* use the `SingleTableScenario` / `TableSpec` harness.
 *
 *   - each tick draws Poisson `putsPerTick` writes and `getsPerTick` reads (each `itemBytes`);
 *   - with probability `hotFraction` a request targets one of `hotKeyCount` hot keys, else a distinct cold
 *     key drawn from `coldKeySpace` — the skew that creates the hot partition;
 *   - puts overwrite in place (storage stays flat), so the derived partition count is stable and the demo
 *     isolates the throttling / adaptive / heat-split effects.
 */
final case class HotKeyConfig(
  scenarioId:       String                  = "hot-key",
  simulationTicks:  Long                    = 200L,
  trialCount:       Int                     = 50,
  parallelism:      Int                     = 4,
  billingMode:      BillingMode.Provisioned = BillingMode.Provisioned(readCapacityUnits = 3000, writeCapacityUnits = 4000),
  initialItems:     Long                    = 100000L,
  itemBytes:        Long                    = 1024L,
  putsPerTick:      Double                  = 3000.0,
  getsPerTick:      Double                  = 0.0,
  hotKeyCount:      Int                     = 1,
  hotFraction:      Double                  = 0.6,
  coldKeySpace:     Int                     = 100000,
  adaptiveCapacity: Boolean                 = true,
  heatSplitPolicy:  Option[HeatSplitPolicy] = Some(HeatSplitPolicy(windowTicks = 3, maxPartitionCount = 20)),
  partitionAccessEnabled: Boolean           = true // model per-partition key access; false = the table-level-only
                                                   // path (the reconcile baseline — no hot-partition modeling)
):
  require(scenarioId.nonEmpty,                    "scenarioId must be non-empty")
  require(simulationTicks >= 1L,                  "simulationTicks must be at least 1")
  require(trialCount >= 1,                        "trialCount must be at least 1")
  require(parallelism >= 1,                       "parallelism must be at least 1")
  require(initialItems >= 1L,                     "initialItems must be at least 1")
  require(itemBytes >= 1L,                        "itemBytes must be at least 1")
  require(putsPerTick >= 0.0,                     "putsPerTick must be non-negative")
  require(getsPerTick >= 0.0,                     "getsPerTick must be non-negative")
  require(hotKeyCount >= 1,                       "hotKeyCount must be at least 1")
  require(hotFraction >= 0.0 && hotFraction <= 1.0, "hotFraction must be in [0, 1]")
  require(coldKeySpace >= 1,                      "coldKeySpace must be at least 1")

  /** Pre-loaded with `initialItems` items each `itemBytes` — hot/cold puts overwrite these in place. */
  def initialTableState: TableSummaryState = TableSummaryState.initial(initialItems, itemBytes)

  /** The derived base partition count (before any heat-splits) for this billing + pre-loaded storage. */
  def basePartitionCount: Int =
    PartitionTopology.derive(billingMode.readCapacityUnits, billingMode.writeCapacityUnits, initialItems * itemBytes)

object HotKeyConfig:
  val default: HotKeyConfig = HotKeyConfig()
