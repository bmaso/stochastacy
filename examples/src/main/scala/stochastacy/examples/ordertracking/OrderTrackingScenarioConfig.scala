package stochastacy.examples.ordertracking

import stochastacy.aws.dynamodb.table.ReadConsistency

final case class OrderTrackingScenarioConfig(
                                              scenarioId: String,
                                              simulationTicks: Long,
                                              trialCount: Int,
                                              parallelism: Int,
                                              initialItemCount: Long,
                                              initialAverageItemBytes: Long,
                                              createRatePerTick: Double,
                                              fetchRatePerTick: Double,
                                              updateRatePerTick: Double,
                                              deleteRatePerTick: Double,
                                              getHitProbability: Double,
                                              updateExistingProbability: Double,
                                              deleteExistingProbability: Double,
                                              newOrderMeanBytes: Long,
                                              updatedOrderMeanBytes: Long,
                                              tableQueryRatePerTick: Double,
                                              tableScanRatePerTick: Double,
                                              gsiQueryRatePerTick: Double,
                                              gsiScanRatePerTick: Double,
                                              readConsistency: ReadConsistency,
                                              tableName: String,
                                              globalSecondaryIndexNames: Vector[String],
                                              localSecondaryIndexNames: Vector[String]
                                            ):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(simulationTicks >= 1L, "simulationTicks must be at least 1")
  require(trialCount >= 1, "trialCount must be at least 1")
  require(parallelism >= 1, "parallelism must be at least 1")
  require(initialItemCount >= 0L, "initialItemCount must be non-negative")
  require(initialAverageItemBytes >= 1L, "initialAverageItemBytes must be at least 1")
  require(createRatePerTick >= 0.0, "createRatePerTick must be non-negative")
  require(fetchRatePerTick >= 0.0, "fetchRatePerTick must be non-negative")
  require(updateRatePerTick >= 0.0, "updateRatePerTick must be non-negative")
  require(deleteRatePerTick >= 0.0, "deleteRatePerTick must be non-negative")
  require(probability(getHitProbability), "getHitProbability must be between 0 and 1")
  require(probability(updateExistingProbability), "updateExistingProbability must be between 0 and 1")
  require(probability(deleteExistingProbability), "deleteExistingProbability must be between 0 and 1")
  require(newOrderMeanBytes >= 1L, "newOrderMeanBytes must be at least 1")
  require(updatedOrderMeanBytes >= 1L, "updatedOrderMeanBytes must be at least 1")
  require(tableQueryRatePerTick >= 0.0, "tableQueryRatePerTick must be non-negative")
  require(tableScanRatePerTick >= 0.0, "tableScanRatePerTick must be non-negative")
  require(gsiQueryRatePerTick >= 0.0, "gsiQueryRatePerTick must be non-negative")
  require(gsiScanRatePerTick >= 0.0, "gsiScanRatePerTick must be non-negative")
  require(tableName.nonEmpty, "tableName must be non-empty")
  require(globalSecondaryIndexNames.distinct.size == globalSecondaryIndexNames.size, "globalSecondaryIndexNames must be distinct")
  require(localSecondaryIndexNames.distinct.size == localSecondaryIndexNames.size, "localSecondaryIndexNames must be distinct")
  require(
    (globalSecondaryIndexNames.toSet intersect localSecondaryIndexNames.toSet).isEmpty,
    "globalSecondaryIndexNames and localSecondaryIndexNames must not overlap"
  )

  private def probability(value: Double): Boolean =
    value >= 0.0 && value <= 1.0

object OrderTrackingScenarioConfig:
  val phase2Default: OrderTrackingScenarioConfig =
    OrderTrackingScenarioConfig(
      scenarioId = "order-tracking-phase2",
      simulationTicks = 30L,
      trialCount = 100,
      parallelism = 4,
      initialItemCount = 10L,
      initialAverageItemBytes = 768L,
      createRatePerTick = 0.8,
      fetchRatePerTick = 2.5,
      updateRatePerTick = 1.2,
      deleteRatePerTick = 0.4,
      getHitProbability = 0.85,
      updateExistingProbability = 0.9,
      deleteExistingProbability = 0.75,
      newOrderMeanBytes = 896L,
      updatedOrderMeanBytes = 1024L,
      tableQueryRatePerTick = 0.8,
      tableScanRatePerTick = 0.25,
      gsiQueryRatePerTick = 1.5,
      gsiScanRatePerTick = 0.6,
      readConsistency = ReadConsistency.StronglyConsistent,
      tableName = "orders",
      globalSecondaryIndexNames = Vector("customerId-status", "sellerId-createdAt"),
      localSecondaryIndexNames = Vector("createdAt-priority")
    )
