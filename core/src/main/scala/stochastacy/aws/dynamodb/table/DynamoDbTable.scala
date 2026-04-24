package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*

object DynamoDbTable:

  final case class OnDemandMaxThroughput(
                                          tableMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          tableMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          globalSecondaryIndexMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
                                        ):
    require(tableMaxReadRequestUnitsPerSecond.forall(_ > 0), "tableMaxReadRequestUnitsPerSecond must be positive when defined")
    require(tableMaxWriteRequestUnitsPerSecond.forall(_ > 0), "tableMaxWriteRequestUnitsPerSecond must be positive when defined")
    require(
      globalSecondaryIndexMaxReadRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexMaxReadRequestUnitsPerSecond values must be positive"
    )

  final case class HotPartitionModel(
                                      tablePartitionCount: Int,
                                      tablePerPartitionMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                      tablePerPartitionMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                      globalSecondaryIndexPartitionCounts: Map[String, Int] = Map.empty,
                                      globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
                                    ):
    require(tablePartitionCount > 0, s"tablePartitionCount must be positive, got $tablePartitionCount")
    require(
      tablePerPartitionMaxReadRequestUnitsPerSecond.forall(_ > 0),
      "tablePerPartitionMaxReadRequestUnitsPerSecond must be positive when defined"
    )
    require(
      tablePerPartitionMaxWriteRequestUnitsPerSecond.forall(_ > 0),
      "tablePerPartitionMaxWriteRequestUnitsPerSecond must be positive when defined"
    )
    require(
      globalSecondaryIndexPartitionCounts.values.forall(_ > 0),
      "globalSecondaryIndexPartitionCounts values must be positive"
    )
    require(
      globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond values must be positive"
    )

  final case class BurstCapacityModel(
                                       enabled: Boolean = true,
                                       retentionWindowSeconds: Int = 300,
                                       initialTableReadBurstRequestUnits: Option[BigDecimal] = None,
                                       initialTableWriteBurstRequestUnits: Option[BigDecimal] = None,
                                       initialGlobalSecondaryIndexReadBurstRequestUnits: Map[String, BigDecimal] = Map.empty
                                     ):
    require(retentionWindowSeconds > 0, s"retentionWindowSeconds must be positive, got $retentionWindowSeconds")
    require(
      initialTableReadBurstRequestUnits.forall(_ >= 0),
      "initialTableReadBurstRequestUnits must be non-negative when defined"
    )
    require(
      initialTableWriteBurstRequestUnits.forall(_ >= 0),
      "initialTableWriteBurstRequestUnits must be non-negative when defined"
    )
    require(
      initialGlobalSecondaryIndexReadBurstRequestUnits.values.forall(_ >= 0),
      "initialGlobalSecondaryIndexReadBurstRequestUnits values must be non-negative"
    )

  final case class AdaptiveCapacityModel(
                                          tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
                                        ):
    require(
      tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond.forall(_ > 0),
      "tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond must be positive when defined"
    )
    require(
      tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.forall(_ > 0),
      "tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond must be positive when defined"
    )
    require(
      globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond values must be positive"
    )

  final case class DynamicPartitionTopologyModel(
                                                  enabled: Boolean = true,
                                                  tableInitialPartitionCount: Int,
                                                  globalSecondaryIndexInitialPartitionCounts: Map[String, Int] = Map.empty,
                                                  tableStorageSplitThresholdBytes: Option[Long] = None,
                                                  globalSecondaryIndexStorageSplitThresholdBytes: Map[String, Long] = Map.empty,
                                                  tableThroughputGrowthSplitThresholdRequestUnitsPerSecond: Option[BigDecimal] = None,
                                                  tableWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond: Option[BigDecimal] = None,
                                                  globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty,
                                                  heatSplitSustainWindowSeconds: Int = 1,
                                                  tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                  tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                  globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition: Map[String, BigDecimal] = Map.empty,
                                                  maxTablePartitionCount: Option[Int] = None,
                                                  maxGlobalSecondaryIndexPartitionCounts: Map[String, Int] = Map.empty
                                                ):
    require(tableInitialPartitionCount > 0, s"tableInitialPartitionCount must be positive, got $tableInitialPartitionCount")
    require(
      globalSecondaryIndexInitialPartitionCounts.values.forall(_ > 0),
      "globalSecondaryIndexInitialPartitionCounts values must be positive"
    )
    require(tableStorageSplitThresholdBytes.forall(_ > 0L), "tableStorageSplitThresholdBytes must be positive when defined")
    require(
      globalSecondaryIndexStorageSplitThresholdBytes.values.forall(_ > 0L),
      "globalSecondaryIndexStorageSplitThresholdBytes values must be positive"
    )
    require(
      tableThroughputGrowthSplitThresholdRequestUnitsPerSecond.forall(_ > 0),
      "tableThroughputGrowthSplitThresholdRequestUnitsPerSecond must be positive when defined"
    )
    require(
      tableWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond.forall(_ > 0),
      "tableWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond must be positive when defined"
    )
    require(
      globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond values must be positive"
    )
    require(heatSplitSustainWindowSeconds > 0, s"heatSplitSustainWindowSeconds must be positive, got $heatSplitSustainWindowSeconds")
    require(
      tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition.forall(_ > 0),
      "tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition must be positive when defined"
    )
    require(
      globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition.values.forall(_ > 0),
      "globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition values must be positive"
    )
    require(maxTablePartitionCount.forall(_ >= tableInitialPartitionCount), "maxTablePartitionCount must be >= tableInitialPartitionCount when defined")
    require(
      maxGlobalSecondaryIndexPartitionCounts.forall { case (indexName, maxCount) =>
        maxCount >= globalSecondaryIndexInitialPartitionCounts.getOrElse(indexName, tableInitialPartitionCount)
      },
      "maxGlobalSecondaryIndexPartitionCounts values must be >= their initial partition counts"
    )

  final case class GlobalSecondaryIndexDefinition(
                                                   indexName: String,
                                                   stateModel: TableState = SummaryTableState(0L, 0L)
                                                 )

  final case class LocalSecondaryIndexDefinition(
                                                  indexName: String,
                                                  stateModel: TableState = SummaryTableState(0L, 0L)
                                                )

  final case class Config(
                           tableName: String,
                           stateModel: TableState,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                           globalSecondaryIndexes: Vector[GlobalSecondaryIndexDefinition] = Vector.empty,
                           localSecondaryIndexes: Vector[LocalSecondaryIndexDefinition] = Vector.empty,
                           onDemandMaxThroughput: OnDemandMaxThroughput = OnDemandMaxThroughput(),
                           hotPartitionModel: Option[HotPartitionModel] = None,
                           burstCapacityModel: Option[BurstCapacityModel] = None,
                           adaptiveCapacityModel: Option[AdaptiveCapacityModel] = None,
                           dynamicPartitionTopologyModel: Option[DynamicPartitionTopologyModel] = None
                         ):
    Config.validate(this)

  object Config:
    private def validate(config: Config): Unit =
      val duplicateNames =
        (config.globalSecondaryIndexes.map(_.indexName) ++ config.localSecondaryIndexes.map(_.indexName))
          .groupBy(identity)
          .collect {
            case (indexName, occurrences) if occurrences.size > 1 => indexName
          }
          .toVector
          .sorted

      require(
        duplicateNames.isEmpty,
        s"Duplicate index names configured for table '${config.tableName}': ${duplicateNames.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNames =
        config.onDemandMaxThroughput.globalSecondaryIndexMaxReadRequestUnitsPerSecond.keySet --
          config.globalSecondaryIndexes.map(_.indexName).toSet

      require(
        unknownGlobalSecondaryIndexNames.isEmpty,
        s"On-demand max-throughput config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNames.toVector.sorted.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNamesForHotPartitions =
        config.hotPartitionModel.toVector.flatMap { model =>
          (model.globalSecondaryIndexPartitionCounts.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet)
        }.distinct.sorted

      require(
        unknownGlobalSecondaryIndexNamesForHotPartitions.isEmpty,
        s"Hot-partition config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForHotPartitions.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNamesForBurst =
        config.burstCapacityModel.toVector
          .flatMap(_.initialGlobalSecondaryIndexReadBurstRequestUnits.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet)
          .distinct
          .sorted

      require(
        unknownGlobalSecondaryIndexNamesForBurst.isEmpty,
        s"Burst-capacity config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForBurst.mkString(", ")}"
      )

      config.burstCapacityModel.foreach { burst =>
        if burst.initialTableReadBurstRequestUnits.isDefined then
          require(
            config.onDemandMaxThroughput.tableMaxReadRequestUnitsPerSecond.isDefined,
            s"Burst-capacity config for table '${config.tableName}' defines initialTableReadBurstRequestUnits without tableMaxReadRequestUnitsPerSecond"
          )

        if burst.initialTableWriteBurstRequestUnits.isDefined then
          require(
            config.onDemandMaxThroughput.tableMaxWriteRequestUnitsPerSecond.isDefined,
            s"Burst-capacity config for table '${config.tableName}' defines initialTableWriteBurstRequestUnits without tableMaxWriteRequestUnitsPerSecond"
          )

        val missingThroughputForInitialGsiBurst =
          burst.initialGlobalSecondaryIndexReadBurstRequestUnits.keySet
            .filterNot(config.onDemandMaxThroughput.globalSecondaryIndexMaxReadRequestUnitsPerSecond.contains)
            .toVector
            .sorted

        require(
          missingThroughputForInitialGsiBurst.isEmpty,
          s"Burst-capacity config for table '${config.tableName}' defines initial GSI burst for indexes without GSI max throughput: ${missingThroughputForInitialGsiBurst.mkString(", ")}"
        )
      }

      val unknownGlobalSecondaryIndexNamesForAdaptive =
        config.adaptiveCapacityModel.toVector
          .flatMap(_.globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet)
          .distinct
          .sorted

      require(
        unknownGlobalSecondaryIndexNamesForAdaptive.isEmpty,
        s"Adaptive-capacity config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForAdaptive.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNamesForDynamicTopology =
        config.dynamicPartitionTopologyModel.toVector.flatMap { model =>
          (model.globalSecondaryIndexInitialPartitionCounts.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexStorageSplitThresholdBytes.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.maxGlobalSecondaryIndexPartitionCounts.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet)
        }.distinct.sorted

      require(
        unknownGlobalSecondaryIndexNamesForDynamicTopology.isEmpty,
        s"Dynamic partition-topology config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForDynamicTopology.mkString(", ")}"
      )

      config.adaptiveCapacityModel.foreach { adaptive =>
        require(
          config.hotPartitionModel.flatMap(_.tablePerPartitionMaxReadRequestUnitsPerSecond).isDefined ||
            adaptive.tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond.isEmpty,
          s"Adaptive-capacity config for table '${config.tableName}' defines tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond without a table read hot-partition baseline"
        )
        require(
          config.hotPartitionModel.flatMap(_.tablePerPartitionMaxWriteRequestUnitsPerSecond).isDefined ||
            adaptive.tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.isEmpty,
          s"Adaptive-capacity config for table '${config.tableName}' defines tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond without a table write hot-partition baseline"
        )

        adaptive.tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond.foreach { adaptiveMax =>
          val baseline = config.hotPartitionModel.flatMap(_.tablePerPartitionMaxReadRequestUnitsPerSecond).get
          require(
            adaptiveMax >= baseline,
            s"Adaptive-capacity config for table '${config.tableName}' requires table read adaptive max ($adaptiveMax) to be >= the table read hot-partition baseline ($baseline)"
          )
        }

        adaptive.tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.foreach { adaptiveMax =>
          val baseline = config.hotPartitionModel.flatMap(_.tablePerPartitionMaxWriteRequestUnitsPerSecond).get
          require(
            adaptiveMax >= baseline,
            s"Adaptive-capacity config for table '${config.tableName}' requires table write adaptive max ($adaptiveMax) to be >= the table write hot-partition baseline ($baseline)"
          )
        }

        val gsiBaselines =
          config.hotPartitionModel.toVector.flatMap(_.globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond).toMap

        adaptive.globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond.foreach { case (indexName, adaptiveMax) =>
          val baseline =
            gsiBaselines.getOrElse(
              indexName,
              throw new IllegalArgumentException(
                s"Adaptive-capacity config for table '${config.tableName}' defines GSI adaptive max for '$indexName' without a GSI read hot-partition baseline"
              )
            )
          require(
            adaptiveMax >= baseline,
            s"Adaptive-capacity config for table '${config.tableName}' requires GSI read adaptive max for '$indexName' ($adaptiveMax) to be >= the GSI read hot-partition baseline ($baseline)"
          )
        }
      }

  private enum RouteBranch:
    case BaseTable
    case GlobalSecondaryIndex(indexName: String)
    case LocalSecondaryIndex(indexName: String)

  private sealed trait InternalIndexRuntime:
    def indexName: String
    def stateModel: TableState
    def target: DynamoDbTarget

  private object InternalIndexRuntime:
    final case class GlobalSecondaryIndex(
                                           indexName: String,
                                           stateModel: TableState,
                                           target: DynamoDbTarget.GlobalSecondaryIndex
                                         ) extends InternalIndexRuntime

    final case class LocalSecondaryIndex(
                                          indexName: String,
                                          stateModel: TableState,
                                          target: DynamoDbTarget.LocalSecondaryIndex
                                        ) extends InternalIndexRuntime

  private def routeFor(config: Config, request: DynamoDBRequest): RouteBranch =
    request match
      case _: GetItemRequest | _: PutItemRequest | _: UpdateItemRequest | _: DeleteItemRequest | _: PartiQLQueryRequest =>
        RouteBranch.BaseTable

      case QueryRequest(_, _, target, _) => routeForReadTarget(config, target)
      case ScanRequest(_, _, target, _) => routeForReadTarget(config, target)

  private def validateRequest(config: Config, request: DynamoDBRequest): Unit =
    request match
      case queryRequest: QueryRequest =>
        routeForReadTarget(config, queryRequest.target)
        validateReadConsistency(queryRequest.target, queryRequest.readConsistency, "Query")

      case scanRequest: ScanRequest =>
        routeForReadTarget(config, scanRequest.target)
        validateReadConsistency(scanRequest.target, scanRequest.readConsistency, "Scan")

      case other =>
        routeFor(config, other)

  private def validateReadConsistency(
                                       target: DynamoDbReadTarget,
                                       consistency: ReadConsistency,
                                       operationName: String
                                     ): Unit =
    target match
      case DynamoDbReadTarget.GlobalSecondaryIndex(_, indexName)
          if consistency == ReadConsistency.StronglyConsistent =>
        throw new IllegalArgumentException(
          s"Strongly consistent $operationName is not supported for global secondary index '$indexName'"
        )
      case _ => ()

  private def routeForReadTarget(config: Config, target: DynamoDbReadTarget): RouteBranch =
    val globalIndexNames = config.globalSecondaryIndexes.map(_.indexName).toSet
    val localIndexNames = config.localSecondaryIndexes.map(_.indexName).toSet

    target match
      case DynamoDbReadTarget.Table(tableName) =>
        requireMatchingTableName(config, tableName)
        RouteBranch.BaseTable

      case DynamoDbReadTarget.GlobalSecondaryIndex(tableName, indexName) =>
        requireMatchingTableName(config, tableName)
        if globalIndexNames.contains(indexName) then RouteBranch.GlobalSecondaryIndex(indexName)
        else if localIndexNames.contains(indexName) then
          throw new IllegalArgumentException(
            s"Read target '$indexName' is configured as a local secondary index, not a global secondary index"
          )
        else
          throw new IllegalArgumentException(
            s"Unknown global secondary index '$indexName' for table '${config.tableName}'"
          )

      case DynamoDbReadTarget.LocalSecondaryIndex(tableName, indexName) =>
        requireMatchingTableName(config, tableName)
        if localIndexNames.contains(indexName) then RouteBranch.LocalSecondaryIndex(indexName)
        else if globalIndexNames.contains(indexName) then
          throw new IllegalArgumentException(
            s"Read target '$indexName' is configured as a global secondary index, not a local secondary index"
          )
        else
          throw new IllegalArgumentException(
            s"Unknown local secondary index '$indexName' for table '${config.tableName}'"
          )

  private def requireMatchingTableName(config: Config, targetTableName: String): Unit =
    if targetTableName != config.tableName then
      throw new IllegalArgumentException(
        s"Read target table '$targetTableName' does not match configured table '${config.tableName}'"
      )

  private def indexRuntimesFor(config: Config): Vector[InternalIndexRuntime] =
    val globalSecondaryIndexes =
      config.globalSecondaryIndexes.map { definition =>
        InternalIndexRuntime.GlobalSecondaryIndex(
          indexName = definition.indexName,
          stateModel = definition.stateModel,
          target = DynamoDbTarget.GlobalSecondaryIndex(config.tableName, definition.indexName)
        )
      }

    val localSecondaryIndexes =
      config.localSecondaryIndexes.map { definition =>
        InternalIndexRuntime.LocalSecondaryIndex(
          indexName = definition.indexName,
          stateModel = definition.stateModel,
          target = DynamoDbTarget.LocalSecondaryIndex(config.tableName, definition.indexName)
        )
      }

    globalSecondaryIndexes ++ localSecondaryIndexes

  private def branchGraph(
                           stateModel: TableState,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           executionTarget: DynamoDbTarget,
                           admissionTarget: DynamoDbTarget,
                           readConsistency: ReadConsistency,
                           maxReadRequestUnitsPerSecond: Option[BigDecimal],
                           maxWriteRequestUnitsPerSecond: Option[BigDecimal],
                           partitionCount: Int,
                           maxReadRequestUnitsPerSecondPerPartition: Option[BigDecimal],
                           maxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal],
                           adaptiveMaxReadRequestUnitsPerSecondPerPartition: Option[BigDecimal],
                           adaptiveMaxWriteRequestUnitsPerSecondPerPartition: Option[BigDecimal],
                           burstRetentionWindowSeconds: Option[Int],
                           initialReadBurstRequestUnits: Option[BigDecimal],
                           initialWriteBurstRequestUnits: Option[BigDecimal],
                           dynamicPartitionTopologyConfig: Option[TableStage1.DynamicPartitionTopologyConfig]
                         ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[TableMetricEvent]
    ],
    NotUsed
  ] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val stage1 = b.add(
        TableStage1.componentOf(
          TableStage1.Config(
            executionTarget = executionTarget,
            admissionTarget = admissionTarget,
            useCaseBehaviors = useCaseBehaviors,
            stateModel = stateModel,
            readConsistency = readConsistency,
            maxReadRequestUnitsPerSecond = maxReadRequestUnitsPerSecond,
            maxWriteRequestUnitsPerSecond = maxWriteRequestUnitsPerSecond,
            partitionCount = partitionCount,
            maxReadRequestUnitsPerSecondPerPartition = maxReadRequestUnitsPerSecondPerPartition,
            maxWriteRequestUnitsPerSecondPerPartition = maxWriteRequestUnitsPerSecondPerPartition,
            adaptiveMaxReadRequestUnitsPerSecondPerPartition = adaptiveMaxReadRequestUnitsPerSecondPerPartition,
            adaptiveMaxWriteRequestUnitsPerSecondPerPartition = adaptiveMaxWriteRequestUnitsPerSecondPerPartition,
            burstRetentionWindowSeconds = burstRetentionWindowSeconds,
            initialReadBurstRequestUnits = initialReadBurstRequestUnits,
            initialWriteBurstRequestUnits = initialWriteBurstRequestUnits,
            dynamicPartitionTopologyConfig = dynamicPartitionTopologyConfig
          )
        )
      )
      val stage4 = b.add(TableStage4.componentOfAdmitted(stateModel))
      val throttledResponseFilter = b.add(
        Flow[TimedElement[DynamoDBResponse]].collect[TimedElement[DynamoDBResponse]] {
          case response: DynamoDBResponse => response
        }
      )
      val stage1MetricFilter = b.add(
        Flow[TimedElement[Stage1MetricEvent]].collect[TimedElement[TableMetricEvent]] {
          case metric: Stage1MetricEvent => metric
        }
      )
      val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](2))
      val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](2))

      stage1.out0 ~> stage4.in
      stage1.out1 ~> throttledResponseFilter ~> responseMerge.in(0)
      stage4.out0 ~> responseMerge.in(1)
      stage1.out2 ~> stage1MetricFilter ~> metricMerge.in(0)
      stage4.out2 ~> metricMerge.in(1)

      new FanOutShape3(
        stage1.in,
        responseMerge.out,
        stage4.out1,
        metricMerge.out
      )
    }

  def componentOf(config: Config): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[TableMetricEvent]
    ],
    NotUsed
  ] =
    val baseTableGraph =
      branchGraph(
        stateModel = config.stateModel,
        useCaseBehaviors = config.useCaseBehaviors,
        executionTarget = DynamoDbTarget.Table(config.tableName),
        admissionTarget = DynamoDbTarget.Table(config.tableName),
        readConsistency = config.readConsistency,
        maxReadRequestUnitsPerSecond = config.onDemandMaxThroughput.tableMaxReadRequestUnitsPerSecond,
        maxWriteRequestUnitsPerSecond = config.onDemandMaxThroughput.tableMaxWriteRequestUnitsPerSecond,
        partitionCount = config.hotPartitionModel.map(_.tablePartitionCount).getOrElse(1),
        maxReadRequestUnitsPerSecondPerPartition =
          config.hotPartitionModel.flatMap(_.tablePerPartitionMaxReadRequestUnitsPerSecond),
        maxWriteRequestUnitsPerSecondPerPartition =
          config.hotPartitionModel.flatMap(_.tablePerPartitionMaxWriteRequestUnitsPerSecond),
        adaptiveMaxReadRequestUnitsPerSecondPerPartition =
          config.adaptiveCapacityModel.flatMap(_.tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond),
        adaptiveMaxWriteRequestUnitsPerSecondPerPartition =
          config.adaptiveCapacityModel.flatMap(_.tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond),
        burstRetentionWindowSeconds = config.burstCapacityModel.filter(_.enabled).map(_.retentionWindowSeconds),
        initialReadBurstRequestUnits = config.burstCapacityModel.flatMap(_.initialTableReadBurstRequestUnits),
        initialWriteBurstRequestUnits = config.burstCapacityModel.flatMap(_.initialTableWriteBurstRequestUnits),
        dynamicPartitionTopologyConfig =
          config.dynamicPartitionTopologyModel.filter(_.enabled).map { dynamic =>
            TableStage1.DynamicPartitionTopologyConfig(
              initialPartitionCount = dynamic.tableInitialPartitionCount,
              storageSplitThresholdBytes = dynamic.tableStorageSplitThresholdBytes,
              readThroughputGrowthSplitThresholdRequestUnitsPerSecond =
                dynamic.tableThroughputGrowthSplitThresholdRequestUnitsPerSecond,
              writeThroughputGrowthSplitThresholdRequestUnitsPerSecond =
                dynamic.tableWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond,
              heatSplitSustainWindowSeconds = dynamic.heatSplitSustainWindowSeconds,
              readHeatSplitTriggerRequestUnitsPerSecondPerPartition =
                dynamic.tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition,
              writeHeatSplitTriggerRequestUnitsPerSecondPerPartition =
                dynamic.tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition,
              maxPartitionCount = dynamic.maxTablePartitionCount
            )
          }
      )

    val globalSecondaryIndexes = config.globalSecondaryIndexes
    val localSecondaryIndexes = config.localSecondaryIndexes
    val indexRuntimes = indexRuntimesFor(config)

    if globalSecondaryIndexes.isEmpty && localSecondaryIndexes.isEmpty then
      baseTableGraph
    else
      val branchCount = 1 + globalSecondaryIndexes.size + localSecondaryIndexes.size

      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val validationFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBRequest]] {
            case request: DynamoDBRequest =>
              validateRequest(config, request)
              request

            case t: TimedControlEvent => t
          }
        )

        val requestBroadcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](branchCount))

        val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](branchCount))
        val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](branchCount + 1))
        val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](branchCount))

        val baseRequestFilter = b.add(
          Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
            case t: TimedControlEvent => t
            case request: DynamoDBRequest if routeFor(config, request) == RouteBranch.BaseTable => request
          }
        )

        val baseTable = b.add(baseTableGraph)
        val baseResponseBroadcast = b.add(Broadcast[TimedElement[DynamoDBResponse]](2))
        val indexPropagationConsumptionFlow = b.add(
          Flow[TimedElement[DynamoDBResponse]].mapConcat[TimedElement[DynamoDbConsumptionEvent]] {
            case _: TimedControlEvent => Nil

            case response: PutItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulPut(
                  response.storedItemBytes,
                  response.previousItemBytes
                )

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = TableThroughputMath.writeCapacityUnitsFor(response.storedItemBytes)
                  ),
                  DynamoDbConsumptionEvent.StorageBytesWritten(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytes = response.storedItemBytes
                  ),
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = response.storedItemBytes - response.previousItemBytes.getOrElse(0L)
                  )
                )
              }

            case response: UpdateItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulUpdate(
                  response.storedItemBytes,
                  response.previousItemBytes
                )

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = TableThroughputMath.writeCapacityUnitsFor(response.storedItemBytes)
                  ),
                  DynamoDbConsumptionEvent.StorageBytesWritten(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytes = response.storedItemBytes
                  ),
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = response.storedItemBytes - response.previousItemBytes.getOrElse(0L)
                  )
                )
              }

            case response: DeleteItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulDelete(response.deletedItemBytes)

                val deletedEvents =
                  response.deletedItemBytes.toList.map { bytes =>
                    DynamoDbConsumptionEvent.StorageBytesDeleted(
                      eventTime = response.eventTime,
                      usecase = response.usecase,
                      target = indexRuntime.target,
                      bytes = bytes
                    )
                  }

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = TableThroughputMath.writeCapacityUnitsFor(response.deletedItemBytes.getOrElse(0L))
                  )
                ) ++ deletedEvents ++ List(
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = -response.deletedItemBytes.getOrElse(0L)
                  )
                )
              }

            case _: DynamoDBResponse =>
              Nil
          }
        )

        validationFlow.out ~> requestBroadcast.in
        requestBroadcast.out(0) ~> baseRequestFilter ~> baseTable.in
        baseTable.out0 ~> baseResponseBroadcast.in
        baseResponseBroadcast.out(0) ~> responseMerge.in(0)
        baseResponseBroadcast.out(1) ~> indexPropagationConsumptionFlow ~> consumptionMerge.in(branchCount)
        baseTable.out1 ~> consumptionMerge.in(0)
        baseTable.out2 ~> metricMerge.in(0)

        var mergeInputIndex = 1

        globalSecondaryIndexes.foreach { indexDefinition =>
          val requestFilter = b.add(
            Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
              case request: DynamoDBRequest
                  if routeFor(config, request) == RouteBranch.GlobalSecondaryIndex(indexDefinition.indexName) =>
                request
            }
          )

          val indexRuntime = indexRuntimes.collectFirst {
            case gsi: InternalIndexRuntime.GlobalSecondaryIndex if gsi.indexName == indexDefinition.indexName => gsi
          }.getOrElse(
            throw new IllegalStateException(s"Missing runtime for global secondary index '${indexDefinition.indexName}'")
          )

          val queryAndScanEnabledStage = b.add(
            branchGraph(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              executionTarget = indexRuntime.target,
              admissionTarget = indexRuntime.target,
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond =
                config.onDemandMaxThroughput.globalSecondaryIndexMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName),
              maxWriteRequestUnitsPerSecond = None,
              partitionCount =
                config.hotPartitionModel.flatMap(_.globalSecondaryIndexPartitionCounts.get(indexDefinition.indexName))
                  .orElse(config.hotPartitionModel.map(_.tablePartitionCount))
                  .getOrElse(1),
              maxReadRequestUnitsPerSecondPerPartition =
                config.hotPartitionModel.flatMap(_.globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName)),
              maxWriteRequestUnitsPerSecondPerPartition = None,
              adaptiveMaxReadRequestUnitsPerSecondPerPartition =
                config.adaptiveCapacityModel.flatMap(_.globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName)),
              adaptiveMaxWriteRequestUnitsPerSecondPerPartition = None,
              burstRetentionWindowSeconds = config.burstCapacityModel.filter(_.enabled).map(_.retentionWindowSeconds),
              initialReadBurstRequestUnits =
                config.burstCapacityModel.flatMap(_.initialGlobalSecondaryIndexReadBurstRequestUnits.get(indexDefinition.indexName)),
              initialWriteBurstRequestUnits = None,
              dynamicPartitionTopologyConfig =
                config.dynamicPartitionTopologyModel.filter(_.enabled).map { dynamic =>
                  TableStage1.DynamicPartitionTopologyConfig(
                    initialPartitionCount =
                      dynamic.globalSecondaryIndexInitialPartitionCounts.getOrElse(indexDefinition.indexName, dynamic.tableInitialPartitionCount),
                    storageSplitThresholdBytes = dynamic.globalSecondaryIndexStorageSplitThresholdBytes.get(indexDefinition.indexName),
                    readThroughputGrowthSplitThresholdRequestUnitsPerSecond =
                      dynamic.globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond.get(indexDefinition.indexName),
                    writeThroughputGrowthSplitThresholdRequestUnitsPerSecond = None,
                    heatSplitSustainWindowSeconds = dynamic.heatSplitSustainWindowSeconds,
                    readHeatSplitTriggerRequestUnitsPerSecondPerPartition =
                      dynamic.globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition.get(indexDefinition.indexName),
                    writeHeatSplitTriggerRequestUnitsPerSecondPerPartition = None,
                    maxPartitionCount = dynamic.maxGlobalSecondaryIndexPartitionCounts.get(indexDefinition.indexName)
                  )
                }
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> queryAndScanEnabledStage.in
          queryAndScanEnabledStage.out0 ~> responseMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out2 ~> metricMerge.in(mergeInputIndex)

          mergeInputIndex = mergeInputIndex + 1
        }

        localSecondaryIndexes.foreach { indexDefinition =>
          val requestFilter = b.add(
            Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
              case request: DynamoDBRequest
                  if routeFor(config, request) == RouteBranch.LocalSecondaryIndex(indexDefinition.indexName) =>
                request
            }
          )

          val indexRuntime = indexRuntimes.collectFirst {
            case lsi: InternalIndexRuntime.LocalSecondaryIndex if lsi.indexName == indexDefinition.indexName => lsi
          }.getOrElse(
            throw new IllegalStateException(s"Missing runtime for local secondary index '${indexDefinition.indexName}'")
          )

          val queryAndScanEnabledStage = b.add(
            branchGraph(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              executionTarget = indexRuntime.target,
              admissionTarget = DynamoDbTarget.Table(config.tableName),
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond = config.onDemandMaxThroughput.tableMaxReadRequestUnitsPerSecond,
              maxWriteRequestUnitsPerSecond = None,
              partitionCount = config.hotPartitionModel.map(_.tablePartitionCount).getOrElse(1),
              maxReadRequestUnitsPerSecondPerPartition =
                config.hotPartitionModel.flatMap(_.tablePerPartitionMaxReadRequestUnitsPerSecond),
              maxWriteRequestUnitsPerSecondPerPartition = None,
              adaptiveMaxReadRequestUnitsPerSecondPerPartition =
                config.adaptiveCapacityModel.flatMap(_.tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond),
              adaptiveMaxWriteRequestUnitsPerSecondPerPartition = None,
              burstRetentionWindowSeconds = config.burstCapacityModel.filter(_.enabled).map(_.retentionWindowSeconds),
              initialReadBurstRequestUnits = config.burstCapacityModel.flatMap(_.initialTableReadBurstRequestUnits),
              initialWriteBurstRequestUnits = None,
              dynamicPartitionTopologyConfig =
                config.dynamicPartitionTopologyModel.filter(_.enabled).map { dynamic =>
                  TableStage1.DynamicPartitionTopologyConfig(
                    initialPartitionCount = dynamic.tableInitialPartitionCount,
                    storageSplitThresholdBytes = dynamic.tableStorageSplitThresholdBytes,
                    readThroughputGrowthSplitThresholdRequestUnitsPerSecond =
                      dynamic.tableThroughputGrowthSplitThresholdRequestUnitsPerSecond,
                    writeThroughputGrowthSplitThresholdRequestUnitsPerSecond = None,
                    heatSplitSustainWindowSeconds = dynamic.heatSplitSustainWindowSeconds,
                    readHeatSplitTriggerRequestUnitsPerSecondPerPartition =
                      dynamic.tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition,
                    writeHeatSplitTriggerRequestUnitsPerSecondPerPartition = None,
                    maxPartitionCount = dynamic.maxTablePartitionCount
                  )
                }
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> queryAndScanEnabledStage.in
          queryAndScanEnabledStage.out0 ~> responseMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out2 ~> metricMerge.in(mergeInputIndex)

          mergeInputIndex = mergeInputIndex + 1
        }

        new FanOutShape3(
          validationFlow.in,
          responseMerge.out,
          consumptionMerge.out,
          metricMerge.out
        )
      }
