package stochastacy.aws.examples.ordertracking

import stochastacy.aws.dynamodb.{GlobalSecondaryIndex, LocalSecondaryIndex, ReadConsistency, TableSummaryState}

/**
 * The Order-Tracking demo scenario, re-created on the v2 core — a single on-demand DynamoDB table under
 * a mixed get/put/update/delete workload. This is the reusable config the demo's behavior (Slice 3),
 * workload driver (Slice 4), and runner (Slices 5–6) all draw on; Phase-1 has no GSIs / LSIs.
 *
 *   - `getHitProbability` — chance a get finds its item (else a miss).
 *   - `updateExistingProbability` / `deleteExistingProbability` — chance the targeted item already
 *     exists (an update on a miss is an upsert; a delete on a miss is a no-op).
 *   - `initialItemCount` / `initialAverageItemBytes` — the table's starting size, and the fallback mean
 *     item size used to sample read bytes before the table has any items of its own.
 *   - `*RatePerTick` — the mean arrivals per tick of each flow (Poisson), and `*ItemBytes` — the uniform
 *     size range of items written by the put / update flows.
 */
final case class OrderTrackingConfig(
  scenarioId:                String,
  tableName:                 String,
  simulationTicks:           Long,
  trialCount:                Int,
  parallelism:               Int,
  initialItemCount:          Long,
  initialAverageItemBytes:   Long,
  getHitProbability:         Double,
  updateExistingProbability: Double,
  deleteExistingProbability: Double,
  readConsistency:           ReadConsistency,
  putRatePerTick:            Double,
  getRatePerTick:            Double,
  updateRatePerTick:         Double,
  deleteRatePerTick:         Double,
  putItemBytes:              ByteRange,
  updateItemBytes:           ByteRange,
  globalSecondaryIndexes:    Vector[GlobalSecondaryIndex] = Vector.empty,
  localSecondaryIndexes:     Vector[LocalSecondaryIndex]  = Vector.empty,
  baseQueryRatePerTick:      Double = 0.0,
  baseScanRatePerTick:       Double = 0.0,
  gsiQueryRatePerTick:       Double = 0.0, // applied to each GSI
  gsiScanRatePerTick:        Double = 0.0, // applied to each GSI
  queryEvaluatedItemsMean:   Double = 3.0, // mean "page" a query evaluates (bounded by the target's population)
  returnedFraction:          Double = 0.7  // fraction of evaluated items returned (cosmetic — RCU is on evaluated)
):
  require(scenarioId.nonEmpty,                       "scenarioId must be non-empty")
  require(tableName.nonEmpty,                        "tableName must be non-empty")
  require(simulationTicks >= 1L,                     "simulationTicks must be at least 1")
  require(trialCount >= 1,                           "trialCount must be at least 1")
  require(parallelism >= 1,                          "parallelism must be at least 1")
  require(initialItemCount >= 0L,                    "initialItemCount must be non-negative")
  require(initialAverageItemBytes >= 1L,             "initialAverageItemBytes must be at least 1")
  require(isProbability(getHitProbability),          "getHitProbability must be between 0 and 1")
  require(isProbability(updateExistingProbability),  "updateExistingProbability must be between 0 and 1")
  require(isProbability(deleteExistingProbability),  "deleteExistingProbability must be between 0 and 1")
  require(putRatePerTick >= 0.0,                     "putRatePerTick must be non-negative")
  require(getRatePerTick >= 0.0,                     "getRatePerTick must be non-negative")
  require(updateRatePerTick >= 0.0,                  "updateRatePerTick must be non-negative")
  require(deleteRatePerTick >= 0.0,                  "deleteRatePerTick must be non-negative")
  require(baseQueryRatePerTick >= 0.0,               "baseQueryRatePerTick must be non-negative")
  require(baseScanRatePerTick >= 0.0,                "baseScanRatePerTick must be non-negative")
  require(gsiQueryRatePerTick >= 0.0,                "gsiQueryRatePerTick must be non-negative")
  require(gsiScanRatePerTick >= 0.0,                 "gsiScanRatePerTick must be non-negative")
  require(queryEvaluatedItemsMean >= 0.0,            "queryEvaluatedItemsMean must be non-negative")
  require(isProbability(returnedFraction),           "returnedFraction must be between 0 and 1")

  /** The table's starting summary state. */
  def initialTableState: TableSummaryState =
    TableSummaryState.initial(initialItemCount, initialAverageItemBytes)

  private def isProbability(value: Double): Boolean = value >= 0.0 && value <= 1.0

/** A closed range of item sizes in bytes, sampled uniformly by a write flow. */
final case class ByteRange(minBytes: Long, maxBytes: Long):
  require(minBytes >= 1L,        "ByteRange.minBytes must be at least 1")
  require(maxBytes >= minBytes,  "ByteRange.maxBytes must be >= minBytes")

object OrderTrackingConfig:
  /** The Phase-1 scenario — the behavior this phase reproduces on the v2 core. */
  val phase1Default: OrderTrackingConfig =
    OrderTrackingConfig(
      scenarioId                = "order-tracking-phase1",
      tableName                 = "orders",
      simulationTicks           = 30L,
      trialCount                = 100,
      parallelism               = 4,
      initialItemCount          = 10L,
      initialAverageItemBytes   = 768L,
      getHitProbability         = 0.85,
      updateExistingProbability = 0.9,
      deleteExistingProbability = 0.75,
      readConsistency           = ReadConsistency.StronglyConsistent,
      putRatePerTick            = 0.8,
      getRatePerTick            = 2.5,
      updateRatePerTick         = 1.2,
      deleteRatePerTick         = 0.4,
      putItemBytes              = ByteRange(672L, 1120L),
      updateItemBytes           = ByteRange(768L, 1280L)
    )

  /** The Indexed Order-Tracking scenario — Phase-1's writes/gets plus Query/Scan over two GSIs and one
   *  LSI (the `order-tracking-phase2` equivalent). GSIs/LSI use the default `All` projection. */
  val indexedDefault: OrderTrackingConfig =
    phase1Default.copy(
      scenarioId             = "order-tracking-indexed",
      globalSecondaryIndexes = Vector(GlobalSecondaryIndex("customerId-status"), GlobalSecondaryIndex("sellerId-createdAt")),
      localSecondaryIndexes  = Vector(LocalSecondaryIndex("createdAt-priority")),
      baseQueryRatePerTick   = 0.8,
      baseScanRatePerTick    = 0.25,
      gsiQueryRatePerTick    = 0.75,
      gsiScanRatePerTick     = 0.30
    )
