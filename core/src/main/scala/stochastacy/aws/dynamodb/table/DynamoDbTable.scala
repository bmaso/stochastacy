package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink}
import org.apache.pekko.stream.{FanOutShape2, FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*
import stochastacy.sim.stream.MergeTimedEventGraph

object DynamoDbTable:

  sealed trait IndexProjection

  object IndexProjection:
    case object All extends IndexProjection
    case object KeysOnly extends IndexProjection
    final case class Include(projectedNonKeyBytesPerItem: Long) extends IndexProjection:
      require(projectedNonKeyBytesPerItem > 0L, s"projectedNonKeyBytesPerItem must be positive, got $projectedNonKeyBytesPerItem")

  final case class OnDemandMaxThroughput(
                                          tableMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          tableMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          globalSecondaryIndexMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty,
                                          globalSecondaryIndexMaxWriteRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
                                        ):
    require(tableMaxReadRequestUnitsPerSecond.forall(_ > 0), "tableMaxReadRequestUnitsPerSecond must be positive when defined")
    require(tableMaxWriteRequestUnitsPerSecond.forall(_ > 0), "tableMaxWriteRequestUnitsPerSecond must be positive when defined")
    require(
      globalSecondaryIndexMaxReadRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexMaxReadRequestUnitsPerSecond values must be positive"
    )
    require(
      globalSecondaryIndexMaxWriteRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexMaxWriteRequestUnitsPerSecond values must be positive"
    )

  sealed trait BillingMode

  object BillingMode:
    final case class OnDemand(
      maxThroughput: OnDemandMaxThroughput = OnDemandMaxThroughput()
    ) extends BillingMode

    final case class Provisioned(
      readCapacityUnits: Long,
      writeCapacityUnits: Long,
      globalSecondaryIndexReadCapacityUnits: Map[String, Long] = Map.empty,
      globalSecondaryIndexWriteCapacityUnits: Map[String, Long] = Map.empty,
      replicatedWriteCapacityUnits: Option[Long] = None
    ) extends BillingMode:
      require(readCapacityUnits > 0L, "readCapacityUnits must be positive")
      require(writeCapacityUnits > 0L, "writeCapacityUnits must be positive")
      require(
        globalSecondaryIndexReadCapacityUnits.values.forall(_ > 0L),
        "globalSecondaryIndexReadCapacityUnits values must be positive"
      )
      require(
        globalSecondaryIndexWriteCapacityUnits.values.forall(_ > 0L),
        "globalSecondaryIndexWriteCapacityUnits values must be positive"
      )
      require(
        replicatedWriteCapacityUnits.forall(_ > 0L),
        "replicatedWriteCapacityUnits must be positive when defined"
      )

  final case class HotPartitionModel(
                                      tablePartitionCount: Int,
                                      tablePerPartitionMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                      tablePerPartitionMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                      globalSecondaryIndexPartitionCounts: Map[String, Int] = Map.empty,
                                      globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty,
                                      globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
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
    require(
      globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond values must be positive"
    )

  final case class BurstCapacityModel(
                                       enabled: Boolean = true,
                                       retentionWindowSeconds: Int = 300,
                                       initialTableReadBurstRequestUnits: Option[BigDecimal] = None,
                                       initialTableWriteBurstRequestUnits: Option[BigDecimal] = None,
                                       initialGlobalSecondaryIndexReadBurstRequestUnits: Map[String, BigDecimal] = Map.empty,
                                       initialGlobalSecondaryIndexWriteBurstRequestUnits: Map[String, BigDecimal] = Map.empty
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
    require(
      initialGlobalSecondaryIndexWriteBurstRequestUnits.values.forall(_ >= 0),
      "initialGlobalSecondaryIndexWriteBurstRequestUnits values must be non-negative"
    )

  final case class AdaptiveCapacityModel(
                                          tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond: Option[BigDecimal] = None,
                                          globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty,
                                          globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty
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
    require(
      globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond values must be positive"
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
                                                  globalSecondaryIndexWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond: Map[String, BigDecimal] = Map.empty,
                                                  heatSplitSustainWindowSeconds: Int = 1,
                                                  tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                  tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition: Option[BigDecimal] = None,
                                                  globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition: Map[String, BigDecimal] = Map.empty,
                                                  globalSecondaryIndexWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition: Map[String, BigDecimal] = Map.empty,
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
    require(
      globalSecondaryIndexWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond.values.forall(_ > 0),
      "globalSecondaryIndexWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond values must be positive"
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
    require(
      globalSecondaryIndexWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition.values.forall(_ > 0),
      "globalSecondaryIndexWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition values must be positive"
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
                                                   stateModel: TableState = SummaryTableState(0L, 0L),
                                                   projection: IndexProjection = IndexProjection.All
                                                 )

  final case class LocalSecondaryIndexDefinition(
                                                  indexName: String,
                                                  stateModel: TableState = SummaryTableState(0L, 0L),
                                                  projection: IndexProjection = IndexProjection.All
                                                )

  final case class Config(
                           tableName: String,
                           stateModel: TableState,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                           globalSecondaryIndexes: Vector[GlobalSecondaryIndexDefinition] = Vector.empty,
                           localSecondaryIndexes: Vector[LocalSecondaryIndexDefinition] = Vector.empty,
                           billingMode: BillingMode = BillingMode.OnDemand(),
                           hotPartitionModel: Option[HotPartitionModel] = None,
                           burstCapacityModel: Option[BurstCapacityModel] = None,
                           adaptiveCapacityModel: Option[AdaptiveCapacityModel] = None,
                           dynamicPartitionTopologyModel: Option[DynamicPartitionTopologyModel] = None,
                           itemCollectionSizeLimitBytes: Option[Long] = None
                         ):
    Config.validate(this)

    /**
     * The effective per-collection byte limit applied at the storage stage.
     * Returns `None` when no LSIs are configured (rule does not apply). When LSIs
     * are configured and `itemCollectionSizeLimitBytes` is `None`, defaults to
     * 10 GiB to match real DynamoDB.
     */
    private[table] def effectiveItemCollectionSizeLimitBytes: Option[Long] =
      if localSecondaryIndexes.isEmpty then None
      else itemCollectionSizeLimitBytes.orElse(Some(10L * 1024L * 1024L * 1024L))

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
        config.billingMode match
          case BillingMode.OnDemand(odmt) =>
            (odmt.globalSecondaryIndexMaxReadRequestUnitsPerSecond.keySet ++
              odmt.globalSecondaryIndexMaxWriteRequestUnitsPerSecond.keySet) --
              config.globalSecondaryIndexes.map(_.indexName).toSet
          case p: BillingMode.Provisioned =>
            (p.globalSecondaryIndexReadCapacityUnits.keySet ++
              p.globalSecondaryIndexWriteCapacityUnits.keySet) --
              config.globalSecondaryIndexes.map(_.indexName).toSet

      require(
        unknownGlobalSecondaryIndexNames.isEmpty,
        s"Billing-mode config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNames.toVector.sorted.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNamesForHotPartitions =
        config.hotPartitionModel.toVector.flatMap { model =>
          (model.globalSecondaryIndexPartitionCounts.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet)
        }.distinct.sorted

      require(
        unknownGlobalSecondaryIndexNamesForHotPartitions.isEmpty,
        s"Hot-partition config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForHotPartitions.mkString(", ")}"
      )

      val unknownGlobalSecondaryIndexNamesForBurst =
        config.burstCapacityModel.toVector
          .flatMap(model =>
            (model.initialGlobalSecondaryIndexReadBurstRequestUnits.keySet ++
              model.initialGlobalSecondaryIndexWriteBurstRequestUnits.keySet) --
              config.globalSecondaryIndexes.map(_.indexName).toSet
          )
          .distinct
          .sorted

      require(
        unknownGlobalSecondaryIndexNamesForBurst.isEmpty,
        s"Burst-capacity config references unknown global secondary indexes for table '${config.tableName}': ${unknownGlobalSecondaryIndexNamesForBurst.mkString(", ")}"
      )

      config.burstCapacityModel.foreach { burst =>
        config.billingMode match
          case BillingMode.OnDemand(odmt) =>
            if burst.initialTableReadBurstRequestUnits.isDefined then
              require(
                odmt.tableMaxReadRequestUnitsPerSecond.isDefined,
                s"Burst-capacity config for table '${config.tableName}' defines initialTableReadBurstRequestUnits without tableMaxReadRequestUnitsPerSecond"
              )

            if burst.initialTableWriteBurstRequestUnits.isDefined then
              require(
                odmt.tableMaxWriteRequestUnitsPerSecond.isDefined,
                s"Burst-capacity config for table '${config.tableName}' defines initialTableWriteBurstRequestUnits without tableMaxWriteRequestUnitsPerSecond"
              )

            val missingThroughputForInitialGsiBurst =
              (burst.initialGlobalSecondaryIndexReadBurstRequestUnits.keySet
                .filterNot(odmt.globalSecondaryIndexMaxReadRequestUnitsPerSecond.contains) ++
                burst.initialGlobalSecondaryIndexWriteBurstRequestUnits.keySet
                  .filterNot(odmt.globalSecondaryIndexMaxWriteRequestUnitsPerSecond.contains))
                .toVector
                .sorted

            require(
              missingThroughputForInitialGsiBurst.isEmpty,
              s"Burst-capacity config for table '${config.tableName}' defines initial GSI burst for indexes without GSI max throughput: ${missingThroughputForInitialGsiBurst.mkString(", ")}"
            )

          case _: BillingMode.Provisioned =>
            // provisioned mode: every table and GSI always has a throughput ceiling;
            // no "missing throughput" validation needed for burst initial values
            ()
      }

      val unknownGlobalSecondaryIndexNamesForAdaptive =
        config.adaptiveCapacityModel.toVector
          .flatMap(model =>
            (model.globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond.keySet ++
              model.globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.keySet) --
              config.globalSecondaryIndexes.map(_.indexName).toSet
          )
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
            (model.globalSecondaryIndexWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
            (model.globalSecondaryIndexWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition.keySet -- config.globalSecondaryIndexes.map(_.indexName).toSet) ++
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
        val gsiWriteBaselines =
          config.hotPartitionModel.toVector.flatMap(_.globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond).toMap

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

        adaptive.globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.foreach { case (indexName, adaptiveMax) =>
          val baseline =
            gsiWriteBaselines.getOrElse(
              indexName,
              throw new IllegalArgumentException(
                s"Adaptive-capacity config for table '${config.tableName}' defines GSI adaptive write max for '$indexName' without a GSI write hot-partition baseline"
              )
            )
          require(
            adaptiveMax >= baseline,
            s"Adaptive-capacity config for table '${config.tableName}' requires GSI write adaptive max for '$indexName' ($adaptiveMax) to be >= the GSI write hot-partition baseline ($baseline)"
          )
        }
      }

      config.billingMode match
        case _: BillingMode.Provisioned =>
          require(
            config.adaptiveCapacityModel.isEmpty,
            s"Provisioned billing mode for table '${config.tableName}' does not support adaptive capacity"
          )
        case _ => ()

      config.itemCollectionSizeLimitBytes.foreach { limit =>
        require(
          limit > 0L,
          s"itemCollectionSizeLimitBytes for table '${config.tableName}' must be positive when defined, got $limit"
        )
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

      case QueryRequest(_, _, target, _, _) => routeForReadTarget(config, target)
      case ScanRequest(_, _, target, _, _) => routeForReadTarget(config, target)

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

  private def gsiWriteScopesFor(
                                 config: Config,
                                 indexRuntimes: Vector[InternalIndexRuntime]
                               ): Vector[TableAdmissionStage.GsiWriteScopeConfig] =
    config.globalSecondaryIndexes.map { definition =>
      val indexRuntime = indexRuntimes.collectFirst {
        case gsi: InternalIndexRuntime.GlobalSecondaryIndex if gsi.indexName == definition.indexName => gsi
      }.getOrElse(
        throw new IllegalStateException(s"Missing runtime for global secondary index '${definition.indexName}'")
      )

      TableAdmissionStage.GsiWriteScopeConfig(
        target = indexRuntime.target,
        stateModel = indexRuntime.stateModel,
        maxWriteRequestUnitsPerSecond =
          config.billingMode match
            case BillingMode.OnDemand(odmt) =>
              odmt.globalSecondaryIndexMaxWriteRequestUnitsPerSecond.get(definition.indexName)
            case p: BillingMode.Provisioned =>
              Some(BigDecimal(p.globalSecondaryIndexWriteCapacityUnits.getOrElse(definition.indexName, p.writeCapacityUnits))),
        maxWriteRequestUnitsPerSecondPerPartition =
          config.hotPartitionModel.flatMap(_.globalSecondaryIndexPerPartitionMaxWriteRequestUnitsPerSecond.get(definition.indexName)),
        adaptiveMaxWriteRequestUnitsPerSecondPerPartition =
          config.adaptiveCapacityModel.flatMap(_.globalSecondaryIndexPerPartitionAdaptiveMaxWriteRequestUnitsPerSecond.get(definition.indexName)),
        burstRetentionWindowSeconds = config.burstCapacityModel.filter(_.enabled).map(_.retentionWindowSeconds),
        initialWriteBurstRequestUnits =
          config.burstCapacityModel.flatMap(_.initialGlobalSecondaryIndexWriteBurstRequestUnits.get(definition.indexName)),
        dynamicPartitionTopologyConfig =
          config.dynamicPartitionTopologyModel.filter(_.enabled).map { dynamic =>
            TableAdmissionStage.DynamicPartitionTopologyConfig(
              initialPartitionCount =
                dynamic.globalSecondaryIndexInitialPartitionCounts.getOrElse(definition.indexName, dynamic.tableInitialPartitionCount),
              storageSplitThresholdBytes = dynamic.globalSecondaryIndexStorageSplitThresholdBytes.get(definition.indexName),
              readThroughputGrowthSplitThresholdRequestUnitsPerSecond = None,
              writeThroughputGrowthSplitThresholdRequestUnitsPerSecond =
                dynamic.globalSecondaryIndexWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond.get(definition.indexName),
              heatSplitSustainWindowSeconds = dynamic.heatSplitSustainWindowSeconds,
              readHeatSplitTriggerRequestUnitsPerSecondPerPartition = None,
              writeHeatSplitTriggerRequestUnitsPerSecondPerPartition =
                dynamic.globalSecondaryIndexWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition.get(definition.indexName),
              maxPartitionCount = dynamic.maxGlobalSecondaryIndexPartitionCounts.get(definition.indexName)
            )
          }
      )
    }

  private def indexMaintenanceTargetsFor(
                                          config: Config,
                                          indexRuntimes: Vector[InternalIndexRuntime]
                                        ): Vector[TableAdmissionStage.IndexMaintenanceTargetConfig] =
    val globalTargets =
      config.globalSecondaryIndexes.map { definition =>
        val indexRuntime = indexRuntimes.collectFirst {
          case gsi: InternalIndexRuntime.GlobalSecondaryIndex if gsi.indexName == definition.indexName => gsi
        }.getOrElse(
          throw new IllegalStateException(s"Missing runtime for global secondary index '${definition.indexName}'")
        )

        TableAdmissionStage.IndexMaintenanceTargetConfig(
          target = indexRuntime.target,
          projection = definition.projection
        )
      }

    val localTargets =
      config.localSecondaryIndexes.map { definition =>
        val indexRuntime = indexRuntimes.collectFirst {
          case lsi: InternalIndexRuntime.LocalSecondaryIndex if lsi.indexName == definition.indexName => lsi
        }.getOrElse(
          throw new IllegalStateException(s"Missing runtime for local secondary index '${definition.indexName}'")
        )

        TableAdmissionStage.IndexMaintenanceTargetConfig(
          target = indexRuntime.target,
          projection = definition.projection
        )
      }

    globalTargets ++ localTargets

  private def indexMaintenanceGraph(
                                     indexRuntimes: Vector[InternalIndexRuntime]
                                   ): Graph[
    FanOutShape2[
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[StorageMetricEvent]
    ],
    NotUsed
  ] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val broadcast = b.add(Broadcast[TimedElement[AdmittedRequestSample]](2))

      val consumptionFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].mapConcat[TimedElement[DynamoDbConsumptionEvent]] {
          case t: TimedControlEvent => List(t)

          case writeSample: AdmittedWriteRequestSample =>
            val isReplicated = writeSample.isInstanceOf[Replicated[?]]
            def writeCapEvent(target: DynamoDbTarget, units: BigDecimal): DynamoDbConsumptionEvent =
              if isReplicated then
                DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed(writeSample.eventTime, writeSample.usecase, target, units)
              else
                DynamoDbConsumptionEvent.WriteCapacityConsumed(writeSample.eventTime, writeSample.usecase, target, units)

            writeSample.indexMaintenancePlan.flatMap { plan =>
              val runtime =
                indexRuntimes.find(_.target == plan.target).getOrElse(
                  throw new IllegalStateException(s"Missing runtime for index maintenance target '$plan.target'")
                )

              plan.action match
                case IndexMaintenanceAction.NoOp =>
                  Nil
                case IndexMaintenanceAction.InsertEntry | IndexMaintenanceAction.ReplaceEntry =>
                  val newBytes = plan.newIndexEntryBytes.getOrElse(0L)
                  runtime.stateModel.recordSuccessfulWrite(newBytes, plan.previousIndexEntryBytes)
                  List(
                    writeCapEvent(runtime.target, plan.throughputDemand),
                    DynamoDbConsumptionEvent.StorageBytesWritten(
                      eventTime = writeSample.eventTime,
                      usecase = writeSample.usecase,
                      target = runtime.target,
                      bytes = newBytes
                    ),
                    DynamoDbConsumptionEvent.StorageBytesDelta(
                      eventTime = writeSample.eventTime,
                      usecase = writeSample.usecase,
                      target = runtime.target,
                      bytesDelta = plan.storageBytesDelta
                    )
                  )
                case IndexMaintenanceAction.DeleteEntry =>
                  val previousBytes = plan.previousIndexEntryBytes.getOrElse(0L)
                  runtime.stateModel.recordSuccessfulDelete(plan.previousIndexEntryBytes)
                  List(
                    writeCapEvent(runtime.target, plan.throughputDemand),
                    DynamoDbConsumptionEvent.StorageBytesDeleted(
                      eventTime = writeSample.eventTime,
                      usecase = writeSample.usecase,
                      target = runtime.target,
                      bytes = previousBytes
                    ),
                    DynamoDbConsumptionEvent.StorageBytesDelta(
                      eventTime = writeSample.eventTime,
                      usecase = writeSample.usecase,
                      target = runtime.target,
                      bytesDelta = plan.storageBytesDelta
                    )
                  )
            }

          case _: AdmittedRequestSample =>
            Nil
        }
      )

      val metricFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].mapConcat[TimedElement[StorageMetricEvent]] {
          case t: TimedControlEvent => List(t)

          case writeSample: AdmittedWriteRequestSample =>
            writeSample.indexMaintenancePlan.map { plan =>
              plan.action match
                case IndexMaintenanceAction.NoOp =>
                  StorageMetricEvent.IndexEntryUnchanged(
                    eventTime = writeSample.eventTime,
                    usecase = writeSample.usecase,
                    target = plan.target
                  )
                case IndexMaintenanceAction.InsertEntry =>
                  StorageMetricEvent.IndexEntryInserted(
                    eventTime = writeSample.eventTime,
                    usecase = writeSample.usecase,
                    target = plan.target,
                    bytes = plan.newIndexEntryBytes.getOrElse(0L)
                  )
                case IndexMaintenanceAction.ReplaceEntry =>
                  StorageMetricEvent.IndexEntryReplaced(
                    eventTime = writeSample.eventTime,
                    usecase = writeSample.usecase,
                    target = plan.target,
                    previousBytes = plan.previousIndexEntryBytes.getOrElse(0L),
                    newBytes = plan.newIndexEntryBytes.getOrElse(0L),
                    bytesDelta = plan.storageBytesDelta
                  )
                case IndexMaintenanceAction.DeleteEntry =>
                  StorageMetricEvent.IndexEntryDeleted(
                    eventTime = writeSample.eventTime,
                    usecase = writeSample.usecase,
                    target = plan.target,
                    bytes = plan.previousIndexEntryBytes.getOrElse(0L)
                  )
            }

          case _: AdmittedRequestSample =>
            Nil
        }
      )

      broadcast.out(0) ~> consumptionFlow
      broadcast.out(1) ~> metricFlow

      new FanOutShape2(
        broadcast.in,
        consumptionFlow.out,
        metricFlow.out
      )
    }

  private def branchGraph(
                           stateModel: TableState,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           executionTarget: DynamoDbTarget,
                           admissionTarget: DynamoDbTarget,
                           indexProjection: Option[IndexProjection],
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
                           dynamicPartitionTopologyConfig: Option[TableAdmissionStage.DynamicPartitionTopologyConfig],
                           billingMode: BillingMode = BillingMode.OnDemand(),
                           indexMaintenanceTargets: Vector[TableAdmissionStage.IndexMaintenanceTargetConfig] = Vector.empty,
                           indexMaintenanceRuntimes: Vector[InternalIndexRuntime] = Vector.empty,
                           gsiWriteScopes: Vector[TableAdmissionStage.GsiWriteScopeConfig] = Vector.empty,
                           itemCollectionSizeLimitBytes: Option[Long] = None,
                           billingModeRef: Option[BillingModeRef] = None
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

      val admission = b.add(
        TableAdmissionStage.componentOf(
          TableAdmissionStage.Config(
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
            dynamicPartitionTopologyConfig = dynamicPartitionTopologyConfig,
            billingMode = billingMode,
            indexMaintenanceTargets = indexMaintenanceTargets,
            gsiWriteScopes = gsiWriteScopes
          ),
          billingModeRef = billingModeRef
        )
      )
      val storage = b.add(
        TableStorageStage.componentOfAdmitted(
          stateModel = stateModel,
          indexProjection = indexProjection,
          itemCollectionSizeLimitBytes = itemCollectionSizeLimitBytes
        )
      )
      val throttledResponseFilter = b.add(
        Flow[TimedElement[DynamoDBResponse]].collect[TimedElement[DynamoDBResponse]] {
          case response: DynamoDBResponse => response
        }
      )
      val admissionMetricFilter = b.add(
        Flow[TimedElement[AdmissionMetricEvent]].collect[TimedElement[TableMetricEvent]] {
          case metric: AdmissionMetricEvent => metric
        }
      )
      val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](2))
      val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](if indexMaintenanceRuntimes.nonEmpty then 2 else 1))
      val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](if indexMaintenanceRuntimes.nonEmpty then 3 else 2))

      // The storage stage now sits between admission and index-maintenance: only
      // writes that pass the item-collection check are forwarded to maintenance via
      // storage.out3 (validated samples). Rejected writes are absent from out3, so
      // their maintenance plans never propagate.
      admission.out0 ~> storage.in
      admission.out1 ~> throttledResponseFilter ~> responseMerge.in(0)
      storage.out0 ~> responseMerge.in(1)
      storage.out1 ~> consumptionMerge.in(0)
      admission.out2 ~> admissionMetricFilter ~> metricMerge.in(0)
      storage.out2 ~> metricMerge.in(1)

      if indexMaintenanceRuntimes.nonEmpty then
        val maintenanceStage = b.add(indexMaintenanceGraph(indexMaintenanceRuntimes))
        storage.out3 ~> maintenanceStage.in
        maintenanceStage.out0 ~> consumptionMerge.in(1)
        maintenanceStage.out1 ~> metricMerge.in(2)
      else
        val ignoreValidatedSamples = b.add(Sink.ignore)
        storage.out3 ~> ignoreValidatedSamples

      new FanOutShape3(
        admission.in,
        responseMerge.out,
        consumptionMerge.out,
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
    val indexRuntimes = indexRuntimesFor(config)
    val baseTableGraph =
      branchGraph(
        stateModel = config.stateModel,
        useCaseBehaviors = config.useCaseBehaviors,
        executionTarget = DynamoDbTarget.Table(config.tableName),
        admissionTarget = DynamoDbTarget.Table(config.tableName),
        indexProjection = None,
        readConsistency = config.readConsistency,
        maxReadRequestUnitsPerSecond = config.billingMode match
          case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
          case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
        maxWriteRequestUnitsPerSecond = config.billingMode match
          case BillingMode.OnDemand(odmt) => odmt.tableMaxWriteRequestUnitsPerSecond
          case p: BillingMode.Provisioned => Some(BigDecimal(p.writeCapacityUnits)),
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
            TableAdmissionStage.DynamicPartitionTopologyConfig(
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
          },
        billingMode = config.billingMode,
        indexMaintenanceTargets = indexMaintenanceTargetsFor(config, indexRuntimes),
        indexMaintenanceRuntimes = indexRuntimes,
        gsiWriteScopes = gsiWriteScopesFor(config, indexRuntimes),
        itemCollectionSizeLimitBytes = config.effectiveItemCollectionSizeLimitBytes
      )

    val globalSecondaryIndexes = config.globalSecondaryIndexes
    val localSecondaryIndexes = config.localSecondaryIndexes

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
        val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](branchCount))
        val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](branchCount))

        val baseRequestFilter = b.add(
          Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
            case t: TimedControlEvent => t
            case request: DynamoDBRequest if routeFor(config, request) == RouteBranch.BaseTable => request
          }
        )

        val baseTable = b.add(baseTableGraph)
        validationFlow.out ~> requestBroadcast.in
        requestBroadcast.out(0) ~> baseRequestFilter ~> baseTable.in
        baseTable.out0 ~> responseMerge.in(0)
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
              indexProjection = Some(indexDefinition.projection),
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond = config.billingMode match
                case BillingMode.OnDemand(odmt) =>
                  odmt.globalSecondaryIndexMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName)
                case p: BillingMode.Provisioned =>
                  Some(BigDecimal(p.globalSecondaryIndexReadCapacityUnits.getOrElse(indexDefinition.indexName, p.readCapacityUnits))),
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
                  TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                },
              billingMode = config.billingMode
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
              indexProjection = Some(indexDefinition.projection),
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond = config.billingMode match
                case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
                case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
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
                  TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                },
              billingMode = config.billingMode
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

  /**
   * Management-event-aware factory variant. Adds a `managementIn` inlet for `DynamoDbManagementEvent`
   * events (billing mode switches) alongside the standard request inlet. The table pipeline is
   * otherwise identical to `componentOf`.
   *
   * A management event processor validates the 24-hour billing mode switch cooldown and updates a
   * shared `BillingModeRef`. Each branch's admission stage detects the mode change at the next tick
   * boundary and adjusts its capacity state accordingly.
   */
  def componentOfManaged(config: Config): Graph[DynamoDbTableManagedShape, NotUsed] =
    val billingModeRef = new BillingModeRef(config.billingMode)
    val indexRuntimes = indexRuntimesFor(config)
    val baseTableGraph =
      branchGraph(
        stateModel = config.stateModel,
        useCaseBehaviors = config.useCaseBehaviors,
        executionTarget = DynamoDbTarget.Table(config.tableName),
        admissionTarget = DynamoDbTarget.Table(config.tableName),
        indexProjection = None,
        readConsistency = config.readConsistency,
        maxReadRequestUnitsPerSecond = config.billingMode match
          case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
          case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
        maxWriteRequestUnitsPerSecond = config.billingMode match
          case BillingMode.OnDemand(odmt) => odmt.tableMaxWriteRequestUnitsPerSecond
          case p: BillingMode.Provisioned => Some(BigDecimal(p.writeCapacityUnits)),
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
            TableAdmissionStage.DynamicPartitionTopologyConfig(
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
          },
        billingMode = config.billingMode,
        indexMaintenanceTargets = indexMaintenanceTargetsFor(config, indexRuntimes),
        indexMaintenanceRuntimes = indexRuntimes,
        gsiWriteScopes = gsiWriteScopesFor(config, indexRuntimes),
        itemCollectionSizeLimitBytes = config.effectiveItemCollectionSizeLimitBytes,
        billingModeRef = Some(billingModeRef)
      )

    val globalSecondaryIndexes = config.globalSecondaryIndexes
    val localSecondaryIndexes = config.localSecondaryIndexes

    val tableGraph =
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
          val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](branchCount))
          val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](branchCount))

          val baseRequestFilter = b.add(
            Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
              case t: TimedControlEvent => t
              case request: DynamoDBRequest if routeFor(config, request) == RouteBranch.BaseTable => request
            }
          )

          val baseTable = b.add(baseTableGraph)
          validationFlow.out ~> requestBroadcast.in
          requestBroadcast.out(0) ~> baseRequestFilter ~> baseTable.in
          baseTable.out0 ~> responseMerge.in(0)
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
                indexProjection = Some(indexDefinition.projection),
                readConsistency = ReadConsistency.EventuallyConsistent,
                maxReadRequestUnitsPerSecond = config.billingMode match
                  case BillingMode.OnDemand(odmt) =>
                    odmt.globalSecondaryIndexMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName)
                  case p: BillingMode.Provisioned =>
                    Some(BigDecimal(p.globalSecondaryIndexReadCapacityUnits.getOrElse(indexDefinition.indexName, p.readCapacityUnits))),
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
                    TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                  },
                billingMode = config.billingMode,
                billingModeRef = Some(billingModeRef)
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
                indexProjection = Some(indexDefinition.projection),
                readConsistency = ReadConsistency.EventuallyConsistent,
                maxReadRequestUnitsPerSecond = config.billingMode match
                  case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
                  case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
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
                    TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                  },
                billingMode = config.billingMode,
                billingModeRef = Some(billingModeRef)
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

    val managementProcessor = managementProcessorOf(billingModeRef)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val table = b.add(tableGraph)
      val mgmt = b.add(managementProcessor)
      val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](2))

      table.out0 ~> responseMerge.in(0)
      mgmt.out ~> responseMerge.in(1)

      new DynamoDbTableManagedShape(
        requestIn = table.in,
        managementIn = mgmt.in,
        responseOut = responseMerge.out,
        consumptionOut = table.out1,
        metricOut = table.out2
      )
    }

  private def managementProcessorOf(
                                     billingModeRef: BillingModeRef
                                   ): Flow[TimedElement[DynamoDbManagementEvent], TimedElement[DynamoDBResponse], NotUsed] =
    Flow[TimedElement[DynamoDbManagementEvent]].statefulMapConcat[TimedElement[DynamoDBResponse]] { () =>
      {
        case _: TimedControlEvent => Nil
        case event: DynamoDbManagementEvent.SwitchBillingMode =>
          billingModeRef.lastSwitchTick match
            case Some(lastTick) if event.eventTime.ticks - lastTick < ReconfigurationSchedule.BillingModeSwitchCooldownTicks =>
              List(ReconfigurationRejectedResponse(
                event.eventTime,
                event.usecase,
                "Billing mode switch attempted within the 24-hour cooldown period"
              ))
            case _ =>
              // Enqueue rather than apply immediately: the management stream races ahead of the
              // request stream in Pekko's fused graph. Enqueueing here lets advanceToShaped apply
              // the change at the correct tick boundary.
              billingModeRef.enqueueModeChange(event.eventTime.ticks, event.newMode)
              billingModeRef.lastSwitchTick = Some(event.eventTime.ticks)
              Nil
        case event: DynamoDbManagementEvent.UpdateProvisionedCapacity =>
          // Validate against the effective mode at the event's tick (includes pending changes).
          billingModeRef.effectiveModeAt(event.eventTime.ticks) match
            case _: DynamoDbTable.BillingMode.Provisioned =>
              billingModeRef.enqueueModeChange(event.eventTime.ticks, event.newCapacity)
              Nil
            case _ =>
              List(ReconfigurationRejectedResponse(
                event.eventTime,
                event.usecase,
                "UpdateProvisionedCapacity is only valid when the table is in provisioned billing mode"
              ))
      }
    }

  // Tagged union used internally by replicatedWriteAdmissionOf. All three cases flow through
  // Broadcast(2) so each downstream collect can select what it needs without consuming an element
  // on only one path, which would stall the other.
  private sealed trait RwcuDecision
  private final case class RwcuTick(tick: TimedControlEvent)   extends RwcuDecision
  private final case class RwcuAdmitted(inner: TimedEvent)     extends RwcuDecision
  private final case class RwcuThrottled(resp: ThrottledResponse) extends RwcuDecision

  // Per-tick token-bucket admission check for incoming replicated writes.
  // out0 = admitted path (TimedEvent, feeds replicatedInletMerge)
  // out1 = throttled responses (TimedElement[DynamoDBResponse], feeds responseMerge)
  // Known inaccuracy: real DynamoDB queues and retries throttled replicated writes; accurate
  // model (capacity-constrained drain in ReplicationCoordinator) is deferred to slice 6.
  private def replicatedWriteAdmissionOf(
    config: Config,
    billingModeRef: Option[BillingModeRef]
  ): Graph[FanOutShape2[TimedElement[AdmittedRequestSample], TimedEvent, TimedElement[DynamoDBResponse]], NotUsed] =
    val Unlimited = BigDecimal(Long.MaxValue)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val decisionFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].statefulMapConcat { () =>
          var rWcuBudget: BigDecimal = Unlimited

          {
            case tick: TimedControlEvent.Tick =>
              val ceiling =
                billingModeRef
                  .map(_.effectiveModeAt(tick.eventTime.ticks))
                  .getOrElse(config.billingMode) match
                    case p: BillingMode.Provisioned =>
                      p.replicatedWriteCapacityUnits.map(BigDecimal(_))
                    case _: BillingMode.OnDemand => None
              rWcuBudget = ceiling.getOrElse(Unlimited)
              List(RwcuTick(tick))

            case other: TimedControlEvent =>
              List(RwcuTick(other))

            case sample: Replicated[?] =>
              val demand = sample.throughputDemand
              if rWcuBudget == Unlimited || rWcuBudget >= demand then
                if rWcuBudget != Unlimited then rWcuBudget -= demand
                List(RwcuAdmitted(sample))
              else
                List(RwcuThrottled(ThrottledResponse(
                  eventTime = sample.req.eventTime,
                  usecase   = sample.req.usecase,
                  operation = DynamoDbOperationKind.fromRequest(sample.req),
                  target    = sample.admissionTarget,
                  dimension = DynamoDbThroughputDimension.Write,
                  reason    = DynamoDbThrottleReason.ReplicatedWriteCapacityExceeded
                )))

            case other: AdmittedRequestSample =>
              List(RwcuAdmitted(other))
          }
        }
      )

      val broadcast = b.add(Broadcast[RwcuDecision](2))

      val admittedOut = b.add(
        Flow[RwcuDecision].collect[TimedEvent] {
          case RwcuTick(t)     => t
          case RwcuAdmitted(e) => e
        }
      )

      val throttledOut = b.add(
        Flow[RwcuDecision].collect[TimedElement[DynamoDBResponse]] {
          case RwcuTick(t)      => t
          case RwcuThrottled(r) => r
        }
      )

      decisionFlow.out ~> broadcast.in
      broadcast.out(0) ~> admittedOut
      broadcast.out(1) ~> throttledOut

      new FanOutShape2(decisionFlow.in, admittedOut.out, throttledOut.out)
    }

  /**
   * Replication-aware factory variant. Produces a graph with the same response/consumption/metric
   * outputs as `componentOf`, plus an inbound port for replicated writes (which bypass admission)
   * and an outbound port emitting validated admitted samples for the replication coordinator.
   *
   * Supports full GSI/LSI configurations. GSI/LSI read branches (Query/Scan) are wired exactly
   * as in `componentOf`. Index maintenance (write amplification, rWCU accounting) runs from
   * `storage.out3`, so replicated writes also trigger index maintenance at the destination region.
   */
  private def componentOfReplicatedInternal(
                                            config: Config,
                                            billingModeRef: Option[BillingModeRef] = None
                                          ): Graph[DynamoDbTableReplicatedShape, NotUsed] =
    config.billingMode match
      case p: BillingMode.Provisioned if p.replicatedWriteCapacityUnits.isEmpty =>
        throw new IllegalArgumentException(
          s"componentOfReplicated requires BillingMode.Provisioned.replicatedWriteCapacityUnits " +
          s"to be set for table '${config.tableName}'"
        )
      case _ =>

    val indexRuntimes = indexRuntimesFor(config)
    val hasIndexes = config.globalSecondaryIndexes.nonEmpty || config.localSecondaryIndexes.nonEmpty
    val numIndexBranches = config.globalSecondaryIndexes.size + config.localSecondaryIndexes.size
    val executionTarget: DynamoDbTarget = DynamoDbTarget.Table(config.tableName)
    val admissionTarget: DynamoDbTarget = DynamoDbTarget.Table(config.tableName)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val admission = b.add(
        TableAdmissionStage.componentOf(
          TableAdmissionStage.Config(
            executionTarget = executionTarget,
            admissionTarget = admissionTarget,
            useCaseBehaviors = config.useCaseBehaviors,
            stateModel = config.stateModel,
            readConsistency = config.readConsistency,
            maxReadRequestUnitsPerSecond = config.billingMode match
              case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
              case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
            maxWriteRequestUnitsPerSecond = config.billingMode match
              case BillingMode.OnDemand(odmt) => odmt.tableMaxWriteRequestUnitsPerSecond
              case p: BillingMode.Provisioned => Some(BigDecimal(p.writeCapacityUnits)),
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
                TableAdmissionStage.DynamicPartitionTopologyConfig(
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
              },
            billingMode = config.billingMode,
            indexMaintenanceTargets = indexMaintenanceTargetsFor(config, indexRuntimes),
            gsiWriteScopes = gsiWriteScopesFor(config, indexRuntimes)
          ),
          billingModeRef = billingModeRef
        )
      )

      val storage = b.add(
        TableStorageStage.componentOfAdmitted(
          stateModel = config.stateModel,
          indexProjection = None,
          itemCollectionSizeLimitBytes = config.effectiveItemCollectionSizeLimitBytes
        )
      )

      // The outbound replication output forks from admission.out0 (admitted client requests)
      // BEFORE the writes are merged with replicated-input. This is critical: it prevents an
      // infinite replication loop. If we forked from storage.out3 instead, then replicated
      // writes applied at this region would be re-emitted on the outbound, sent back to the
      // coordinator, and fanned out again — endlessly. Forking from admission.out0 captures
      // only writes that originated as client requests at this region; replicated writes
      // bypass admission and never appear here.
      //
      // Note: a write that's admitted but later rejected by storage's item-collection check
      // would still be replicated outbound. This is an accepted approximation.
      val admissionFork = b.add(Broadcast[TimedElement[AdmittedRequestSample]](2))

      // Combine admission's admitted samples with the inbound replicated-writes port.
      // Both streams are tick-aligned (the global-table factory ensures this); MergeTimedEventGraph
      // pairs ticks across the two streams and produces a single TimedEvent stream feeding storage.
      val replicatedInletMerge = b.add(MergeTimedEventGraph.graphOf(bufferSize = 16))

      // Upcast admission's admitted samples to TimedEvent for the merger.
      val upcastAdmission = b.add(Flow[TimedElement[AdmittedRequestSample]].map[TimedEvent](e => e))

      // Coerce TimedEvent back to TimedElement[AdmittedRequestSample] for storage's input shape.
      val coerceToAdmitted = b.add(
        Flow[TimedEvent].collect[TimedElement[AdmittedRequestSample]] {
          case s: AdmittedRequestSample => s
          case t: TimedControlEvent => t
        }
      )

      val throttledResponseFilter = b.add(
        Flow[TimedElement[DynamoDBResponse]].collect[TimedElement[DynamoDBResponse]] {
          case response: DynamoDBResponse => response
        }
      )
      val admissionMetricFilter = b.add(
        Flow[TimedElement[AdmissionMetricEvent]].collect[TimedElement[TableMetricEvent]] {
          case metric: AdmissionMetricEvent => metric
        }
      )

      // Outbound write filter: only writes (Put/Update/Delete) appear on the outbound
      // replication output. Reads passed through admission but are not replicated.
      val outboundWriteFilter = b.add(
        Flow[TimedElement[AdmittedRequestSample]].collect[TimedElement[AdmittedRequestSample]] {
          case t: TimedControlEvent => t
          case s: AdmittedPutItemSample => s
          case s: AdmittedUpdateItemSample => s
          case s: AdmittedDeleteItemSample => s
        }
      )

      val rwcuAdmission = b.add(replicatedWriteAdmissionOf(config, billingModeRef))

      // Core replication wiring (same regardless of whether indexes are configured).
      // admission.out0 → fork(2): one path to storage (via merge with replicatedIn), one to outbound.
      admission.out0 ~> admissionFork.in
      admissionFork.out(0) ~> upcastAdmission.in
      upcastAdmission.out ~> replicatedInletMerge.in0
      admissionFork.out(1) ~> outboundWriteFilter.in
      rwcuAdmission.out0 ~> replicatedInletMerge.in1
      replicatedInletMerge.out ~> coerceToAdmitted.in
      coerceToAdmitted.out ~> storage.in

      if !hasIndexes then
        // Base-table-only: storage.out3 unused; consumption wired directly from storage.
        val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](3))
        val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](2))
        val ignoreValidatedSamples = b.add(Sink.ignore)

        admission.out1 ~> throttledResponseFilter ~> responseMerge.in(0)
        storage.out0 ~> responseMerge.in(1)
        rwcuAdmission.out1 ~> responseMerge.in(2)
        storage.out3 ~> ignoreValidatedSamples
        admission.out2 ~> admissionMetricFilter ~> metricMerge.in(0)
        storage.out2 ~> metricMerge.in(1)

        new DynamoDbTableReplicatedShape(
          requestIn = admission.in,
          replicatedIn = rwcuAdmission.in,
          responseOut = responseMerge.out,
          consumptionOut = storage.out1,
          metricOut = metricMerge.out,
          outboundReplicationOut = outboundWriteFilter.out
        )
      else
        // With indexes: validate + broadcast client requests; wire storage.out3 into
        // indexMaintenanceGraph so both client and replicated writes trigger index maintenance.
        val validationFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBRequest]] {
            case request: DynamoDBRequest =>
              validateRequest(config, request)
              request
            case t: TimedControlEvent => t
          }
        )
        val requestBroadcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](1 + numIndexBranches))
        val baseRequestFilter = b.add(
          Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
            case t: TimedControlEvent => t
            case r: DynamoDBRequest if routeFor(config, r) == RouteBranch.BaseTable => r
          }
        )
        validationFlow.out ~> requestBroadcast.in
        requestBroadcast.out(0) ~> baseRequestFilter ~> admission.in

        val maintenance = b.add(indexMaintenanceGraph(indexRuntimes))
        storage.out3 ~> maintenance.in

        // responseMerge: throttled(0) + storage(1) + rwcuThrottled(2) + one inlet per index branch
        val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](3 + numIndexBranches))
        // consumptionMerge: storage(0) + maintenance(1) + one inlet per index branch
        val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](2 + numIndexBranches))
        // metricMerge: admission(0) + storage(1) + maintenance(2) + one inlet per index branch
        val metricMerge = b.add(Merge[TimedElement[TableMetricEvent]](3 + numIndexBranches))

        admission.out1 ~> throttledResponseFilter ~> responseMerge.in(0)
        storage.out0 ~> responseMerge.in(1)
        rwcuAdmission.out1 ~> responseMerge.in(2)
        storage.out1 ~> consumptionMerge.in(0)
        maintenance.out0 ~> consumptionMerge.in(1)
        admission.out2 ~> admissionMetricFilter ~> metricMerge.in(0)
        storage.out2 ~> metricMerge.in(1)
        maintenance.out1 ~> metricMerge.in(2)

        var broadcastIdx = 1  // requestBroadcast outlet (0 is base table)
        var respIdx = 3       // responseMerge inlet (0=throttled, 1=storage, 2=rwcuThrottled)
        var consIdx = 2       // consumptionMerge inlet
        var metIdx = 3        // metricMerge inlet

        config.globalSecondaryIndexes.foreach { indexDefinition =>
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
          val gsiStage = b.add(
            branchGraph(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              executionTarget = indexRuntime.target,
              admissionTarget = indexRuntime.target,
              indexProjection = Some(indexDefinition.projection),
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond = config.billingMode match
                case BillingMode.OnDemand(odmt) =>
                  odmt.globalSecondaryIndexMaxReadRequestUnitsPerSecond.get(indexDefinition.indexName)
                case p: BillingMode.Provisioned =>
                  Some(BigDecimal(p.globalSecondaryIndexReadCapacityUnits.getOrElse(indexDefinition.indexName, p.readCapacityUnits))),
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
                  TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                },
              billingMode = config.billingMode
              ,
              billingModeRef = billingModeRef
            )
          )
          requestBroadcast.out(broadcastIdx) ~> requestFilter ~> gsiStage.in
          gsiStage.out0 ~> responseMerge.in(respIdx)
          gsiStage.out1 ~> consumptionMerge.in(consIdx)
          gsiStage.out2 ~> metricMerge.in(metIdx)
          broadcastIdx += 1; respIdx += 1; consIdx += 1; metIdx += 1
        }

        config.localSecondaryIndexes.foreach { indexDefinition =>
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
          val lsiStage = b.add(
            branchGraph(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              executionTarget = indexRuntime.target,
              admissionTarget = DynamoDbTarget.Table(config.tableName),
              indexProjection = Some(indexDefinition.projection),
              readConsistency = ReadConsistency.EventuallyConsistent,
              maxReadRequestUnitsPerSecond = config.billingMode match
                case BillingMode.OnDemand(odmt) => odmt.tableMaxReadRequestUnitsPerSecond
                case p: BillingMode.Provisioned => Some(BigDecimal(p.readCapacityUnits)),
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
                  TableAdmissionStage.DynamicPartitionTopologyConfig(
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
                },
              billingMode = config.billingMode
              ,
              billingModeRef = billingModeRef
            )
          )
          requestBroadcast.out(broadcastIdx) ~> requestFilter ~> lsiStage.in
          lsiStage.out0 ~> responseMerge.in(respIdx)
          lsiStage.out1 ~> consumptionMerge.in(consIdx)
          lsiStage.out2 ~> metricMerge.in(metIdx)
          broadcastIdx += 1; respIdx += 1; consIdx += 1; metIdx += 1
        }

        new DynamoDbTableReplicatedShape(
          requestIn = validationFlow.in,
          replicatedIn = rwcuAdmission.in,
          responseOut = responseMerge.out,
          consumptionOut = consumptionMerge.out,
          metricOut = metricMerge.out,
          outboundReplicationOut = outboundWriteFilter.out
        )
    }

  def componentOfReplicated(config: Config): Graph[DynamoDbTableReplicatedShape, NotUsed] =
    componentOfReplicatedInternal(config)

  def componentOfManagedReplicated(config: Config): Graph[DynamoDbTableManagedReplicatedShape, NotUsed] =
    val billingModeRef = new BillingModeRef(config.billingMode)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val replicatedTable = b.add(componentOfReplicatedInternal(config, Some(billingModeRef)))
      val managementProcessor = b.add(managementProcessorOf(billingModeRef))
      val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](2))

      replicatedTable.responseOut ~> responseMerge.in(0)
      managementProcessor.out ~> responseMerge.in(1)

      new DynamoDbTableManagedReplicatedShape(
        requestIn = replicatedTable.requestIn,
        managementIn = managementProcessor.in,
        replicatedIn = replicatedTable.replicatedIn,
        responseOut = responseMerge.out,
        consumptionOut = replicatedTable.consumptionOut,
        metricOut = replicatedTable.metricOut,
        outboundReplicationOut = replicatedTable.outboundReplicationOut
      )
    }
