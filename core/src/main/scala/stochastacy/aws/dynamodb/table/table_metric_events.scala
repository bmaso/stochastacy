package stochastacy.aws.dynamodb.table

import stochastacy.aws.MetricEvent
import stochastacy.aws.dynamodb.{DynamoDbOperationKind, DynamoDbThroughputDimension, DynamoDbThrottleReason}
import stochastacy.sim.SimTime

trait TableMetricEvent extends MetricEvent

sealed trait Stage1MetricEvent extends TableMetricEvent

enum Stage1AdmissionMode:
  case Normal
  case BurstBacked

object Stage1MetricEvent:

  final case class RequestAdmitted(
                                    eventTime: SimTime,
                                    usecase: Any,
                                    operation: DynamoDbOperationKind,
                                    target: DynamoDbTarget,
                                    dimension: DynamoDbThroughputDimension,
                                    throughputDemand: BigDecimal,
                                    admissionMode: Stage1AdmissionMode,
                                    burstConsumedRequestUnits: BigDecimal,
                                    burstRemainingRequestUnits: BigDecimal,
                                    resolvedPartitionFootprint: ResolvedPartitionFootprint
                                  ) extends Stage1MetricEvent

  final case class RequestThrottled(
                                     eventTime: SimTime,
                                     usecase: Any,
                                     operation: DynamoDbOperationKind,
                                     target: DynamoDbTarget,
                                     dimension: DynamoDbThroughputDimension,
                                     throughputDemand: BigDecimal,
                                     reason: DynamoDbThrottleReason,
                                     burstAvailableRequestUnits: BigDecimal,
                                     resolvedPartitionFootprint: ResolvedPartitionFootprint
                                   ) extends Stage1MetricEvent
