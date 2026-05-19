package stochastacy.examples.ordertracking

import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.workload.{WorkloadDefinition, WorkloadDsl}

final case class OrderTrackingScenarioConfig(
  scenarioId:                  String,
  simulationTicks:             Long,
  trialCount:                  Int,
  parallelism:                 Int,
  initialItemCount:            Long,
  initialAverageItemBytes:     Long,
  getHitProbability:           Double,
  updateExistingProbability:   Double,
  deleteExistingProbability:   Double,
  readConsistency:             ReadConsistency,
  tableName:                   String,
  globalSecondaryIndexNames:   Vector[String],
  localSecondaryIndexNames:    Vector[String]
):
  require(scenarioId.nonEmpty,                   "scenarioId must be non-empty")
  require(simulationTicks >= 1L,                 "simulationTicks must be at least 1")
  require(trialCount >= 1,                       "trialCount must be at least 1")
  require(parallelism >= 1,                      "parallelism must be at least 1")
  require(initialItemCount >= 0L,                "initialItemCount must be non-negative")
  require(initialAverageItemBytes >= 1L,         "initialAverageItemBytes must be at least 1")
  require(probability(getHitProbability),        "getHitProbability must be between 0 and 1")
  require(probability(updateExistingProbability),"updateExistingProbability must be between 0 and 1")
  require(probability(deleteExistingProbability),"deleteExistingProbability must be between 0 and 1")
  require(tableName.nonEmpty,                    "tableName must be non-empty")
  require(
    globalSecondaryIndexNames.distinct.size == globalSecondaryIndexNames.size,
    "globalSecondaryIndexNames must be distinct"
  )
  require(
    localSecondaryIndexNames.distinct.size == localSecondaryIndexNames.size,
    "localSecondaryIndexNames must be distinct"
  )
  require(
    (globalSecondaryIndexNames.toSet intersect localSecondaryIndexNames.toSet).isEmpty,
    "globalSecondaryIndexNames and localSecondaryIndexNames must not overlap"
  )

  def toWorkloadDefinition(): WorkloadDefinition =
    val yaml = scala.io.Source.fromResource(
      "stochastacy/examples/ordertracking/order-tracking.yaml"
    ).mkString
    val template = WorkloadDsl.parse(yaml).resolve(scenarioId)
    val indices = globalSecondaryIndexNames.zipWithIndex.map { (name, i) =>
      s"gsi-${i + 1}" -> name
    }.toMap
    template.bind(tableName, scenarioId, indices)

  private def probability(value: Double): Boolean =
    value >= 0.0 && value <= 1.0

object OrderTrackingScenarioConfig:
  val phase1Default: OrderTrackingScenarioConfig =
    OrderTrackingScenarioConfig(
      scenarioId                = "order-tracking-phase1",
      simulationTicks           = 30L,
      trialCount                = 100,
      parallelism               = 4,
      initialItemCount          = 10L,
      initialAverageItemBytes   = 768L,
      getHitProbability         = 0.85,
      updateExistingProbability = 0.9,
      deleteExistingProbability = 0.75,
      readConsistency           = ReadConsistency.StronglyConsistent,
      tableName                 = "orders",
      globalSecondaryIndexNames = Vector.empty,
      localSecondaryIndexNames  = Vector.empty
    )

  val phase2Default: OrderTrackingScenarioConfig =
    OrderTrackingScenarioConfig(
      scenarioId                = "order-tracking-phase2",
      simulationTicks           = 30L,
      trialCount                = 100,
      parallelism               = 4,
      initialItemCount          = 10L,
      initialAverageItemBytes   = 768L,
      getHitProbability         = 0.85,
      updateExistingProbability = 0.9,
      deleteExistingProbability = 0.75,
      readConsistency           = ReadConsistency.StronglyConsistent,
      tableName                 = "orders",
      globalSecondaryIndexNames = Vector("customerId-status", "sellerId-createdAt"),
      localSecondaryIndexNames  = Vector("createdAt-priority")
    )
