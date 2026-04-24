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

private[table] final case class GsiWritePropagation(
                                                     indexTarget: DynamoDbTarget.GlobalSecondaryIndex,
                                                     throughputDemand: BigDecimal,
                                                     logicalPartitionAccess: LogicalPartitionAccess,
                                                     resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                     writtenItemBytes: Option[Long],
                                                     previousItemBytes: Option[Long],
                                                     deletedItemBytes: Option[Long]
                                                   )

private[table] sealed trait AdmittedWriteRequestSample extends AdmittedRequestSample:
  def gsiWritePropagationPlan: Vector[GsiWritePropagation]

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
                                                       gsiWritePropagationPlan: Vector[GsiWritePropagation] = Vector.empty
                                                     ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class AdmittedUpdateItemSample(
                                                          req: UpdateItemRequest,
                                                          executionTarget: DynamoDbTarget,
                                                          admissionTarget: DynamoDbTarget,
                                                          sample: UpdateItemSample,
                                                          throughputDemand: BigDecimal,
                                                          resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                          gsiWritePropagationPlan: Vector[GsiWritePropagation] = Vector.empty
                                                        ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class AdmittedDeleteItemSample(
                                                          req: DeleteItemRequest,
                                                          executionTarget: DynamoDbTarget,
                                                          admissionTarget: DynamoDbTarget,
                                                          sample: DeleteItemSample,
                                                          throughputDemand: BigDecimal,
                                                          resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                          gsiWritePropagationPlan: Vector[GsiWritePropagation] = Vector.empty
                                                        ) extends AdmittedWriteRequestSample:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write
