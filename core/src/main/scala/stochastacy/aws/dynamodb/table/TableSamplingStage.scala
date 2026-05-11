package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}
import scala.collection.immutable.SortedMap

/**
 * The sampling and shaping stage sits upstream of `TableAdmissionStage` in the internal
 * `DynamoDbTable` pipeline. It takes raw `DynamoDBRequest` elements, invokes the
 * use-case sampler, computes throughput demand, resolves logical partition access
 * into concrete partition footprints, derives the index-maintenance plan for writes,
 * and emits a fully-shaped `ShapedRequest` envelope.
 *
 * This stage is stateless with respect to admission concerns. It does not own
 * per-tick usage state, burst reservoirs, or topology evolution logic. It reads
 * the current partition topology from a shared `TopologySnapshotRef` that is
 * owned and updated by the downstream admission stage.
 */
private[table] object TableSamplingStage:

  final case class Config(
                           executionTarget: DynamoDbTarget,
                           admissionTarget: DynamoDbTarget,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           stateModel: TableState,
                           readConsistency: ReadConsistency,
                           partitionCount: Int,
                           indexMaintenanceTargets: Vector[TableAdmissionStage.IndexMaintenanceTargetConfig],
                           gsiWriteScopes: Vector[TableAdmissionStage.GsiWriteScopeConfig],
                           topologyRef: TopologySnapshotRef
                         ):
    require(partitionCount > 0, s"partitionCount must be positive, got $partitionCount")

  def flowOf(
              config: Config
            ): Flow[TimedElement[DynamoDBRequest], TimedElement[ShapedRequest], NotUsed] =

    def samplerFor(request: DynamoDBRequest): UseCaseSampler[TableState] =
      config.useCaseBehaviors.getOrElse(
        request.usecase,
        throw new IllegalArgumentException(s"No table behavior for '${request.usecase}'")
      )

    def logicalAccessFor(request: DynamoDBRequest, sample: Any): LogicalPartitionAccess =
      sample match
        case getItemSample: GetItemSample =>
          PartitionAccessResolver.validateOperationAccess(request, getItemSample.logicalPartitionAccess)
          getItemSample.logicalPartitionAccess
        case querySample: QuerySample =>
          PartitionAccessResolver.validateOperationAccess(request, querySample.logicalPartitionAccess)
          querySample.logicalPartitionAccess
        case scanSample: ScanSample =>
          PartitionAccessResolver.validateOperationAccess(request, scanSample.logicalPartitionAccess)
          scanSample.logicalPartitionAccess
        case writeSample: WriteItemSample =>
          PartitionAccessResolver.validateOperationAccess(request, writeSample.logicalPartitionAccess)
          writeSample.logicalPartitionAccess
        case deleteSample: DeleteItemSample =>
          PartitionAccessResolver.validateOperationAccess(request, deleteSample.logicalPartitionAccess)
          deleteSample.logicalPartitionAccess
        case other =>
          throw new IllegalArgumentException(
            s"Unsupported sampled operation shape '${other.getClass.getSimpleName}' for request '${request.getClass.getSimpleName}'"
          )

    def resolveFootprint(
                          request: DynamoDBRequest,
                          sampledOutcome: Any,
                          throughputDemand: BigDecimal,
                          topologySnapshot: PartitionTopologySnapshot
                        ): ResolvedPartitionFootprint =
      PartitionAccessResolver.resolve(
        access = logicalAccessFor(request, sampledOutcome),
        throughputDemand = throughputDemand,
        topology = topologySnapshot
      )

    def deriveIndexMaintenancePlan(
                                    logicalPartitionAccess: LogicalPartitionAccess,
                                    newBaseItemBytes: Option[Long],
                                    previousBaseItemBytes: Option[Long],
                                    baseTopologySnapshot: PartitionTopologySnapshot,
                                    topologySnapshotsByIndex: Map[String, PartitionTopologySnapshot]
                                  ): Vector[IndexMaintenancePlan] =
      IndexMaintenancePlanDerivation.derivePlans(
        indexMaintenanceTargets = config.indexMaintenanceTargets,
        gsiWriteScopes = config.gsiWriteScopes,
        fallbackPartitionCount = config.partitionCount,
        logicalPartitionAccess = logicalPartitionAccess,
        newBaseItemBytes = newBaseItemBytes,
        previousBaseItemBytes = previousBaseItemBytes,
        baseTopologySnapshot = baseTopologySnapshot,
        gsiTopologySnapshots = topologySnapshotsByIndex
      )

    def shape(request: DynamoDBRequest): ShapedRequest =
      val topologySnapshot = config.topologyRef.baseTopology
      val gsiTopologySnapshots = config.topologyRef.gsiTopologies

      request match
        case r: GetItemRequest =>
          val sample = samplerFor(r).getItem(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.readCapacityUnitsFor(sample.itemBytes, config.readConsistency)
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          ShapedGetItemRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            readConsistency = config.readConsistency,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint
          )

        case r: QueryRequest =>
          val sample = samplerFor(r).query(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency)
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          ShapedQueryRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint
          )

        case r: ScanRequest =>
          val sample = samplerFor(r).scan(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency)
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          ShapedScanRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint
          )

        case r: PutItemRequest =>
          val sample = samplerFor(r).putItem(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes)
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          val maintenancePlan = deriveIndexMaintenancePlan(
            logicalPartitionAccess = access,
            newBaseItemBytes = Some(sample.writtenItemBytes),
            previousBaseItemBytes = sample.previousItemBytes,
            baseTopologySnapshot = topologySnapshot,
            topologySnapshotsByIndex = gsiTopologySnapshots
          )
          ShapedPutItemRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint,
            indexMaintenancePlan = maintenancePlan
          )

        case r: UpdateItemRequest =>
          val sample = samplerFor(r).updateItem(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes)
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          val maintenancePlan = deriveIndexMaintenancePlan(
            logicalPartitionAccess = access,
            newBaseItemBytes = Some(sample.writtenItemBytes),
            previousBaseItemBytes = sample.previousItemBytes,
            baseTopologySnapshot = topologySnapshot,
            topologySnapshotsByIndex = gsiTopologySnapshots
          )
          ShapedUpdateItemRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint,
            indexMaintenancePlan = maintenancePlan
          )

        case r: DeleteItemRequest =>
          val sample = samplerFor(r).deleteItem(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.deletedItemBytes.getOrElse(0L))
          val access = logicalAccessFor(r, sample)
          val footprint = resolveFootprint(r, sample, demand, topologySnapshot)
          val maintenancePlan = deriveIndexMaintenancePlan(
            logicalPartitionAccess = access,
            newBaseItemBytes = None,
            previousBaseItemBytes = sample.deletedItemBytes,
            baseTopologySnapshot = topologySnapshot,
            topologySnapshotsByIndex = gsiTopologySnapshots
          )
          ShapedDeleteItemRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = demand,
            logicalPartitionAccess = access,
            resolvedPartitionFootprint = footprint,
            indexMaintenancePlan = maintenancePlan
          )

        case r: TransactWriteItemsRequest =>
          val sample = samplerFor(r).transactWriteItems(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val perItemSamples = sample.items
          val perItemDemands = perItemSamples.map(item => TableThroughputMath.transactionalWriteCapacityUnitsFor(item.writtenItemBytes))
          val totalDemand = perItemDemands.sum
          val perItemAccess = perItemSamples.map(_.logicalPartitionAccess)
          val perItemFootprints = perItemSamples.zip(perItemDemands).map { case (item, demand) =>
            PartitionAccessResolver.resolve(
              access = item.logicalPartitionAccess,
              throughputDemand = demand,
              topology = topologySnapshot
            )
          }
          val perItemPlans = perItemSamples.map { item =>
            deriveIndexMaintenancePlan(
              logicalPartitionAccess = item.logicalPartitionAccess,
              newBaseItemBytes = Some(item.writtenItemBytes),
              previousBaseItemBytes = item.previousItemBytes,
              baseTopologySnapshot = topologySnapshot,
              topologySnapshotsByIndex = gsiTopologySnapshots
            )
          }
          ShapedTransactWriteItemsRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = totalDemand,
            logicalPartitionAccess = LogicalPartitionAccess.AllPartitions,
            resolvedPartitionFootprint = mergeFootprints(perItemFootprints),
            indexMaintenancePlan = mergeIndexMaintenancePlans(perItemPlans),
            perItemResolvedFootprints = perItemFootprints,
            perItemIndexMaintenancePlans = perItemPlans
          )

        case r: TransactGetItemsRequest =>
          val sample = samplerFor(r).transactGetItems(r, SamplerContext(config.stateModel, r.eventTime.ticks))
          val perItemDemands = sample.items.map(item => TableThroughputMath.transactionalReadCapacityUnitsFor(item.itemBytes))
          val totalDemand = perItemDemands.sum
          val perItemFootprints = sample.items.zip(perItemDemands).map { case (item, demand) =>
            PartitionAccessResolver.resolve(
              access = item.logicalPartitionAccess,
              throughputDemand = demand,
              topology = topologySnapshot
            )
          }
          ShapedTransactGetItemsRequest(
            req = r,
            executionTarget = config.executionTarget,
            admissionTarget = config.admissionTarget,
            sample = sample,
            throughputDemand = totalDemand,
            logicalPartitionAccess = LogicalPartitionAccess.AllPartitions,
            resolvedPartitionFootprint = mergeFootprints(perItemFootprints)
          )

        case _: PartiQLQueryRequest =>
          throw new UnsupportedOperationException("PartiQL query execution is not yet supported")

    Flow[TimedElement[DynamoDBRequest]].map[TimedElement[ShapedRequest]] {
      case t: TimedControlEvent => t
      case request: DynamoDBRequest => shape(request)
    }
