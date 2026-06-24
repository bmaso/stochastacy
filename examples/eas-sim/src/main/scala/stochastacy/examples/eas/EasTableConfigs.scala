package stochastacy.examples.eas

import stochastacy.aws.dynamodb.table.*

/**
 * Factory methods for `DynamoDbTable.Config` for the two EAS tables.
 *
 * Both tables use on-demand billing. The `alerts` table is the hot-partition story:
 * all A1 Query requests hit the same GSI partition key, so a `HotPartitionModel`
 * is configured with the standard DynamoDB on-demand per-partition read limit of
 * 3000 RCU/s. This is what produces the IIR throttle cliff.
 *
 * The `user-alert-status` table has distributed partition access (per-user keys)
 * so no hot-partition model is needed — no single partition is a bottleneck.
 */
object EasTableConfigs:

  /**
   * `DynamoDbTable.Config` for the `alerts` table.
   *
   *  - GSI `by-region-index` with `IndexProjection.All` — A1 Query is fully
   *    served from the index, consistent with `ProjectionSatisfaction.FullySatisfiedByIndex`
   *    in `EasAlertsSampler.query`.
   *  - `HotPartitionModel`: 1 base-table partition (A3 PutItem volume is trivial)
   *    and 1 GSI partition capped at 3000 RCU/s — the DynamoDB on-demand single-
   *    partition read limit. All A1 requests concentrate on this partition.
   *  - On-demand billing, no adaptive/burst/dynamic-topology overrides (defaults apply).
   *  - Use-case key `"alerts"` matches `WorkloadDefinition.usecase` in `EasSimGraph`.
   */
  def alertsTableConfig(
    config:  EasAlertsConfig,
    sampler: EasAlertsSampler
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName        = "alerts",
      stateModel       = SummaryTableState(0L, 0L),
      useCaseBehaviors = Map("alerts" -> sampler),
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName  = "by-region-index",
          stateModel = SummaryTableState(0L, 0L),
          projection = DynamoDbTable.IndexProjection.All
        )
      ),
      billingMode = DynamoDbTable.BillingMode.OnDemand(),
      hotPartitionModel = Some(DynamoDbTable.HotPartitionModel(
        tablePartitionCount = 1,
        globalSecondaryIndexPartitionCounts =
          Map("by-region-index" -> 1),
        globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond =
          Map("by-region-index" -> BigDecimal(3000))
      ))
    )

  /**
   * `DynamoDbTable.Config` for the `user-alert-status` table.
   *
   *  - No indexes.
   *  - No hot-partition model: S1/S2/S3 writes spread across 500K user-keyed
   *    partitions, so no single partition approaches a throughput ceiling.
   *  - On-demand billing.
   *  - Use-case key `"user-alert-status"` matches `WorkloadDefinition.usecase`
   *    in `EasSimGraph`.
   */
  def uasTableConfig(
    config:  EasUserAlertStatusConfig,
    sampler: EasUserAlertStatusSampler
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName        = "user-alert-status",
      stateModel       = SummaryTableState(0L, 0L),
      useCaseBehaviors = Map("user-alert-status" -> sampler),
      billingMode      = DynamoDbTable.BillingMode.OnDemand()
    )
