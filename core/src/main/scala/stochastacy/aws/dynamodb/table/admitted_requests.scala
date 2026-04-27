package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedEvent}

private[table] sealed trait AdmittedRequestSample extends TimedEvent:
  def req: DynamoDBRequest
  def executionTarget: DynamoDbTarget
  def admissionTarget: DynamoDbTarget
  def throughputDimension: DynamoDbThroughputDimension
  def throughputDemand: BigDecimal
  def resolvedPartitionFootprint: ResolvedPartitionFootprint
  override val eventTime: SimTime = req.eventTime
  override val usecase: Any = req.usecase

private[table] sealed trait AdmittedWriteRequestSample extends AdmittedRequestSample:
  def indexMaintenancePlan: Vector[IndexMaintenancePlan]

private[table] final case class AdmittedGetItemSample(
                                                       req: GetItemRequest,
                                                       executionTarget: DynamoDbTarget,
                                                       admissionTarget: DynamoDbTarget,
                                                       readConsistency: ReadConsistency,
                                                       sample: GetItemSample,
                                                       throughputDemand: BigDecimal,
                                                       resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                     ) extends AdmittedRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class AdmittedQuerySample(
                                                     req: QueryRequest,
                                                     executionTarget: DynamoDbTarget,
                                                     admissionTarget: DynamoDbTarget,
                                                     sample: QuerySample,
                                                     throughputDemand: BigDecimal,
                                                     resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                   ) extends AdmittedRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class AdmittedScanSample(
                                                    req: ScanRequest,
                                                    executionTarget: DynamoDbTarget,
                                                    admissionTarget: DynamoDbTarget,
                                                    sample: ScanSample,
                                                    throughputDemand: BigDecimal,
                                                    resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                  ) extends AdmittedRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class AdmittedPutItemSample(
                                                       req: PutItemRequest,
                                                       executionTarget: DynamoDbTarget,
                                                       admissionTarget: DynamoDbTarget,
                                                       sample: PutItemSample,
                                                       throughputDemand: BigDecimal,
                                                       resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                       indexMaintenancePlan: Vector[IndexMaintenancePlan] = Vector.empty
                                                     ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class AdmittedUpdateItemSample(
                                                          req: UpdateItemRequest,
                                                          executionTarget: DynamoDbTarget,
                                                          admissionTarget: DynamoDbTarget,
                                                          sample: UpdateItemSample,
                                                          throughputDemand: BigDecimal,
                                                          resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                          indexMaintenancePlan: Vector[IndexMaintenancePlan] = Vector.empty
                                                        ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class AdmittedDeleteItemSample(
                                                          req: DeleteItemRequest,
                                                          executionTarget: DynamoDbTarget,
                                                          admissionTarget: DynamoDbTarget,
                                                          sample: DeleteItemSample,
                                                          throughputDemand: BigDecimal,
                                                          resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                          indexMaintenancePlan: Vector[IndexMaintenancePlan] = Vector.empty
                                                        ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

/**
 * Envelope marking an admitted write sample as originating from cross-region replication rather
 * than from a local client request. Extends `AdmittedWriteRequestSample` by pure delegation so
 * it flows transparently through the existing `TimedElement[AdmittedRequestSample]` streams.
 * Storage and index-maintenance stages pattern-match on `Replicated[?]` to emit
 * `ReplicatedWriteCapacityConsumed` instead of `WriteCapacityConsumed`.
 */
private[table] final case class Replicated[+X <: AdmittedWriteRequestSample](sample: X)
    extends AdmittedWriteRequestSample:
  override def req: DynamoDBRequest                              = sample.req
  override def executionTarget: DynamoDbTarget                   = sample.executionTarget
  override def admissionTarget: DynamoDbTarget                   = sample.admissionTarget
  override def throughputDimension: DynamoDbThroughputDimension  = sample.throughputDimension
  override def throughputDemand: BigDecimal                      = sample.throughputDemand
  override def resolvedPartitionFootprint: ResolvedPartitionFootprint = sample.resolvedPartitionFootprint
  override def indexMaintenancePlan: Vector[IndexMaintenancePlan] = sample.indexMaintenancePlan
