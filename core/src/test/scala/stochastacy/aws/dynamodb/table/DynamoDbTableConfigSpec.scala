package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class DynamoDbTableConfigSpec extends AnyWordSpec with should.Matchers:

  "DynamoDbTable.Config" should {
    "represent table-only and indexed-table configurations" in {
      val explicitIndexState = FixedTableState(itemCount = 2L, totalItemBytes = 256L)

      val tableOnly =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty
        )

      val withGlobalSecondaryIndex =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = explicitIndexState)
          )
        )

      val withLocalSecondaryIndex =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index")
          )
        )

      val withBoth =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index")
          )
        )

      tableOnly.globalSecondaryIndexes shouldBe empty
      tableOnly.localSecondaryIndexes shouldBe empty
      withGlobalSecondaryIndex.globalSecondaryIndexes.map(_.indexName) shouldBe Vector("status-index")
      withGlobalSecondaryIndex.globalSecondaryIndexes.head.stateModel shouldBe explicitIndexState
      withLocalSecondaryIndex.localSecondaryIndexes.map(_.indexName) shouldBe Vector("created-at-index")
      withBoth.globalSecondaryIndexes.map(_.indexName) shouldBe Vector("status-index")
      withBoth.localSecondaryIndexes.map(_.indexName) shouldBe Vector("created-at-index")
    }

    "default index definitions to empty summary state when no explicit state model is provided" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index")
          )
        )

      config.globalSecondaryIndexes.head.stateModel.itemCount shouldBe 0L
      config.globalSecondaryIndexes.head.stateModel.totalItemBytes shouldBe 0L
      config.localSecondaryIndexes.head.stateModel.itemCount shouldBe 0L
      config.localSecondaryIndexes.head.stateModel.totalItemBytes shouldBe 0L
    }

    "reject duplicate index names across configured GSIs and LSIs" in {
      val error =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            globalSecondaryIndexes = Vector(
              DynamoDbTable.GlobalSecondaryIndexDefinition("shared-index")
            ),
            localSecondaryIndexes = Vector(
              DynamoDbTable.LocalSecondaryIndexDefinition("shared-index")
            )
          )
        }

      error.getMessage should include("Duplicate index names configured for table 'orders'")
      error.getMessage should include("shared-index")
    }

    "accept optional on-demand max throughput config for the table and configured GSIs" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          onDemandMaxThroughput = DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(100)),
            tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal(200)),
            globalSecondaryIndexMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal(25))
          )
        )

      config.onDemandMaxThroughput.tableMaxReadRequestUnitsPerSecond shouldBe Some(BigDecimal(100))
      config.onDemandMaxThroughput.tableMaxWriteRequestUnitsPerSecond shouldBe Some(BigDecimal(200))
      config.onDemandMaxThroughput.globalSecondaryIndexMaxReadRequestUnitsPerSecond shouldBe Map(
        "status-index" -> BigDecimal(25)
      )
    }

    "reject on-demand GSI throughput config for unknown indexes" in {
      val error =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            globalSecondaryIndexes = Vector(
              DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
            ),
            onDemandMaxThroughput = DynamoDbTable.OnDemandMaxThroughput(
              globalSecondaryIndexMaxReadRequestUnitsPerSecond = Map("missing-index" -> BigDecimal(25))
            )
          )
        }

      error.getMessage should include("unknown global secondary indexes")
      error.getMessage should include("missing-index")
    }

    "accept optional fixed hot-partition topology config" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 8,
              tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(4)),
              tablePerPartitionMaxWriteRequestUnitsPerSecond = Some(BigDecimal(2)),
              globalSecondaryIndexPartitionCounts = Map("status-index" -> 4),
              globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal(3))
            )
          )
        )

      config.hotPartitionModel.map(_.tablePartitionCount) shouldBe Some(8)
      config.hotPartitionModel.flatMap(_.tablePerPartitionMaxReadRequestUnitsPerSecond) shouldBe Some(BigDecimal(4))
      config.hotPartitionModel.flatMap(_.tablePerPartitionMaxWriteRequestUnitsPerSecond) shouldBe Some(BigDecimal(2))
      config.hotPartitionModel.map(_.globalSecondaryIndexPartitionCounts) shouldBe Some(Map("status-index" -> 4))
    }

    "accept optional dynamic partition-topology config for the table and configured GSIs" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          dynamicPartitionTopologyModel = Some(
            DynamoDbTable.DynamicPartitionTopologyModel(
              tableInitialPartitionCount = 2,
              globalSecondaryIndexInitialPartitionCounts = Map("status-index" -> 3),
              tableStorageSplitThresholdBytes = Some(1024L),
              globalSecondaryIndexStorageSplitThresholdBytes = Map("status-index" -> 2048L),
              tableThroughputGrowthSplitThresholdRequestUnitsPerSecond = Some(BigDecimal(5)),
              tableWriteThroughputGrowthSplitThresholdRequestUnitsPerSecond = Some(BigDecimal(4)),
              globalSecondaryIndexReadThroughputGrowthSplitThresholdRequestUnitsPerSecond = Map("status-index" -> BigDecimal(3)),
              heatSplitSustainWindowSeconds = 2,
              tableReadHeatSplitTriggerRequestUnitsPerSecondPerPartition = Some(BigDecimal(2)),
              tableWriteHeatSplitTriggerRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
              globalSecondaryIndexReadHeatSplitTriggerRequestUnitsPerSecondPerPartition = Map("status-index" -> BigDecimal("0.5")),
              maxTablePartitionCount = Some(6),
              maxGlobalSecondaryIndexPartitionCounts = Map("status-index" -> 5)
            )
          )
        )

      config.dynamicPartitionTopologyModel.map(_.tableInitialPartitionCount) shouldBe Some(2)
      config.dynamicPartitionTopologyModel.map(_.globalSecondaryIndexInitialPartitionCounts) shouldBe Some(Map("status-index" -> 3))
      config.dynamicPartitionTopologyModel.map(_.maxTablePartitionCount) shouldBe Some(Some(6))
    }

    "reject hot-partition config for unknown global secondary indexes" in {
      val error =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            hotPartitionModel = Some(
              DynamoDbTable.HotPartitionModel(
                tablePartitionCount = 4,
                globalSecondaryIndexPartitionCounts = Map("missing-index" -> 2)
              )
            )
          )
        }

      error.getMessage should include("Hot-partition config references unknown global secondary indexes")
      error.getMessage should include("missing-index")
    }

    "reject dynamic partition-topology config for unknown global secondary indexes or invalid maxima" in {
      val unknownIndexError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            dynamicPartitionTopologyModel = Some(
              DynamoDbTable.DynamicPartitionTopologyModel(
                tableInitialPartitionCount = 2,
                globalSecondaryIndexInitialPartitionCounts = Map("missing-index" -> 3)
              )
            )
          )
        }

      unknownIndexError.getMessage should include("Dynamic partition-topology config references unknown global secondary indexes")
      unknownIndexError.getMessage should include("missing-index")

      val invalidMaxError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.DynamicPartitionTopologyModel(
            tableInitialPartitionCount = 4,
            maxTablePartitionCount = Some(3)
          )
        }

      invalidMaxError.getMessage should include("maxTablePartitionCount must be >= tableInitialPartitionCount")
    }

    "accept optional burst-capacity config for the table and configured GSIs" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          onDemandMaxThroughput = DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(100)),
            tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal(200)),
            globalSecondaryIndexMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal(25))
          ),
          burstCapacityModel = Some(
            DynamoDbTable.BurstCapacityModel(
              retentionWindowSeconds = 300,
              initialTableReadBurstRequestUnits = Some(BigDecimal(50)),
              initialTableWriteBurstRequestUnits = Some(BigDecimal(75)),
              initialGlobalSecondaryIndexReadBurstRequestUnits = Map("status-index" -> BigDecimal(10))
            )
          )
        )

      config.burstCapacityModel.flatMap(_.initialTableReadBurstRequestUnits) shouldBe Some(BigDecimal(50))
      config.burstCapacityModel.flatMap(_.initialTableWriteBurstRequestUnits) shouldBe Some(BigDecimal(75))
      config.burstCapacityModel.map(_.initialGlobalSecondaryIndexReadBurstRequestUnits) shouldBe Some(
        Map("status-index" -> BigDecimal(10))
      )
    }

    "reject burst-capacity config for unknown global secondary indexes or missing steady-state throughput" in {
      val unknownIndexError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            burstCapacityModel = Some(
              DynamoDbTable.BurstCapacityModel(
                initialGlobalSecondaryIndexReadBurstRequestUnits = Map("missing-index" -> BigDecimal(10))
              )
            )
          )
        }

      unknownIndexError.getMessage should include("Burst-capacity config references unknown global secondary indexes")
      unknownIndexError.getMessage should include("missing-index")

      val missingThroughputError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            burstCapacityModel = Some(
              DynamoDbTable.BurstCapacityModel(
                initialTableReadBurstRequestUnits = Some(BigDecimal(10))
              )
            )
          )
        }

      missingThroughputError.getMessage should include("initialTableReadBurstRequestUnits without tableMaxReadRequestUnitsPerSecond")
    }

    "accept optional adaptive-capacity config for the table and configured GSIs" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map.empty,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
          ),
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 8,
              tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(2)),
              tablePerPartitionMaxWriteRequestUnitsPerSecond = Some(BigDecimal(1)),
              globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal("0.5"))
            )
          ),
          adaptiveCapacityModel = Some(
            DynamoDbTable.AdaptiveCapacityModel(
              tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Some(BigDecimal(4)),
              tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond = Some(BigDecimal(2)),
              globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal(1))
            )
          )
        )

      config.adaptiveCapacityModel.flatMap(_.tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond) shouldBe Some(BigDecimal(4))
      config.adaptiveCapacityModel.flatMap(_.tablePerPartitionAdaptiveMaxWriteRequestUnitsPerSecond) shouldBe Some(BigDecimal(2))
      config.adaptiveCapacityModel.map(_.globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond) shouldBe Some(
        Map("status-index" -> BigDecimal(1))
      )
    }

    "reject adaptive-capacity config for unknown GSIs or invalid baseline relationships" in {
      val unknownIndexError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            adaptiveCapacityModel = Some(
              DynamoDbTable.AdaptiveCapacityModel(
                globalSecondaryIndexPerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Map("missing-index" -> BigDecimal(1))
              )
            )
          )
        }

      unknownIndexError.getMessage should include("Adaptive-capacity config references unknown global secondary indexes")

      val missingBaselineError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            adaptiveCapacityModel = Some(
              DynamoDbTable.AdaptiveCapacityModel(
                tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Some(BigDecimal(2))
              )
            )
          )
        }

      missingBaselineError.getMessage should include("tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond without a table read hot-partition baseline")

      val belowBaselineError =
        the[IllegalArgumentException] thrownBy {
          DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
            useCaseBehaviors = Map.empty,
            hotPartitionModel = Some(
              DynamoDbTable.HotPartitionModel(
                tablePartitionCount = 4,
                tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(2))
              )
            ),
            adaptiveCapacityModel = Some(
              DynamoDbTable.AdaptiveCapacityModel(
                tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
              )
            )
          )
        }

      belowBaselineError.getMessage should include("requires table read adaptive max")
    }
  }
