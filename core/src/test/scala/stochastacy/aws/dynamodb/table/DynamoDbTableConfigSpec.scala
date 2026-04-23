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
  }
