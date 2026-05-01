package stochastacy.aws.dynamodb.table

import stochastacy.aws.MetricEvent
import stochastacy.aws.dynamodb.{DynamoDbOperationKind, DynamoDbThroughputDimension, DynamoDbThrottleReason}
import stochastacy.sim.SimTime

trait TableMetricEvent extends MetricEvent

sealed trait AdmissionMetricEvent extends TableMetricEvent

enum AdmissionMode:
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

object AdmissionMetricEvent:

  final case class RequestAdmitted(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    operation: DynamoDbOperationKind,
                                    target: DynamoDbTarget,
                                    dimension: DynamoDbThroughputDimension,
                                    throughputDemand: BigDecimal,
                                    admissionMode: AdmissionMode,
                                    adaptiveConsumedRequestUnits: BigDecimal,
                                    adaptiveAvailableRequestUnits: BigDecimal,
                                    burstConsumedRequestUnits: BigDecimal,
                                    burstRemainingRequestUnits: BigDecimal,
                                    topologyPartitionCount: Int,
                                    resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                    indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                                  ) extends AdmissionMetricEvent

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
                                   ) extends AdmissionMetricEvent

  final case class TopologyChanged(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    scope: TopologyScope,
                                    reason: TopologyChangeReason,
                                    previousPartitionCount: Int,
                                    newPartitionCount: Int
                                  ) extends AdmissionMetricEvent

  final case class BillingModeSwitched(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        previousMode: DynamoDbTable.BillingMode,
                                        newMode: DynamoDbTable.BillingMode
                                      ) extends AdmissionMetricEvent

  final case class ProvisionedCapacityChanged(
                                               eventTime: SimTime,
                                               usecase: Any,
                                               previousCapacity: DynamoDbTable.BillingMode.Provisioned,
                                               newCapacity: DynamoDbTable.BillingMode.Provisioned
                                             ) extends AdmissionMetricEvent

  final case class ConsumedCapacitySnapshot(
                                             eventTime: SimTime,
                                             usecase: Any,
                                             consumedReadUnits: BigDecimal,
                                             consumedWriteUnits: BigDecimal
                                           ) extends AdmissionMetricEvent

  final case class ProvisionedCapacityUtilization(
                                                   eventTime: SimTime,
                                                   usecase: Any,
                                                   consumedReadUnits: BigDecimal,
                                                   consumedWriteUnits: BigDecimal,
                                                   provisionedReadCapacityUnits: Long,
                                                   provisionedWriteCapacityUnits: Long
                                                 ) extends AdmissionMetricEvent

  final case class BillingModeSnapshot(
                                        eventTime: SimTime,
                                        usecase: Any,
                                        billingModeCode: Int  // 0 = on-demand, 1 = provisioned
                                      ) extends AdmissionMetricEvent
