package stochastacy.aws.examples.ordertracking

import stochastacy.aws.dynamodb.{ReadConsistency, TableSummaryState}

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
  readConsistency:           ReadConsistency
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

  /** The table's starting summary state. */
  def initialTableState: TableSummaryState =
    TableSummaryState.initial(initialItemCount, initialAverageItemBytes)

  private def isProbability(value: Double): Boolean = value >= 0.0 && value <= 1.0

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
      readConsistency           = ReadConsistency.StronglyConsistent
    )
