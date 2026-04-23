package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}
import stochastacy.sim.ticks

object TableStage1:

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
                           maxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None
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

  private sealed trait Stage1Decision extends TimedEvent:
    def request: DynamoDBRequest
    override val eventTime: SimTime = request.eventTime
    override val usecase: Any = request.usecase

  private final case class Admitted(
                                     request: DynamoDBRequest,
                                     sample: AdmittedRequestSample,
                                     metric: Stage1MetricEvent.RequestAdmitted
                                   ) extends Stage1Decision
  private final case class Throttled(
                                      request: DynamoDBRequest,
                                      response: ThrottledResponse,
                                      metric: Stage1MetricEvent.RequestThrottled
                                    ) extends Stage1Decision

  private final case class PerTickUsageState(
                                              readUnits: BigDecimal = BigDecimal(0),
                                              writeUnits: BigDecimal = BigDecimal(0),
                                              readUnitsByPartition: Map[Int, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0)),
                                              writeUnitsByPartition: Map[Int, BigDecimal] = Map.empty.withDefaultValue(BigDecimal(0))
                                            ):
    def afterAdmission(sample: AdmittedRequestSample): PerTickUsageState =
      sample.throughputDimension match
        case DynamoDbThroughputDimension.Read =>
          copy(
            readUnits = readUnits + sample.throughputDemand,
            readUnitsByPartition = accumulateByPartition(readUnitsByPartition, sample.resolvedPartitionFootprint)
          )
        case DynamoDbThroughputDimension.Write =>
          copy(
            writeUnits = writeUnits + sample.throughputDemand,
            writeUnitsByPartition = accumulateByPartition(writeUnitsByPartition, sample.resolvedPartitionFootprint)
          )

  def componentOf(
                   config: Config
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[Stage1MetricEvent]
    ],
    NotUsed
  ] =
    def samplerFor(request: DynamoDBRequest): UseCaseSampler[TableState] =
      config.useCaseBehaviors.getOrElse(
        request.usecase,
        throw new IllegalArgumentException(s"No table behavior for '${request.usecase}'")
      )

    def metricForAdmission(
                            request: DynamoDBRequest,
                            dimension: DynamoDbThroughputDimension,
                            throughputDemand: BigDecimal,
                            resolvedPartitionFootprint: ResolvedPartitionFootprint
                          ): Stage1MetricEvent.RequestAdmitted =
      Stage1MetricEvent.RequestAdmitted(
        eventTime = request.eventTime,
        usecase = request.usecase,
        operation = DynamoDbOperationKind.fromRequest(request),
        target = config.admissionTarget,
        dimension = dimension,
        throughputDemand = throughputDemand,
        resolvedPartitionFootprint = resolvedPartitionFootprint
      )

    def metricForThrottle(
                           request: DynamoDBRequest,
                           dimension: DynamoDbThroughputDimension,
                           throughputDemand: BigDecimal,
                           reason: DynamoDbThrottleReason,
                           resolvedPartitionFootprint: ResolvedPartitionFootprint
                         ): Stage1MetricEvent.RequestThrottled =
      Stage1MetricEvent.RequestThrottled(
        eventTime = request.eventTime,
        usecase = request.usecase,
        operation = DynamoDbOperationKind.fromRequest(request),
        target = config.admissionTarget,
        dimension = dimension,
        throughputDemand = throughputDemand,
        reason = reason,
        resolvedPartitionFootprint = resolvedPartitionFootprint
      )

    def throttled(
                   request: DynamoDBRequest,
                   dimension: DynamoDbThroughputDimension,
                   throughputDemand: BigDecimal,
                   reason: DynamoDbThrottleReason,
                   resolvedPartitionFootprint: ResolvedPartitionFootprint
                 ): Throttled =
      Throttled(
        request = request,
        response = ThrottledResponse(
          eventTime = request.eventTime,
          usecase = request.usecase,
          operation = DynamoDbOperationKind.fromRequest(request),
          target = config.admissionTarget,
          dimension = dimension,
          reason = reason
        ),
        metric = metricForThrottle(request, dimension, throughputDemand, reason, resolvedPartitionFootprint)
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
                          throughputDemand: BigDecimal
                        ): ResolvedPartitionFootprint =
      PartitionAccessResolver.resolve(
        access = logicalAccessFor(request, sampledOutcome),
        throughputDemand = throughputDemand,
        partitionCount = config.partitionCount
      )

    def hotPartitionReason(dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      (dimension, config.admissionTarget) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexReadHotPartitionThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          DynamoDbThrottleReason.TableWriteHotPartitionThroughputExceeded

    def wholeResourceReason(dimension: DynamoDbThroughputDimension): DynamoDbThrottleReason =
      (dimension, config.admissionTarget) match
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.Table(_)) |
             (DynamoDbThroughputDimension.Read, DynamoDbTarget.LocalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Read, DynamoDbTarget.GlobalSecondaryIndex(_, _)) =>
          DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
        case (DynamoDbThroughputDimension.Write, _) =>
          DynamoDbThrottleReason.TableWriteMaxOnDemandThroughputExceeded

    def exceedsPartitionLimit(
                               currentlyUsedByPartition: Map[Int, BigDecimal],
                               resolvedPartitionFootprint: ResolvedPartitionFootprint,
                               partitionLimit: BigDecimal
                             ): Boolean =
      resolvedPartitionFootprint.partitionDemandById.exists { case (partitionId, demand) =>
        currentlyUsedByPartition.getOrElse(partitionId, BigDecimal(0)) + demand > partitionLimit
      }

    def evaluateReadAdmission(
                               request: DynamoDBRequest,
                               throughputDemand: BigDecimal,
                               resolvedPartitionFootprint: ResolvedPartitionFootprint,
                               usageState: PerTickUsageState,
                               admittedSample: => AdmittedRequestSample
                             ): Stage1Decision =
      config.maxReadRequestUnitsPerSecondPerPartition match
        case Some(limit) if exceedsPartitionLimit(usageState.readUnitsByPartition, resolvedPartitionFootprint, limit) =>
          throttled(
            request = request,
            dimension = DynamoDbThroughputDimension.Read,
            throughputDemand = throughputDemand,
            reason = hotPartitionReason(DynamoDbThroughputDimension.Read),
            resolvedPartitionFootprint = resolvedPartitionFootprint
          )
        case _ =>
          config.maxReadRequestUnitsPerSecond match
            case Some(limit) if usageState.readUnits + throughputDemand > limit =>
              throttled(
                request = request,
                dimension = DynamoDbThroughputDimension.Read,
                throughputDemand = throughputDemand,
                reason = wholeResourceReason(DynamoDbThroughputDimension.Read),
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
            case _ =>
              Admitted(
                request = request,
                sample = admittedSample,
                metric = metricForAdmission(
                  request = request,
                  dimension = DynamoDbThroughputDimension.Read,
                  throughputDemand = throughputDemand,
                  resolvedPartitionFootprint = resolvedPartitionFootprint
                )
              )

    def evaluateWriteAdmission(
                                request: DynamoDBRequest,
                                throughputDemand: BigDecimal,
                                resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                usageState: PerTickUsageState,
                                admittedSample: => AdmittedRequestSample
                              ): Stage1Decision =
      config.maxWriteRequestUnitsPerSecondPerPartition match
        case Some(limit) if exceedsPartitionLimit(usageState.writeUnitsByPartition, resolvedPartitionFootprint, limit) =>
          throttled(
            request = request,
            dimension = DynamoDbThroughputDimension.Write,
            throughputDemand = throughputDemand,
            reason = hotPartitionReason(DynamoDbThroughputDimension.Write),
            resolvedPartitionFootprint = resolvedPartitionFootprint
          )
        case _ =>
          config.maxWriteRequestUnitsPerSecond match
            case Some(limit) if usageState.writeUnits + throughputDemand > limit =>
              throttled(
                request = request,
                dimension = DynamoDbThroughputDimension.Write,
                throughputDemand = throughputDemand,
                reason = wholeResourceReason(DynamoDbThroughputDimension.Write),
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
            case _ =>
              Admitted(
                request = request,
                sample = admittedSample,
                metric = metricForAdmission(
                  request = request,
                  dimension = DynamoDbThroughputDimension.Write,
                  throughputDemand = throughputDemand,
                  resolvedPartitionFootprint = resolvedPartitionFootprint
                )
              )

    def decide(request: DynamoDBRequest, usageState: PerTickUsageState): Stage1Decision =
      request match
        case r: GetItemRequest =>
          val sample = samplerFor(r).getItem(r, config.stateModel)
          val demand = TableThroughputMath.readCapacityUnitsFor(sample.itemBytes, config.readConsistency)
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateReadAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedGetItemSample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                readConsistency = config.readConsistency,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case r: QueryRequest =>
          val sample = samplerFor(r).query(r, config.stateModel)
          val demand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency)
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateReadAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedQuerySample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case r: ScanRequest =>
          val sample = samplerFor(r).scan(r, config.stateModel)
          val demand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency)
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateReadAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedScanSample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case r: PutItemRequest =>
          val sample = samplerFor(r).putItem(r, config.stateModel)
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes)
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateWriteAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedPutItemSample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case r: UpdateItemRequest =>
          val sample = samplerFor(r).updateItem(r, config.stateModel)
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes)
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateWriteAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedUpdateItemSample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case r: DeleteItemRequest =>
          val sample = samplerFor(r).deleteItem(r, config.stateModel)
          val demand = TableThroughputMath.writeCapacityUnitsFor(sample.deletedItemBytes.getOrElse(0L))
          val resolvedPartitionFootprint = resolveFootprint(r, sample, demand)
          evaluateWriteAdmission(
            request = r,
            throughputDemand = demand,
            resolvedPartitionFootprint = resolvedPartitionFootprint,
            usageState = usageState,
            admittedSample =
              AdmittedDeleteItemSample(
                req = r,
                executionTarget = config.executionTarget,
                admissionTarget = config.admissionTarget,
                sample = sample,
                throughputDemand = demand,
                resolvedPartitionFootprint = resolvedPartitionFootprint
              )
          )

        case _: PartiQLQueryRequest =>
          throw new UnsupportedOperationException("PartiQL query execution is not yet supported")

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val decisionFlow = b.add(
        Flow[TimedElement[DynamoDBRequest]].statefulMapConcat[TimedElement[Stage1Decision]] { () =>
          var currentTick: Option[Long] = None
          var usageState = PerTickUsageState()

          def advanceTo(tick: Long): Unit =
            if currentTick.forall(_ != tick) then
              currentTick = Some(tick)
              usageState = PerTickUsageState()

          {
            case t: TimedControlEvent.Tick =>
              advanceTo(t.eventTime.ticks)
              List(t)

            case t: TimedControlEvent =>
              List(t)

            case request: DynamoDBRequest =>
              advanceTo(request.eventTime.ticks)
              val decision = decide(request, usageState)
              decision match
                case admitted: Admitted =>
                  usageState = usageState.afterAdmission(admitted.sample)
                  List(admitted)
                case throttled: Throttled =>
                  List(throttled)
          }
        }
      )

      val broadcast = b.add(Broadcast[TimedEvent](3))

      val admittedFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[AdmittedRequestSample]] {
          case t: TimedControlEvent => List(t)
          case Admitted(_, sample, _) => List(sample)
          case _: Throttled => Nil
        }
      )

      val responseFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[DynamoDBResponse]] {
          case t: TimedControlEvent => List(t)
          case Throttled(_, response, _) => List(response)
          case _: Admitted => Nil
        }
      )

      val metricFlow = b.add(
        Flow[TimedEvent].mapConcat[TimedElement[Stage1MetricEvent]] {
          case t: TimedControlEvent => List(t)
          case Admitted(_, _, metric) => List(metric)
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
