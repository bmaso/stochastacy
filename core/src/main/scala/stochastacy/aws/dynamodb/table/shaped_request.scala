package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedEvent}

/**
 * A shaped request is the intermediate envelope between the sampling/shaping stage
 * and the admission/throttling stage. It carries all sampled and derived facts
 * about a request so the admission stage can make admission decisions without
 * re-invoking the sampler or re-deriving throughput demand, partition footprints,
 * or index-maintenance plans.
 */
private[table] sealed trait ShapedRequest extends TimedEvent:
  def req: DynamoDBRequest
  def executionTarget: DynamoDbTarget
  def admissionTarget: DynamoDbTarget
  def throughputDimension: DynamoDbThroughputDimension
  def throughputDemand: BigDecimal
  def logicalPartitionAccess: LogicalPartitionAccess
  def resolvedPartitionFootprint: ResolvedPartitionFootprint
  override val eventTime: SimTime = req.eventTime
  override val usecase: Any = req.usecase

private[table] sealed trait ShapedWriteRequest extends ShapedRequest:
  def indexMaintenancePlan: Vector[IndexMaintenancePlan]

private[table] final case class ShapedGetItemRequest(
                                                      req: GetItemRequest,
                                                      executionTarget: DynamoDbTarget,
                                                      admissionTarget: DynamoDbTarget,
                                                      readConsistency: ReadConsistency,
                                                      sample: GetItemSample,
                                                      throughputDemand: BigDecimal,
                                                      logicalPartitionAccess: LogicalPartitionAccess,
                                                      resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                    ) extends ShapedRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class ShapedQueryRequest(
                                                    req: QueryRequest,
                                                    executionTarget: DynamoDbTarget,
                                                    admissionTarget: DynamoDbTarget,
                                                    sample: QuerySample,
                                                    throughputDemand: BigDecimal,
                                                    logicalPartitionAccess: LogicalPartitionAccess,
                                                    resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                  ) extends ShapedRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class ShapedScanRequest(
                                                   req: ScanRequest,
                                                   executionTarget: DynamoDbTarget,
                                                   admissionTarget: DynamoDbTarget,
                                                   sample: ScanSample,
                                                   throughputDemand: BigDecimal,
                                                   logicalPartitionAccess: LogicalPartitionAccess,
                                                   resolvedPartitionFootprint: ResolvedPartitionFootprint
                                                 ) extends ShapedRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

private[table] final case class ShapedPutItemRequest(
                                                      req: PutItemRequest,
                                                      executionTarget: DynamoDbTarget,
                                                      admissionTarget: DynamoDbTarget,
                                                      sample: PutItemSample,
                                                      throughputDemand: BigDecimal,
                                                      logicalPartitionAccess: LogicalPartitionAccess,
                                                      resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                      indexMaintenancePlan: Vector[IndexMaintenancePlan]
                                                    ) extends ShapedWriteRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class ShapedUpdateItemRequest(
                                                         req: UpdateItemRequest,
                                                         executionTarget: DynamoDbTarget,
                                                         admissionTarget: DynamoDbTarget,
                                                         sample: UpdateItemSample,
                                                         throughputDemand: BigDecimal,
                                                         logicalPartitionAccess: LogicalPartitionAccess,
                                                         resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                         indexMaintenancePlan: Vector[IndexMaintenancePlan]
                                                       ) extends ShapedWriteRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

private[table] final case class ShapedDeleteItemRequest(
                                                         req: DeleteItemRequest,
                                                         executionTarget: DynamoDbTarget,
                                                         admissionTarget: DynamoDbTarget,
                                                         sample: DeleteItemSample,
                                                         throughputDemand: BigDecimal,
                                                         logicalPartitionAccess: LogicalPartitionAccess,
                                                         resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                         indexMaintenancePlan: Vector[IndexMaintenancePlan]
                                                       ) extends ShapedWriteRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

/**
 * A shared mutable reference for the current partition topology snapshots.
 * Owned and updated by the admission stage (TableAdmissionStage) at tick boundaries.
 * Read by the sampling stage (TableSamplingStage) when resolving partition footprints.
 *
 * Thread-safety note: both stages run fused in the same Pekko actor, so there is
 * no concurrency concern. The @volatile annotations serve as documentation that
 * the reference is shared across stages.
 */
private[table] class TopologySnapshotRef(
                                          @volatile var baseTopology: PartitionTopologySnapshot,
                                          @volatile var gsiTopologies: Map[String, PartitionTopologySnapshot]
                                        )
