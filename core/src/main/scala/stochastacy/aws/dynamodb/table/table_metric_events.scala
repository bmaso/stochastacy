package stochastacy.aws.dynamodb.table

import stochastacy.aws.MetricEvent
import stochastacy.aws.dynamodb.{DynamoDbOperationKind, DynamoDbThroughputDimension, DynamoDbThrottleReason}
import stochastacy.sim.SimTime

trait TableMetricEvent extends MetricEvent

sealed trait Stage1MetricEvent extends TableMetricEvent

enum Stage1AdmissionMode:
  case Normal
  case AdaptiveBacked
  case BurstBacked
  case AdaptiveAndBurstBacked

enum TopologyScope:
  case Table
  case GlobalSecondaryIndex(indexName: String)

enum TopologyChangeReason:
  case StorageGrowth
  case ThroughputGrowth
  case SustainedHeat

object Stage1MetricEvent:

  final case class RequestAdmitted(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    operation: DynamoDbOperationKind,
                                    target: DynamoDbTarget,
                                    dimension: DynamoDbThroughputDimension,
                                    throughputDemand: BigDecimal,
                                    admissionMode: Stage1AdmissionMode,
                                    adaptiveConsumedRequestUnits: BigDecimal,
                                    adaptiveAvailableRequestUnits: BigDecimal,
                                    burstConsumedRequestUnits: BigDecimal,
                                    burstRemainingRequestUnits: BigDecimal,
                                    topologyPartitionCount: Int,
                                    resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                    indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                                  ) extends Stage1MetricEvent

  final case class RequestThrottled(
                                     eventTime: SimTime,
                                     usecase: Any,
                                     operation: DynamoDbOperationKind,
                                     target: DynamoDbTarget,
                                     dimension: DynamoDbThroughputDimension,
                                     throughputDemand: BigDecimal,
                                     reason: DynamoDbThrottleReason,
                                     adaptiveAvailableRequestUnits: BigDecimal,
                                     burstAvailableRequestUnits: BigDecimal,
                                     topologyPartitionCount: Int,
                                     resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                     indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                                   ) extends Stage1MetricEvent

  final case class TopologyChanged(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    scope: TopologyScope,
                                    reason: TopologyChangeReason,
                                    previousPartitionCount: Int,
                                    newPartitionCount: Int
                                  ) extends Stage1MetricEvent
