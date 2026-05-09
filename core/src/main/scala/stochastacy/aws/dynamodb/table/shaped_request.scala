package stochastacy.aws.dynamodb.table

import scala.collection.immutable.SortedMap
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
 * Shaped envelope for a TransactWriteItems request. Carries both the merged (aggregated)
 * footprint and maintenance plan for whole-transaction admission, plus per-item data needed
 * to build individual admitted samples for downstream index maintenance.
 */
private[table] final case class ShapedTransactWriteItemsRequest(
  req: TransactWriteItemsRequest,
  executionTarget: DynamoDbTarget,
  admissionTarget: DynamoDbTarget,
  sample: TransactWriteItemsSample,
  throughputDemand: BigDecimal,
  logicalPartitionAccess: LogicalPartitionAccess,
  resolvedPartitionFootprint: ResolvedPartitionFootprint,
  indexMaintenancePlan: Vector[IndexMaintenancePlan],
  perItemResolvedFootprints: Vector[ResolvedPartitionFootprint],
  perItemIndexMaintenancePlans: Vector[Vector[IndexMaintenancePlan]]
) extends ShapedWriteRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Write

/**
 * Shaped envelope for a TransactGetItems request. All reads are strongly consistent.
 */
private[table] final case class ShapedTransactGetItemsRequest(
  req: TransactGetItemsRequest,
  executionTarget: DynamoDbTarget,
  admissionTarget: DynamoDbTarget,
  sample: TransactGetItemsSample,
  throughputDemand: BigDecimal,
  logicalPartitionAccess: LogicalPartitionAccess,
  resolvedPartitionFootprint: ResolvedPartitionFootprint
) extends ShapedRequest:
  override val throughputDimension: DynamoDbThroughputDimension = DynamoDbThroughputDimension.Read

/** Merges multiple partition footprints by summing per-partition demand. */
private[table] def mergeFootprints(footprints: Vector[ResolvedPartitionFootprint]): ResolvedPartitionFootprint =
  require(footprints.nonEmpty, "mergeFootprints requires at least one footprint")
  val totalPartitionCount = footprints.head.totalPartitionCount
  val merged = footprints.foldLeft(SortedMap.empty[Int, BigDecimal]) { (acc, fp) =>
    fp.partitionDemandById.foldLeft(acc) { case (a, (pid, demand)) =>
      a.updated(pid, a.getOrElse(pid, BigDecimal(0)) + demand)
    }
  }
  ResolvedPartitionFootprint(totalPartitionCount, merged)

/** Merges per-item index maintenance plans into one plan per target, summing throughput demand. */
private[table] def mergeIndexMaintenancePlans(perItemPlans: Vector[Vector[IndexMaintenancePlan]]): Vector[IndexMaintenancePlan] =
  val allPlans = perItemPlans.flatten
  if allPlans.isEmpty then return Vector.empty
  allPlans.groupBy(_.target).map { case (_, plans) =>
    plans.head.copy(
      throughputDemand = plans.map(_.throughputDemand).sum,
      storageBytesDelta = plans.map(_.storageBytesDelta).sum,
      logicalPartitionAccess = LogicalPartitionAccess.AllPartitions,
      resolvedPartitionFootprint = mergeFootprints(plans.map(_.resolvedPartitionFootprint))
    )
  }.toVector

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

/**
 * A shared mutable reference for the current billing mode and a tick-ordered queue of
 * pending mode changes. The management event processor (in `DynamoDbTable.componentOfManaged`)
 * races ahead of the request stream in Pekko's fused graph, so it enqueues mode changes here
 * rather than applying them immediately. Each admission stage's `advanceToShaped` drains the
 * queue up to the completed tick before checking for transitions.
 *
 * Thread-safety: all stages run fused in the same Pekko actor.
 */
private[table] class BillingModeRef(
  @volatile var currentMode: DynamoDbTable.BillingMode,
  @volatile var lastSwitchTick: Option[Long] = None
):
  private val pending: scala.collection.mutable.ArrayBuffer[(Long, DynamoDbTable.BillingMode)] =
    scala.collection.mutable.ArrayBuffer.empty

  def enqueueModeChange(tick: Long, newMode: DynamoDbTable.BillingMode): Unit =
    pending += ((tick, newMode))

  /** Returns the effective mode at `tick`, accounting for all pending changes up to that tick. */
  def effectiveModeAt(tick: Long): DynamoDbTable.BillingMode =
    pending.filter(_._1 <= tick).lastOption.map(_._2).getOrElse(currentMode)

  /** Applies all pending changes with tick ≤ `completedTick`, updating `currentMode`. */
  def applyPendingChangesUpTo(completedTick: Long): Unit =
    var latest: DynamoDbTable.BillingMode = currentMode
    var anyApplied = false
    pending.filterInPlace { case (changeTick, mode) =>
      if changeTick <= completedTick then
        latest = mode
        anyApplied = true
        false
      else
        true
    }
    if anyApplied then currentMode = latest
