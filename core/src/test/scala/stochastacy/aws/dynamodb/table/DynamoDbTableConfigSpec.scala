package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class DynamoDbTableConfigSpec extends AnyWordSpec with should.Matchers:

  "DynamoDbTable.Config" should {
    "represent table-only and indexed-table configurations" in {
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
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
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
      withLocalSecondaryIndex.localSecondaryIndexes.map(_.indexName) shouldBe Vector("created-at-index")
      withBoth.globalSecondaryIndexes.map(_.indexName) shouldBe Vector("status-index")
      withBoth.localSecondaryIndexes.map(_.indexName) shouldBe Vector("created-at-index")
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
  }
