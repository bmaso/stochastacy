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
                                              readConsistency: ReadConsistency,
                                              tableName: String
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
  require(tableName.nonEmpty, "tableName must be non-empty")

  private def probability(value: Double): Boolean =
    value >= 0.0 && value <= 1.0

object OrderTrackingScenarioConfig:
  val phase1Default: OrderTrackingScenarioConfig =
    OrderTrackingScenarioConfig(
      scenarioId = "order-tracking-phase1",
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
      readConsistency = ReadConsistency.StronglyConsistent,
      tableName = "orders"
    )
