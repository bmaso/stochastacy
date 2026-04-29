package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}
import stochastacy.sim.ticks

object TableAdmissionStage:

  final case class IndexMaintenanceTargetConfig(
                                                 target: DynamoDbTarget,
                                                 projection: DynamoDbTable.IndexProjection
                                               )

  final case class GsiWriteScopeConfig(
                                        target: DynamoDbTarget.GlobalSecondaryIndex,
                                        stateModel: TableState,
                                        maxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                        maxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                        adaptiveMaxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                        burstRetentionWindowSeconds: Option[Int] = None,
                                        initialWriteBurstRequestUnits: Option[BigDecimal] = None,
                                        dynamicPartitionTopologyConfig: Option[DynamicPartitionTopologyConfig] = None
                                      ):
    require(maxWriteRequestUnitsPerSecond.forall(_ > 0), "maxWriteRequestUnitsPerSecond must be positive when defined")
    require(
      maxWriteRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "maxWriteRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.forall(adaptive =>
        maxWriteRequestUnitsPerSecondPerPartition.forall(baseline => adaptive >= baseline)
      ),
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition must be >= maxWriteRequestUnitsPerSecondPerPartition when both are defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.isEmpty || maxWriteRequestUnitsPerSecondPerPartition.isDefined,
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition requires maxWriteRequestUnitsPerSecondPerPartition to be defined"
    )
    require(burstRetentionWindowSeconds.forall(_ > 0), "burstRetentionWindowSeconds must be positive when defined")
    require(initialWriteBurstRequestUnits.forall(_ >= 0), "initialWriteBurstRequestUnits must be non-negative when defined")
    require(
      initialWriteBurstRequestUnits.isEmpty || maxWriteRequestUnitsPerSecond.isDefined,
      "initialWriteBurstRequestUnits requires maxWriteRequestUnitsPerSecond to be defined"
    )

  final case class DynamicPartitionTopologyConfig(
                                                   initialPartitionCount: Int,
                                                   storageSplitThresholdBytes: Option[Long] = None,
                                                   readThroughputGrowthSplitThresholdRequestUnitsPerSecond: Option[BigDecimal] = None,
                                                   writeThroughputGrowthSplitThresholdRequestUnitsPerSecond: Option[BigDecimal] = None,
                                                   heatSplitSustainWindowSeconds: Int = 1,
                                                   readHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                   writeHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                   maxPartitionCount: Option[Int] = None
                                                 ):
    require(initialPartitionCount > 0, s"initialPartitionCount must be positive, got $initialPartitionCount")
    require(storageSplitThresholdBytes.forall(_ > 0L), "storageSplitThresholdBytes must be positive when defined")
    require(
      readThroughputGrowthSplitThresholdRequestUnitsPerSecond.forall(_ > 0),
      "readThroughputGrowthSplitThresholdRequestUnitsPerSecond must be positive when defined"
    )
    require(
      writeThroughputGrowthSplitThresholdRequestUnitsPerSecond.forall(_ > 0),
      "writeThroughputGrowthSplitThresholdRequestUnitsPerSecond must be positive when defined"
    )
    require(heatSplitSustainWindowSeconds > 0, s"heatSplitSustainWindowSeconds must be positive, got $heatSplitSustainWindowSeconds")
    require(
      readHeatSplitTriggerRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "readHeatSplitTriggerRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      writeHeatSplitTriggerRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "writeHeatSplitTriggerRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(maxPartitionCount.forall(_ >= initialPartitionCount), "maxPartitionCount must be >= initialPartitionCount when defined")

  final case class Config(
                           executionTarget: DynamoDbTarget,
                           admissionTarget: DynamoDbTarget,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           stateModel: TableState,
                           readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                           maxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                           maxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                           partitionCount: Int = 1,
                           maxReadRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                           maxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                           adaptiveMaxReadRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                           adaptiveMaxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                           burstRetentionWindowSeconds: Option[Int] = None,
                           initialReadBurstRequestUnits: Option[BigDecimal] = None,
                           initialWriteBurstRequestUnits: Option[BigDecimal] = None,
                           dynamicPartitionTopologyConfig: Option[DynamicPartitionTopologyConfig] = None,
                           billingMode: DynamoDbTable.BillingMode = DynamoDbTable.BillingMode.OnDemand(),
                           indexMaintenanceTargets: Vector[IndexMaintenanceTargetConfig] = Vector.empty,
                           gsiWriteScopes: Vector[GsiWriteScopeConfig] = Vector.empty
                         ):
    require(maxReadRequestUnitsPerSecond.forall(_ > 0), "maxReadRequestUnitsPerSecond must be positive when defined")
    require(maxWriteRequestUnitsPerSecond.forall(_ > 0), "maxWriteRequestUnitsPerSecond must be positive when defined")
    require(partitionCount > 0, s"partitionCount must be positive, got $partitionCount")
    require(
      maxReadRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "maxReadRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      maxWriteRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "maxWriteRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      adaptiveMaxReadRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "adaptiveMaxReadRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      adaptiveMaxReadRequestUnitsPerSecondPerPartition.forall(adaptive =>
        maxReadRequestUnitsPerSecondPerPartition.forall(baseline => adaptive >= baseline)
      ),
      "adaptiveMaxReadRequestUnitsPerSecondPerPartition must be >= maxReadRequestUnitsPerSecondPerPartition when both are defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.forall(adaptive =>
        maxWriteRequestUnitsPerSecondPerPartition.forall(baseline => adaptive >= baseline)
      ),
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition must be >= maxWriteRequestUnitsPerSecondPerPartition when both are defined"
    )
    require(
      adaptiveMaxReadRequestUnitsPerSecondPerPartition.isEmpty || maxReadRequestUnitsPerSecondPerPartition.isDefined,
      "adaptiveMaxReadRequestUnitsPerSecondPerPartition requires maxReadRequestUnitsPerSecondPerPartition to be defined"
    )
    require(
      adaptiveMaxWriteRequestUnitsPerSecondPerPartition.isEmpty || maxWriteRequestUnitsPerSecondPerPartition.isDefined,
      "adaptiveMaxWriteRequestUnitsPerSecondPerPartition requires maxWriteRequestUnitsPerSecondPerPartition to be defined"
    )
    require(burstRetentionWindowSeconds.forall(_ > 0), "burstRetentionWindowSeconds must be positive when defined")
    require(
      initialReadBurstRequestUnits.forall(_ >= 0),
      "initialReadBurstRequestUnits must be non-negative when defined"
    )
    require(
      initialWriteBurstRequestUnits.forall(_ >= 0),
      "initialWriteBurstRequestUnits must be non-negative when defined"
    )
    require(
      initialReadBurstRequestUnits.isEmpty || maxReadRequestUnitsPerSecond.isDefined,
      "initialReadBurstRequestUnits requires maxReadRequestUnitsPerSecond to be defined"
    )
    require(
      initialWriteBurstRequestUnits.isEmpty || maxWriteRequestUnitsPerSecond.isDefined,
      "initialWriteBurstRequestUnits requires maxWriteRequestUnitsPerSecond to be defined"
    )
    require(
      dynamicPartitionTopologyConfig.forall(_.initialPartitionCount > 0),
      "dynamicPartitionTopologyConfig.initialPartitionCount must be positive when defined"
    )
    require(
      gsiWriteScopes.map(_.target.indexName).distinct.size == gsiWriteScopes.size,
      "gsiWriteScopes must not contain duplicate index targets"
    )
    require(
      indexMaintenanceTargets.map(_.target).distinct.size == indexMaintenanceTargets.size,
      "indexMaintenanceTargets must not contain duplicate targets"
    )
    billingMode match
      case _: DynamoDbTable.BillingMode.Provisioned =>
        require(
          adaptiveMaxReadRequestUnitsPerSecondPerPartition.isEmpty,
          "adaptiveMaxReadRequestUnitsPerSecondPerPartition must be None in provisioned billing mode"
        )
        require(
          adaptiveMaxWriteRequestUnitsPerSecondPerPartition.isEmpty,
          "adaptiveMaxWriteRequestUnitsPerSecondPerPartition must be None in provisioned billing mode"
        )
      case _ => ()

  private final case class BurstReservoir(
                                           currentRequestUnits: BigDecimal,
                                           maxRequestUnits: BigDecimal
                                         ):
    def consume(requestUnits: BigDecimal): BurstReservoir =
      copy(currentRequestUnits = (currentRequestUnits - requestUnits).max(BigDecimal(0)))

    def replenish(requestUnits: BigDecimal): BurstReservoir =
      copy(currentRequestUnits = (currentRequestUnits + requestUnits).min(maxRequestUnits))

  private object BurstReservoir:
    def from(limit: Option[BigDecimal], retentionWindowSeconds: Option[Int], initial: Option[BigDecimal]): BurstReservoir =
      val maxUnits =
        (for
          throughputLimit <- limit
          retentionWindow <- retentionWindowSeconds
        yield throughputLimit * BigDecimal(retentionWindow)).getOrElse(BigDecimal(0))
      val initialUnits = initial.getOrElse(maxUnits).min(maxUnits)
      BurstReservoir(initialUnits, maxUnits)

  private sealed trait AdmissionDecision extends TimedEvent:
    def request: DynamoDBRequest
    override val eventTime: SimTime = request.eventTime
    override val usecase: Any = request.usecase

  private final case class Admitted(
                                    request: DynamoDBRequest,
                                    sample: AdmittedRequestSample,
                                    metric: AdmissionMetricEvent.RequestAdmitted,
                                    gsiWriteAdmissions: Vector[GsiWriteAdmission] = Vector.empty
                                   ) extends AdmissionDecision
  private final case class Throttled(
                                      request: DynamoDBRequest,
                                      response: ThrottledResponse,
                                      metric: AdmissionMetricEvent.RequestThrottled
                                    ) extends AdmissionDecision

  private final case class PerTickUsageState(
                                              readUnits: BigDecimal = BigDecimal(0),
                                              writeUnits: BigDecimal = BigDecimal(0),
                                              readUnitsChargedToSteadyState: BigDecimal = BigDecimal(0),
                                              writeUnitsChargedToSteadyState: BigDecimal = BigDecimal(0),
                                              readUnitsByPartition: Map[Int, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                                              writeUnitsByPartition: Map[Int, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0))
                                            ):
    def afterAdmission(
                        sample: AdmittedRequestSample,
                        steadyStateLimit: Option[BigDecimal]
                      ): PerTickUsageState =
      sample.throughputDimension match
        case DynamoDbThroughputDimension.Read =>
          val steadyStateCharge =
            chargedToSteadyState(sample.throughputDemand, readUnitsChargedToSteadyState, steadyStateLimit)
          copy(
            readUnits = readUnits + sample.throughputDemand,
            readUnitsChargedToSteadyState = readUnitsChargedToSteadyState + steadyStateCharge,
            readUnitsByPartition = accumulateByPartition(readUnitsByPartition, sample.resolvedPartitionFootprint)
          )
        case DynamoDbThroughputDimension.Write =>
          val steadyStateCharge =
            chargedToSteadyState(sample.throughputDemand, writeUnitsChargedToSteadyState, steadyStateLimit)
          copy(
            writeUnits = writeUnits + sample.throughputDemand,
            writeUnitsChargedToSteadyState = writeUnitsChargedToSteadyState + steadyStateCharge,
            writeUnitsByPartition = accumulateByPartition(writeUnitsByPartition, sample.resolvedPartitionFootprint)
          )

    def afterInternalWriteAdmission(
                                     throughputDemand: BigDecimal,
                                     resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                     steadyStateLimit: Option[BigDecimal]
                                   ): PerTickUsageState =
      val steadyStateCharge =
        chargedToSteadyState(throughputDemand, writeUnitsChargedToSteadyState, steadyStateLimit)
      copy(
        writeUnits = writeUnits + throughputDemand,
        writeUnitsChargedToSteadyState = writeUnitsChargedToSteadyState + steadyStateCharge,
        writeUnitsByPartition = accumulateByPartition(writeUnitsByPartition, resolvedPartitionFootprint)
      )

  private final case class BurstState(
                                       readBurst: BurstReservoir,
                                       writeBurst: BurstReservoir
                                     ):
    def replenish(usageState: PerTickUsageState, config: Config): BurstState =
      val replenishedReadBurst =
        config.maxReadRequestUnitsPerSecond match
          case Some(limit) =>
            val unused = (limit - usageState.readUnitsChargedToSteadyState).max(BigDecimal(0))
            readBurst.replenish(unused)
          case None => readBurst

      val replenishedWriteBurst =
        config.maxWriteRequestUnitsPerSecond match
          case Some(limit) =>
            val unused = (limit - usageState.writeUnitsChargedToSteadyState).max(BigDecimal(0))
            writeBurst.replenish(unused)
          case None => writeBurst

      copy(readBurst = replenishedReadBurst, writeBurst = replenishedWriteBurst)

    def availableFor(dimension: DynamoDbThroughputDimension): BigDecimal =
      dimension match
        case DynamoDbThroughputDimension.Read => readBurst.currentRequestUnits
        case DynamoDbThroughputDimension.Write => writeBurst.currentRequestUnits

    def consume(dimension: DynamoDbThroughputDimension, requestUnits: BigDecimal): BurstState =
      dimension match
        case DynamoDbThroughputDimension.Read => copy(readBurst = readBurst.consume(requestUnits))
        case DynamoDbThroughputDimension.Write => copy(writeBurst = writeBurst.consume(requestUnits))

  private final case class AdaptiveRelief(
                                           consumedRequestUnits: BigDecimal,
                                           availableRequestUnits: BigDecimal,
                                           remainingHotPartitionOverage: BigDecimal
                                         )

  private final case class GsiWriteAdmission(
                                              indexName: String,
                                              throughputDemand: BigDecimal,
                                              resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                              burstConsumedRequestUnits: BigDecimal
                                            )

  private final case class WriteScopeEvaluation(
                                                 target: DynamoDbTarget,
                                                 throughputDemand: BigDecimal,
                                                 resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                 adaptiveConsumedRequestUnits: BigDecimal,
                                                 adaptiveAvailableRequestUnits: BigDecimal,
                                                 requiredBurst: BigDecimal,
                                                 burstAvailable: BigDecimal,
                                                 blockingReason: Option[DynamoDbThrottleReason]
                                               )

  private final case class DynamicTopologyHeatState(
                                                     consecutiveReadHotTicks: Int = 0,
                                                     consecutiveWriteHotTicks: Int = 0
                                                   )

  private final case class DynamicTopologyState(
                                                 snapshot: PartitionTopologySnapshot,
                                                 heatState: DynamicTopologyHeatState = DynamicTopologyHeatState()
                                               )

  def componentOf(
                   config: Config
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[AdmissionMetricEvent]
    ],
    NotUsed
  ] =
    componentOfComposed(config)

  /**
   * A composed component that wires `TableSamplingStage` upstream of the shaped admission stage.
   * The external shape is identical to `componentOf`: it takes `TimedElement[DynamoDBRequest]` and
   * produces the same three output streams (admitted samples, throttled responses, metrics).
   */
  def componentOfComposed(
                            config: Config
                          ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[AdmissionMetricEvent]
    ],
    NotUsed
  ] =
    val initialBaseTopology = PartitionTopologySnapshot(
      partitionCount = config.dynamicPartitionTopologyConfig.map(_.initialPartitionCount).getOrElse(config.partitionCount),
      version = 0L,
      effectiveFromTick = 0L
    )
    val initialGsiTopologies = config.gsiWriteScopes.map { scope =>
      scope.target.indexName -> PartitionTopologySnapshot(
        partitionCount = scope.dynamicPartitionTopologyConfig.map(_.initialPartitionCount).getOrElse(config.partitionCount),
        version = 0L,
        effectiveFromTick = 0L
      )
    }.toMap
    val topologyRef = new TopologySnapshotRef(initialBaseTopology, initialGsiTopologies)

    val samplingConfig = TableSamplingStage.Config(
      executionTarget = config.executionTarget,
      admissionTarget = config.admissionTarget,
      useCaseBehaviors = config.useCaseBehaviors,
      stateModel = config.stateModel,
      readConsistency = config.readConsistency,
      partitionCount = config.partitionCount,
      indexMaintenanceTargets = config.indexMaintenanceTargets,
      gsiWriteScopes = config.gsiWriteScopes,
      topologyRef = topologyRef
    )

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val samplingStage = b.add(TableSamplingStage.flowOf(samplingConfig))
      val admissionStage = b.add(componentOfShaped(config, topologyRef))

      samplingStage.out ~> admissionStage.in

      new FanOutShape3(
        samplingStage.in,
        admissionStage.out0,
        admissionStage.out1,
        admissionStage.out2
      )
    }

  /**
   * An admission-only component that accepts pre-shaped `ShapedRequest` elements
   * rather than raw `DynamoDBRequest` elements. All sampling, demand calculation,
   * partition resolution, and index-maintenance-plan derivation have already been
   * performed by the upstream `TableSamplingStage`.
   */
  def componentOfShaped(
                          config: Config,
                          topologyRef: TopologySnapshotRef
                        ): Graph[
    FanOutShape3[
      TimedElement[ShapedRequest],
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[AdmissionMetricEvent]
    ],
    NotUsed
  ] =
    def metricForAdmissionShaped(
                                  shaped: ShapedRequest,
                                  admissionMode: AdmissionMode,
                                  adaptiveConsumedRequestUnits: BigDecimal,
                                  adaptiveAvailableRequestUnits: BigDecimal,
                                  burstConsumedRequestUnits: BigDecimal,
                                  burstRemainingRequestUnits: BigDecimal,
                                  topologyPartitionCount: Int,
                                  indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                                ): AdmissionMetricEvent.RequestAdmitted =
      AdmissionMetricEvent.RequestAdmitted(
        eventTime = shaped.req.eventTime,
        usecase = shaped.req.usecase,
        operation = DynamoDbOperationKind.fromRequest(shaped.req),
        target = shaped.admissionTarget,
        dimension = shaped.throughputDimension,
        throughputDemand = shaped.throughputDemand,
        admissionMode = admissionMode,
        adaptiveConsumedRequestUnits = adaptiveConsumedRequestUnits,
        adaptiveAvailableRequestUnits = adaptiveAvailableRequestUnits,
        burstConsumedRequestUnits = burstConsumedRequestUnits,
        burstRemainingRequestUnits = burstRemainingRequestUnits,
        topologyPartitionCount = topologyPartitionCount,
        resolvedPartitionFootprint = shaped.resolvedPartitionFootprint,
        indexMaintenanceSummary = indexMaintenanceSummary
      )

    def metricForThrottleShaped(
                                 shaped: ShapedRequest,
                                 reason: DynamoDbThrottleReason,
                                 adaptiveAvailableRequestUnits: BigDecimal,
                                 burstAvailableRequestUnits: BigDecimal,
                                 topologyPartitionCount: Int,
                                 target: DynamoDbTarget,
                                 dimension: DynamoDbThroughputDimension,
                                 throughputDemand: BigDecimal,
                                 resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                 indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                               ): AdmissionMetricEvent.RequestThrottled =
      AdmissionMetricEvent.RequestThrottled(
        eventTime = shaped.req.eventTime,
        usecase = shaped.req.usecase,
        operation = DynamoDbOperationKind.fromRequest(shaped.req),
        target = target,
        dimension = dimension,
        throughputDemand = throughputDemand,
        reason = reason,
        adaptiveAvailableRequestUnits = adaptiveAvailableRequestUnits,
        burstAvailableRequestUnits = burstAvailableRequestUnits,
        topologyPartitionCount = topologyPartitionCount,
        resolvedPartitionFootprint = resolvedPartitionFootprint,
        indexMaintenanceSummary = indexMaintenanceSummary
      )

    def topologyMetricForShaped(
                                 eventTime: SimTime,
                                 previousPartitionCount: Int,
                                 newPartitionCount: Int,
                                 reason: TopologyChangeReason
                               ): AdmissionMetricEvent.TopologyChanged =
      AdmissionMetricEvent.TopologyChanged(
        eventTime = eventTime,
        usecase = "topology-change",
        scope =
          config.executionTarget match
            case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) => TopologyScope.GlobalSecondaryIndex(indexName)
            case _ => TopologyScope.Table,
        reason = reason,
        previousPartitionCount = previousPartitionCount,
        newPartitionCount = newPartitionCount
      )

    def throttledShaped(
                         shaped: ShapedRequest,
                         target: DynamoDbTarget,
                         dimension: DynamoDbThroughputDimension,
                         throughputDemand: BigDecimal,
                         reason: DynamoDbThrottleReason,
                         adaptiveAvailableRequestUnits: BigDecimal,
                         burstAvailableRequestUnits: BigDecimal,
                         topologyPartitionCount: Int,
                         resolvedPartitionFootprint: ResolvedPartitionFootprint,
                         indexMaintenanceSummary: Vector[IndexMaintenanceSummary] = Vector.empty
                       ): Throttled =
      Throttled(
        request = shaped.req,
        response = ThrottledResponse(
          eventTime = shaped.req.eventTime,
          usecase = shaped.req.usecase,
          operation = DynamoDbOperationKind.fromRequest(shaped.req),
          target = target,
          dimension = dimension,
          reason = reason
        ),
        metric = metricForThrottleShaped(
          shaped,
          reason,
          adaptiveAvailableRequestUnits,
          burstAvailableRequestUnits,
          topologyPartitionCount,
          target,
          dimension,
          throughputDemand,
          resolvedPartitionFootprint,
          indexMaintenanceSummary
        )
      )

    def hotPartitionReason(dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      (dimension, config.admissionTarget) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Write, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexWriteHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          DynamoDbThrottleReason.TableWriteHotPartitionThroughputExceeded

    def wholeResourceReason(dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      val isProvisioned = config.billingMode.isInstanceOf[DynamoDbTable.BillingMode.Provisioned]
      (dimension, config.admissionTarget) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
          else DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.GlobalSecondaryIndexReadProvisionedThroughputExceeded
          else DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Write, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.GlobalSecondaryIndexWriteProvisionedThroughputExceeded
          else DynamoDbThrottleReason.GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          if isProvisioned then DynamoDbThrottleReason.TableWriteProvisionedThroughputExceeded
          else DynamoDbThrottleReason.TableWriteMaxOnDemandThroughputExceeded

    def hotPartitionReasonFor(target: DynamoDbTarget, dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      (dimension, target) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Write, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexWriteHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          DynamoDbThrottleReason.TableWriteHotPartitionThroughputExceeded

    def wholeResourceReasonFor(target: DynamoDbTarget, dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      val isProvisioned = config.billingMode.isInstanceOf[DynamoDbTable.BillingMode.Provisioned]
      (dimension, target) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
          else DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.GlobalSecondaryIndexReadProvisionedThroughputExceeded
          else DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Write, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          if isProvisioned then DynamoDbThrottleReason.GlobalSecondaryIndexWriteProvisionedThroughputExceeded
          else DynamoDbThrottleReason.GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          if isProvisioned then DynamoDbThrottleReason.TableWriteProvisionedThroughputExceeded
          else DynamoDbThrottleReason.TableWriteMaxOnDemandThroughputExceeded

    def partitionOverages(
                           currentlyUsedByPartition: Map[Int, BigDecimal],
                           resolvedPartitionFootprint: ResolvedPartitionFootprint,
                           partitionLimit: BigDecimal
                         ): Map[Int, BigDecimal] =
      resolvedPartitionFootprint.partitionDemandById.collect {
        case (partitionId, demand)
            if currentlyUsedByPartition.getOrElse(partitionId, BigDecimal(0)) + demand > partitionLimit =>
          partitionId -> ((currentlyUsedByPartition.getOrElse(partitionId, BigDecimal(0)) + demand) - partitionLimit)
      }

    def wholeResourceOverage(
                              currentlyUsed: BigDecimal,
                              throughputDemand: BigDecimal,
                              limit: BigDecimal
                            ): BigDecimal =
      (currentlyUsed + throughputDemand - limit).max(BigDecimal(0))

    def admissionModeFor(
                         adaptiveConsumedRequestUnits: BigDecimal,
                         burstConsumedRequestUnits: BigDecimal
                       ): AdmissionMode =
      if adaptiveConsumedRequestUnits > 0 && burstConsumedRequestUnits > 0 then
        AdmissionMode.AdaptiveAndBurstBacked
      else if adaptiveConsumedRequestUnits > 0 then
        AdmissionMode.AdaptiveBacked
      else if burstConsumedRequestUnits > 0 then
        AdmissionMode.BurstBacked
      else
        AdmissionMode.Normal

    def adaptiveReliefFor(
                           currentlyUsedByPartition: Map[Int, BigDecimal],
                           resolvedPartitionFootprint: ResolvedPartitionFootprint,
                           baselineLimit: Option[BigDecimal],
                           adaptiveLimit: Option[BigDecimal]
                         ): AdaptiveRelief =
      baselineLimit match
        case Some(baseline) =>
          val hotPartitionOverages =
            partitionOverages(currentlyUsedByPartition, resolvedPartitionFootprint, baseline)
          val totalHotOverage = hotPartitionOverages.values.sum

          adaptiveLimit match
            case Some(adaptiveMax) if hotPartitionOverages.nonEmpty =>
              val projectedUsageByPartition =
                (0 until resolvedPartitionFootprint.totalPartitionCount).map { partitionId =>
                  partitionId ->
                    (currentlyUsedByPartition.getOrElse(partitionId, BigDecimal(0)) +
                      resolvedPartitionFootprint.partitionDemandById.getOrElse(partitionId, BigDecimal(0)))
                }.toMap

              val totalCoolPartitionHeadroom =
                projectedUsageByPartition.iterator.collect {
                  case (partitionId, projectedUsage) if !hotPartitionOverages.contains(partitionId) =>
                    (baseline - projectedUsage).max(BigDecimal(0))
                }.foldLeft(BigDecimal(0))(_ + _)

              val totalAdaptiveCeilingRoomOnHotPartitions =
                hotPartitionOverages.keysIterator.map { partitionId =>
                  (adaptiveMax - currentlyUsedByPartition.getOrElse(partitionId, BigDecimal(0))).max(BigDecimal(0))
                }.foldLeft(BigDecimal(0))(_ + _)

              val adaptiveAvailable =
                totalHotOverage.min(totalCoolPartitionHeadroom).min(totalAdaptiveCeilingRoomOnHotPartitions)

              AdaptiveRelief(
                consumedRequestUnits = adaptiveAvailable,
                availableRequestUnits = adaptiveAvailable,
                remainingHotPartitionOverage = (totalHotOverage - adaptiveAvailable).max(BigDecimal(0))
              )
            case _ =>
              AdaptiveRelief(
                consumedRequestUnits = BigDecimal(0),
                availableRequestUnits = BigDecimal(0),
                remainingHotPartitionOverage = totalHotOverage
              )
        case None =>
          AdaptiveRelief(BigDecimal(0), BigDecimal(0), BigDecimal(0))

    def evaluateWriteScope(
                            target: DynamoDbTarget,
                            throughputDemand: BigDecimal,
                            resolvedPartitionFootprint: ResolvedPartitionFootprint,
                            usageState: PerTickUsageState,
                            burstState: BurstState,
                            maxWriteRequestUnitsPerSecond: Option[BigDecimal],
                            maxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal],
                            adaptiveMaxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal]
                          ): WriteScopeEvaluation =
      val adaptiveRelief =
        adaptiveReliefFor(
          currentlyUsedByPartition = usageState.writeUnitsByPartition,
          resolvedPartitionFootprint = resolvedPartitionFootprint,
          baselineLimit = maxWriteRequestUnitsPerSecondPerPartition,
          adaptiveLimit = adaptiveMaxWriteRequestUnitsPerSecondPerPartition
        )

      val wholeOverage =
        maxWriteRequestUnitsPerSecond.map { limit =>
          wholeResourceOverage(usageState.writeUnits, throughputDemand, limit)
        }.getOrElse(BigDecimal(0))

      val requiredBurst = adaptiveRelief.remainingHotPartitionOverage.max(wholeOverage)
      val burstAvailable = burstState.availableFor(DynamoDbThroughputDimension.Write)

      WriteScopeEvaluation(
        target = target,
        throughputDemand = throughputDemand,
        resolvedPartitionFootprint = resolvedPartitionFootprint,
        adaptiveConsumedRequestUnits = adaptiveRelief.consumedRequestUnits,
        adaptiveAvailableRequestUnits = adaptiveRelief.availableRequestUnits,
        requiredBurst = requiredBurst,
        burstAvailable = burstAvailable,
        blockingReason =
          if requiredBurst > 0 && burstAvailable < requiredBurst then
            Some(
              if adaptiveRelief.remainingHotPartitionOverage > 0 then hotPartitionReasonFor(target, DynamoDbThroughputDimension.Write)
              else wholeResourceReasonFor(target, DynamoDbThroughputDimension.Write)
            )
          else None
      )

    def maybeGrowTopology(
                           eventTime: SimTime,
                           usageState: PerTickUsageState,
                           topologyState: DynamicTopologyState,
                           stateModel: TableState
                         ): (DynamicTopologyState, Vector[AdmissionMetricEvent.TopologyChanged]) =
      config.dynamicPartitionTopologyConfig match
        case None => (topologyState, Vector.empty)
        case Some(dynamicConfig) =>
          val currentCount = topologyState.snapshot.partitionCount
          val maxCount = dynamicConfig.maxPartitionCount.getOrElse(Int.MaxValue)

          if currentCount >= maxCount then
            val nextHeat =
              DynamicTopologyHeatState(
                consecutiveReadHotTicks = nextHotTickCount(
                  usageState.readUnitsByPartition.values.maxOption.getOrElse(BigDecimal(0)),
                  dynamicConfig.readHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                  topologyState.heatState.consecutiveReadHotTicks
                ),
                consecutiveWriteHotTicks = nextHotTickCount(
                  usageState.writeUnitsByPartition.values.maxOption.getOrElse(BigDecimal(0)),
                  dynamicConfig.writeHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                  topologyState.heatState.consecutiveWriteHotTicks
                )
              )
            (topologyState.copy(heatState = nextHeat), Vector.empty)
          else
            val nextHeat =
              DynamicTopologyHeatState(
                consecutiveReadHotTicks = nextHotTickCount(
                  usageState.readUnitsByPartition.values.maxOption.getOrElse(BigDecimal(0)),
                  dynamicConfig.readHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                  topologyState.heatState.consecutiveReadHotTicks
                ),
                consecutiveWriteHotTicks = nextHotTickCount(
                  usageState.writeUnitsByPartition.values.maxOption.getOrElse(BigDecimal(0)),
                  dynamicConfig.writeHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                  topologyState.heatState.consecutiveWriteHotTicks
                )
              )

            val growthReason =
              if nextHeat.consecutiveReadHotTicks >= dynamicConfig.heatSplitSustainWindowSeconds ||
                nextHeat.consecutiveWriteHotTicks >= dynamicConfig.heatSplitSustainWindowSeconds
              then Some(TopologyChangeReason.SustainedHeat)
              else if dynamicConfig.readThroughputGrowthSplitThresholdRequestUnitsPerSecond.exists(threshold =>
                  usageState.readUnits > BigDecimal(currentCount) * threshold
                ) || dynamicConfig.writeThroughputGrowthSplitThresholdRequestUnitsPerSecond.exists(threshold =>
                  usageState.writeUnits > BigDecimal(currentCount) * threshold
                )
              then Some(TopologyChangeReason.ThroughputGrowth)
              else if dynamicConfig.storageSplitThresholdBytes.exists(threshold =>
                  BigDecimal(stateModel.totalItemBytes) > BigDecimal(currentCount) * BigDecimal(threshold)
                )
              then Some(TopologyChangeReason.StorageGrowth)
              else None

            growthReason match
              case Some(reason) =>
                val newCount = (currentCount + 1).min(maxCount)
                if newCount == currentCount then
                  (topologyState.copy(heatState = nextHeat), Vector.empty)
                else
                  (
                    topologyState.copy(
                      snapshot = topologyState.snapshot.copy(
                        partitionCount = newCount,
                        version = topologyState.snapshot.version + 1L,
                        effectiveFromTick = eventTime.ticks
                      ),
                      heatState =
                        if reason == TopologyChangeReason.SustainedHeat then DynamicTopologyHeatState()
                        else nextHeat
                    ),
                    Vector(topologyMetricForShaped(eventTime, currentCount, newCount, reason))
                  )
              case None =>
                (topologyState.copy(heatState = nextHeat), Vector.empty)

    def nextHotTickCount(
                          observedPeakByPartition: BigDecimal,
                          trigger: Option[BigDecimal],
                          previousCount: Int
                        ): Int =
      trigger match
        case Some(threshold) if observedPeakByPartition >= threshold => previousCount + 1
        case _ => 0

    def evaluateReadAdmissionShaped(
                                     shaped: ShapedRequest,
                                     usageState: PerTickUsageState,
                                     burstState: BurstState,
                                     admittedSample: => AdmittedRequestSample
                                   ): AdmissionDecision =
      val adaptiveRelief =
        adaptiveReliefFor(
          currentlyUsedByPartition = usageState.readUnitsByPartition,
          resolvedPartitionFootprint = shaped.resolvedPartitionFootprint,
          baselineLimit = config.maxReadRequestUnitsPerSecondPerPartition,
          adaptiveLimit = config.adaptiveMaxReadRequestUnitsPerSecondPerPartition
        )
      val wholeOverage =
        config.maxReadRequestUnitsPerSecond.map { limit =>
          wholeResourceOverage(usageState.readUnits, shaped.throughputDemand, limit)
        }.getOrElse(BigDecimal(0))
      val requiredBurst = adaptiveRelief.remainingHotPartitionOverage.max(wholeOverage)
      val burstAvailable = burstState.availableFor(DynamoDbThroughputDimension.Read)

      if requiredBurst > 0 && burstAvailable < requiredBurst then
        throttledShaped(
          shaped = shaped,
          target = config.admissionTarget,
          dimension = DynamoDbThroughputDimension.Read,
          throughputDemand = shaped.throughputDemand,
          reason =
            if adaptiveRelief.remainingHotPartitionOverage > 0 then hotPartitionReason(DynamoDbThroughputDimension.Read)
            else wholeResourceReason(DynamoDbThroughputDimension.Read),
          adaptiveAvailableRequestUnits = adaptiveRelief.availableRequestUnits,
          burstAvailableRequestUnits = burstAvailable,
          topologyPartitionCount = shaped.resolvedPartitionFootprint.totalPartitionCount,
          resolvedPartitionFootprint = shaped.resolvedPartitionFootprint
        )
      else
        val remainingBurst = (burstAvailable - requiredBurst).max(BigDecimal(0))
        Admitted(
          request = shaped.req,
          sample = admittedSample,
          metric = metricForAdmissionShaped(
            shaped = shaped,
            admissionMode = admissionModeFor(adaptiveRelief.consumedRequestUnits, requiredBurst),
            adaptiveConsumedRequestUnits = adaptiveRelief.consumedRequestUnits,
            adaptiveAvailableRequestUnits = adaptiveRelief.availableRequestUnits,
            burstConsumedRequestUnits = requiredBurst,
            burstRemainingRequestUnits = remainingBurst,
            topologyPartitionCount = shaped.resolvedPartitionFootprint.totalPartitionCount
          )
        )

    def evaluateWriteAdmissionShaped(
                                      shaped: ShapedWriteRequest,
                                      usageState: PerTickUsageState,
                                      burstState: BurstState,
                                      gsiUsageStates: Map[String, PerTickUsageState],
                                      gsiBurstStates: Map[String, BurstState],
                                      admittedSample: => AdmittedRequestSample
                                    ): AdmissionDecision =
      val indexMaintenanceSummary = shaped.indexMaintenancePlan.map(_.summary)
      val baseEvaluation =
        evaluateWriteScope(
          target = config.admissionTarget,
          throughputDemand = shaped.throughputDemand,
          resolvedPartitionFootprint = shaped.resolvedPartitionFootprint,
          usageState = usageState,
          burstState = burstState,
          maxWriteRequestUnitsPerSecond = config.maxWriteRequestUnitsPerSecond,
          maxWriteRequestUnitsPerSecondPerPartition = config.maxWriteRequestUnitsPerSecondPerPartition,
          adaptiveMaxWriteRequestUnitsPerSecondPerPartition = config.adaptiveMaxWriteRequestUnitsPerSecondPerPartition
        )
      val gsiEvaluations =
        config.gsiWriteScopes.map { scope =>
          val maintenancePlan =
            shaped.indexMaintenancePlan.collectFirst {
              case plan: IndexMaintenancePlan if plan.target == scope.target => plan
            }.getOrElse(
              throw new IllegalStateException(s"Missing index maintenance plan for GSI '${scope.target.indexName}'")
            )
          scope.target.indexName ->
            evaluateWriteScope(
              target = scope.target,
              throughputDemand = maintenancePlan.throughputDemand,
              resolvedPartitionFootprint = maintenancePlan.resolvedPartitionFootprint,
              usageState = gsiUsageStates.getOrElse(scope.target.indexName, PerTickUsageState()),
              burstState = gsiBurstStates.getOrElse(
                scope.target.indexName,
                BurstState(
                  readBurst = BurstReservoir.from(None, None, None),
                  writeBurst = BurstReservoir.from(
                    scope.maxWriteRequestUnitsPerSecond,
                    scope.burstRetentionWindowSeconds,
                    scope.initialWriteBurstRequestUnits
                  )
                )
              ),
              maxWriteRequestUnitsPerSecond = scope.maxWriteRequestUnitsPerSecond,
              maxWriteRequestUnitsPerSecondPerPartition = scope.maxWriteRequestUnitsPerSecondPerPartition,
              adaptiveMaxWriteRequestUnitsPerSecondPerPartition = scope.adaptiveMaxWriteRequestUnitsPerSecondPerPartition
            )
        }.toMap
      val failingGsi = config.gsiWriteScopes.collectFirst {
        case scope if gsiEvaluations(scope.target.indexName).blockingReason.nonEmpty =>
          gsiEvaluations(scope.target.indexName)
      }
      failingGsi match
        case Some(failure) =>
          throttledShaped(
            shaped = shaped,
            target = failure.target,
            dimension = DynamoDbThroughputDimension.Write,
            throughputDemand = failure.throughputDemand,
            reason = failure.blockingReason.get,
            adaptiveAvailableRequestUnits = failure.adaptiveAvailableRequestUnits,
            burstAvailableRequestUnits = failure.burstAvailable,
            topologyPartitionCount = failure.resolvedPartitionFootprint.totalPartitionCount,
            resolvedPartitionFootprint = failure.resolvedPartitionFootprint,
            indexMaintenanceSummary = indexMaintenanceSummary
          )
        case None if baseEvaluation.blockingReason.nonEmpty =>
          throttledShaped(
            shaped = shaped,
            target = baseEvaluation.target,
            dimension = DynamoDbThroughputDimension.Write,
            throughputDemand = baseEvaluation.throughputDemand,
            reason = baseEvaluation.blockingReason.get,
            adaptiveAvailableRequestUnits = baseEvaluation.adaptiveAvailableRequestUnits,
            burstAvailableRequestUnits = baseEvaluation.burstAvailable,
            topologyPartitionCount = baseEvaluation.resolvedPartitionFootprint.totalPartitionCount,
            resolvedPartitionFootprint = baseEvaluation.resolvedPartitionFootprint,
            indexMaintenanceSummary = indexMaintenanceSummary
          )
        case None =>
          Admitted(
            request = shaped.req,
            sample = admittedSample,
            metric = metricForAdmissionShaped(
              shaped = shaped,
              admissionMode = admissionModeFor(baseEvaluation.adaptiveConsumedRequestUnits, baseEvaluation.requiredBurst),
              adaptiveConsumedRequestUnits = baseEvaluation.adaptiveConsumedRequestUnits,
              adaptiveAvailableRequestUnits = baseEvaluation.adaptiveAvailableRequestUnits,
              burstConsumedRequestUnits = baseEvaluation.requiredBurst,
              burstRemainingRequestUnits = (baseEvaluation.burstAvailable - baseEvaluation.requiredBurst).max(BigDecimal(0)),
              topologyPartitionCount = shaped.resolvedPartitionFootprint.totalPartitionCount,
              indexMaintenanceSummary = indexMaintenanceSummary
            ),
            gsiWriteAdmissions =
              config.gsiWriteScopes.map { scope =>
                val evaluation = gsiEvaluations(scope.target.indexName)
                GsiWriteAdmission(
                  indexName = scope.target.indexName,
                  throughputDemand = evaluation.throughputDemand,
                  resolvedPartitionFootprint = evaluation.resolvedPartitionFootprint,
                  burstConsumedRequestUnits = evaluation.requiredBurst
                )
              }
          )

    def decideFromShaped(
                          shaped: ShapedRequest,
                          usageState: PerTickUsageState,
                          burstState: BurstState,
                          gsiUsageStates: Map[String, PerTickUsageState],
                          gsiBurstStates: Map[String, BurstState]
                        ): AdmissionDecision =
      shaped match
        case s: ShapedGetItemRequest =>
          evaluateReadAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            admittedSample =
              AdmittedGetItemSample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                readConsistency = s.readConsistency,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint
              )
          )

        case s: ShapedQueryRequest =>
          evaluateReadAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            admittedSample =
              AdmittedQuerySample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint
              )
          )

        case s: ShapedScanRequest =>
          evaluateReadAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            admittedSample =
              AdmittedScanSample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint
              )
          )

        case s: ShapedPutItemRequest =>
          evaluateWriteAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            gsiUsageStates = gsiUsageStates,
            gsiBurstStates = gsiBurstStates,
            admittedSample =
              AdmittedPutItemSample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint,
                indexMaintenancePlan = s.indexMaintenancePlan
              )
          )

        case s: ShapedUpdateItemRequest =>
          evaluateWriteAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            gsiUsageStates = gsiUsageStates,
            gsiBurstStates = gsiBurstStates,
            admittedSample =
              AdmittedUpdateItemSample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint,
                indexMaintenancePlan = s.indexMaintenancePlan
              )
          )

        case s: ShapedDeleteItemRequest =>
          evaluateWriteAdmissionShaped(
            shaped = s,
            usageState = usageState,
            burstState = burstState,
            gsiUsageStates = gsiUsageStates,
            gsiBurstStates = gsiBurstStates,
            admittedSample =
              AdmittedDeleteItemSample(
                req = s.req,
                executionTarget = s.executionTarget,
                admissionTarget = s.admissionTarget,
                sample = s.sample,
                throughputDemand = s.throughputDemand,
                resolvedPartitionFootprint = s.resolvedPartitionFootprint,
                indexMaintenancePlan = s.indexMaintenancePlan
              )
          )

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val decisionFlow = b.add(
        Flow[TimedElement[ShapedRequest]].statefulMapConcat[TimedElement[TimedEvent]] { () =>
          var currentTick: Option[Long] = None
          var usageState = PerTickUsageState()
          var burstState =
            BurstState(
              readBurst =
                BurstReservoir.from(
                  config.maxReadRequestUnitsPerSecond,
                  config.burstRetentionWindowSeconds,
                  config.initialReadBurstRequestUnits
                ),
              writeBurst =
                BurstReservoir.from(
                  config.maxWriteRequestUnitsPerSecond,
                  config.burstRetentionWindowSeconds,
                  config.initialWriteBurstRequestUnits
                )
            )
          var gsiUsageStates =
            config.gsiWriteScopes.map(scope => scope.target.indexName -> PerTickUsageState()).toMap
          var gsiBurstStates =
            config.gsiWriteScopes.map { scope =>
              scope.target.indexName ->
                BurstState(
                  readBurst = BurstReservoir.from(None, None, None),
                  writeBurst = BurstReservoir.from(
                    scope.maxWriteRequestUnitsPerSecond,
                    scope.burstRetentionWindowSeconds,
                    scope.initialWriteBurstRequestUnits
                  )
                )
            }.toMap
          var gsiTopologyStates =
            config.gsiWriteScopes.map { scope =>
              scope.target.indexName ->
                DynamicTopologyState(
                  snapshot = PartitionTopologySnapshot(
                    partitionCount = scope.dynamicPartitionTopologyConfig.map(_.initialPartitionCount).getOrElse(config.partitionCount),
                    version = 0L,
                    effectiveFromTick = 0L
                  )
                )
            }.toMap
          var topologyState =
            DynamicTopologyState(
              snapshot =
                PartitionTopologySnapshot(
                  partitionCount =
                    config.dynamicPartitionTopologyConfig.map(_.initialPartitionCount).getOrElse(config.partitionCount),
                  version = 0L,
                  effectiveFromTick = 0L
                )
            )
          var topologyChangedOnLastAdvance = false

          def reshapeWithCurrentTopology(shaped: ShapedRequest): ShapedRequest =
            val newFootprint =
              PartitionAccessResolver.resolve(
                access = shaped.logicalPartitionAccess,
                throughputDemand = shaped.throughputDemand,
                topology = topologyState.snapshot
              )

            def newPlanFor(
                            newBaseItemBytes: Option[Long],
                            previousBaseItemBytes: Option[Long]
                          ): Vector[IndexMaintenancePlan] =
              IndexMaintenancePlanDerivation.derivePlans(
                indexMaintenanceTargets = config.indexMaintenanceTargets,
                gsiWriteScopes = config.gsiWriteScopes,
                fallbackPartitionCount = config.partitionCount,
                logicalPartitionAccess = shaped.logicalPartitionAccess,
                newBaseItemBytes = newBaseItemBytes,
                previousBaseItemBytes = previousBaseItemBytes,
                baseTopologySnapshot = topologyState.snapshot,
                gsiTopologySnapshots = gsiTopologyStates.view.mapValues(_.snapshot).toMap
              )

            shaped match
              case r: ShapedGetItemRequest => r.copy(resolvedPartitionFootprint = newFootprint)
              case r: ShapedQueryRequest => r.copy(resolvedPartitionFootprint = newFootprint)
              case r: ShapedScanRequest => r.copy(resolvedPartitionFootprint = newFootprint)
              case r: ShapedPutItemRequest =>
                r.copy(
                  resolvedPartitionFootprint = newFootprint,
                  indexMaintenancePlan = newPlanFor(Some(r.sample.writtenItemBytes), r.sample.previousItemBytes)
                )
              case r: ShapedUpdateItemRequest =>
                r.copy(
                  resolvedPartitionFootprint = newFootprint,
                  indexMaintenancePlan = newPlanFor(Some(r.sample.writtenItemBytes), r.sample.previousItemBytes)
                )
              case r: ShapedDeleteItemRequest =>
                r.copy(
                  resolvedPartitionFootprint = newFootprint,
                  indexMaintenancePlan = newPlanFor(None, r.sample.deletedItemBytes)
                )

          def advanceToShaped(eventTime: SimTime): Vector[TimedEvent] =
            topologyChangedOnLastAdvance = false
            val tick = eventTime.ticks
            if currentTick.forall(_ != tick) then
              val topologyEvents =
                if currentTick.nonEmpty then
                  burstState = burstState.replenish(usageState, config)
                  gsiBurstStates =
                    config.gsiWriteScopes.foldLeft(gsiBurstStates) { (acc, scope) =>
                      val updatedState =
                        acc.getOrElse(
                          scope.target.indexName,
                          BurstState(
                            readBurst = BurstReservoir.from(None, None, None),
                            writeBurst = BurstReservoir.from(
                              scope.maxWriteRequestUnitsPerSecond,
                              scope.burstRetentionWindowSeconds,
                              scope.initialWriteBurstRequestUnits
                            )
                          )
                        ).copy(
                          writeBurst =
                            acc.getOrElse(
                              scope.target.indexName,
                              BurstState(
                                readBurst = BurstReservoir.from(None, None, None),
                                writeBurst = BurstReservoir.from(
                                  scope.maxWriteRequestUnitsPerSecond,
                                  scope.burstRetentionWindowSeconds,
                                  scope.initialWriteBurstRequestUnits
                                )
                              )
                            ).writeBurst.replenish(
                              scope.maxWriteRequestUnitsPerSecond
                                .map(limit =>
                                  (limit - gsiUsageStates.getOrElse(scope.target.indexName, PerTickUsageState()).writeUnitsChargedToSteadyState)
                                    .max(BigDecimal(0))
                                )
                                .getOrElse(BigDecimal(0))
                            )
                        )
                      acc.updated(scope.target.indexName, updatedState)
                    }
                  val previousGsiTopologyStates = gsiTopologyStates
                  gsiTopologyStates =
                    config.gsiWriteScopes.foldLeft(gsiTopologyStates) { (acc, scope) =>
                      scope.dynamicPartitionTopologyConfig match
                        case Some(dynamicConfig) =>
                          val existing = acc.getOrElse(
                            scope.target.indexName,
                            DynamicTopologyState(
                              snapshot = PartitionTopologySnapshot(
                                partitionCount = dynamicConfig.initialPartitionCount,
                                version = 0L,
                                effectiveFromTick = 0L
                              )
                            )
                          )
                          val currentCount = existing.snapshot.partitionCount
                          val maxCount = dynamicConfig.maxPartitionCount.getOrElse(Int.MaxValue)
                          val currentUsage = gsiUsageStates.getOrElse(scope.target.indexName, PerTickUsageState())
                          val nextReadHeat = 0
                          val nextWriteHeat =
                            nextHotTickCount(
                              currentUsage.writeUnitsByPartition.values.maxOption.getOrElse(BigDecimal(0)),
                              dynamicConfig.writeHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                              existing.heatState.consecutiveWriteHotTicks
                            )
                          val growthReason =
                            if nextWriteHeat >= dynamicConfig.heatSplitSustainWindowSeconds then
                              Some(TopologyChangeReason.SustainedHeat)
                            else if dynamicConfig.writeThroughputGrowthSplitThresholdRequestUnitsPerSecond.exists(threshold =>
                                currentUsage.writeUnits > BigDecimal(currentCount) * threshold
                              ) then
                              Some(TopologyChangeReason.ThroughputGrowth)
                            else if dynamicConfig.storageSplitThresholdBytes.exists(threshold =>
                                BigDecimal(scope.stateModel.totalItemBytes) > BigDecimal(currentCount) * BigDecimal(threshold)
                              ) then
                              Some(TopologyChangeReason.StorageGrowth)
                            else None

                          growthReason match
                            case Some(reason) if currentCount < maxCount =>
                              acc.updated(
                                scope.target.indexName,
                                existing.copy(
                                  snapshot = existing.snapshot.copy(
                                    partitionCount = currentCount + 1,
                                    version = existing.snapshot.version + 1L,
                                    effectiveFromTick = eventTime.ticks
                                  ),
                                  heatState = DynamicTopologyHeatState(consecutiveReadHotTicks = nextReadHeat, consecutiveWriteHotTicks = 0)
                                )
                              )
                            case _ =>
                              acc.updated(
                                scope.target.indexName,
                                existing.copy(
                                  heatState = DynamicTopologyHeatState(
                                    consecutiveReadHotTicks = nextReadHeat,
                                    consecutiveWriteHotTicks = nextWriteHeat
                                  )
                                )
                              )
                        case None => acc
                    }
                  val gsiTopologyEvents =
                    config.gsiWriteScopes.flatMap { scope =>
                      val previous = previousGsiTopologyStates.get(scope.target.indexName).map(_.snapshot.partitionCount)
                      val current = gsiTopologyStates.get(scope.target.indexName).map(_.snapshot.partitionCount)
                      (previous, current) match
                        case (Some(p), Some(c)) if c > p =>
                          Some(
                            AdmissionMetricEvent.TopologyChanged(
                              eventTime = eventTime,
                              usecase = "topology-change",
                              scope = TopologyScope.GlobalSecondaryIndex(scope.target.indexName),
                              reason = TopologyChangeReason.ThroughputGrowth,
                              previousPartitionCount = p,
                              newPartitionCount = c
                            )
                          )
                        case _ => None
                    }.toVector
                  val (nextTopologyState, events) =
                    maybeGrowTopology(
                      eventTime = eventTime,
                      usageState = usageState,
                      topologyState = topologyState,
                      stateModel = config.stateModel
                    )
                  topologyState = nextTopologyState
                  usageState = PerTickUsageState()
                  gsiUsageStates = config.gsiWriteScopes.map(scope => scope.target.indexName -> PerTickUsageState()).toMap

                  // Update the shared topology ref so the upstream sampling stage
                  // uses the evolved topology for the next tick's requests
                  topologyRef.baseTopology = topologyState.snapshot
                  topologyRef.gsiTopologies = gsiTopologyStates.view.mapValues(_.snapshot).toMap

                  if events.nonEmpty || gsiTopologyEvents.nonEmpty then
                    topologyChangedOnLastAdvance = true

                  events ++ gsiTopologyEvents
                else
                  Vector.empty
              currentTick = Some(tick)
              topologyEvents
            else
              Vector.empty

          {
            case t: TimedControlEvent.Tick =>
              advanceToShaped(t.eventTime) :+ t

            case t: TimedControlEvent =>
              List(t)

            case shaped: ShapedRequest =>
              val boundaryEvents = advanceToShaped(shaped.eventTime)
              val effectiveShaped =
                if topologyChangedOnLastAdvance then reshapeWithCurrentTopology(shaped) else shaped
              val decision =
                decideFromShaped(
                  effectiveShaped,
                  usageState,
                  burstState,
                  gsiUsageStates,
                  gsiBurstStates
                )
              decision match
                case admitted: Admitted =>
                  burstState = burstState.consume(
                    admitted.sample.throughputDimension,
                    admitted.metric.burstConsumedRequestUnits
                  )
                  gsiBurstStates =
                    admitted.gsiWriteAdmissions.foldLeft(gsiBurstStates) { (acc, admission) =>
                      acc.updatedWith(admission.indexName) {
                        case Some(existing) => Some(existing.consume(DynamoDbThroughputDimension.Write, admission.burstConsumedRequestUnits))
                        case None => None
                      }
                    }
                  usageState = usageState.afterAdmission(
                    admitted.sample,
                    admitted.sample.throughputDimension match
                      case DynamoDbThroughputDimension.Read => config.maxReadRequestUnitsPerSecond
                      case DynamoDbThroughputDimension.Write => config.maxWriteRequestUnitsPerSecond
                  )
                  gsiUsageStates =
                    admitted.gsiWriteAdmissions.foldLeft(gsiUsageStates) { (acc, admission) =>
                      acc.updated(
                        admission.indexName,
                        acc.getOrElse(admission.indexName, PerTickUsageState()).afterInternalWriteAdmission(
                          throughputDemand = admission.throughputDemand,
                          resolvedPartitionFootprint = admission.resolvedPartitionFootprint,
                          steadyStateLimit =
                            config.gsiWriteScopes
                              .find(_.target.indexName == admission.indexName)
                              .flatMap(_.maxWriteRequestUnitsPerSecond)
                        )
                      )
                    }
                  boundaryEvents :+ admitted
                case throttled: Throttled =>
                  boundaryEvents :+ throttled
          }
        }
      )

      val broadcast = b.add(Broadcast[TimedEvent](3))

      val admittedFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[AdmittedRequestSample]] {
          case t: TimedControlEvent => List(t)
          case _: AdmissionMetricEvent.TopologyChanged => Nil
          case Admitted(_, sample, _, _) => List(sample)
          case _: Throttled => Nil
        }
      )

      val responseFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[DynamoDBResponse]] {
          case t: TimedControlEvent => List(t)
          case _: AdmissionMetricEvent.TopologyChanged => Nil
          case Throttled(_, response, _) => List(response)
          case _: Admitted => Nil
        }
      )

      val metricFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[AdmissionMetricEvent]] {
          case t: TimedControlEvent => List(t)
          case metric: AdmissionMetricEvent.TopologyChanged => List(metric)
          case Admitted(_, _, metric, _) => List(metric)
          case Throttled(_, _, metric) => List(metric)
        }
      )

      decisionFlow.out ~> broadcast.in
      broadcast.out(0) ~> admittedFlow
      broadcast.out(1) ~> responseFlow
      broadcast.out(2) ~> metricFlow

      new FanOutShape3(
        decisionFlow.in,
        admittedFlow.out,
        responseFlow.out,
        metricFlow.out
      )
    }

  private def accumulateByPartition(
                                     current: Map[Int, BigDecimal],
                                     footprint: ResolvedPartitionFootprint
                                   ): Map[Int, BigDecimal] =
    footprint.partitionDemandById.foldLeft(current.withDefaultValue(BigDecimal(0))) { case (acc, (partitionId, demand)) =>
      acc.updated(partitionId, acc(partitionId) + demand)
    }

  private def chargedToSteadyState(
                                    throughputDemand: BigDecimal,
                                    alreadyChargedToSteadyState: BigDecimal,
                                    limit: Option[BigDecimal]
                                  ): BigDecimal =
    limit match
      case Some(steadyStateLimit) =>
        val remainingHeadroom = (steadyStateLimit - alreadyChargedToSteadyState).max(BigDecimal(0))
        throughputDemand.min(remainingHeadroom)
      case None => throughputDemand
