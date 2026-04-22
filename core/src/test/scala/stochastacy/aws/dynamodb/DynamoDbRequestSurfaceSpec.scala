package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.sim.SimTime

class DynamoDbRequestSurfaceSpec extends AnyWordSpec with should.Matchers:

  "The phase-2 DynamoDB request surface" should {
    "treat Query and Scan requests as DynamoDB requests with explicit read targets" in {
      val queryRequest = QueryRequest(
        eventTime = SimTime.of(1L),
        usecase = "query-usecase",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.StronglyConsistent
      )
      val scanRequest = ScanRequest(
        eventTime = SimTime.of(2L),
        usecase = "scan-usecase",
        target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
        readConsistency = ReadConsistency.EventuallyConsistent
      )
      val lsiRequest = ScanRequest(
        eventTime = SimTime.of(3L),
        usecase = "lsi-scan",
        target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
        readConsistency = ReadConsistency.StronglyConsistent
      )

      queryRequest shouldBe a[DynamoDBRequest]
      scanRequest shouldBe a[DynamoDBRequest]
      lsiRequest shouldBe a[DynamoDBRequest]

      queryRequest.target shouldBe DynamoDbReadTarget.Table("orders")
      queryRequest.readConsistency shouldBe ReadConsistency.StronglyConsistent
      scanRequest.target shouldBe DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
      scanRequest.readConsistency shouldBe ReadConsistency.EventuallyConsistent
      lsiRequest.target shouldBe DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index")
      lsiRequest.readConsistency shouldBe ReadConsistency.StronglyConsistent
    }

    "treat PartiQL query requests and new phase-2 responses as part of the public DynamoDB surface" in {
      val partiqlRequest = PartiQLQueryRequest(
        eventTime = SimTime.of(4L),
        usecase = "partiql-usecase",
        queryText = "select * from orders"
      )
      val queryResponse = QueryResponse(
        eventTime = SimTime.of(5L),
        usecase = "query-response-usecase",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 4L,
        evaluatedBytes = 4096L,
        returnedItemCount = 2L,
        returnedBytes = 1024L
      )
      val scanResponse = ScanResponse(
        eventTime = SimTime.of(6L),
        usecase = "scan-response-usecase",
        target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 8L,
        evaluatedBytes = 16384L,
        returnedItemCount = 3L,
        returnedBytes = 3072L
      )
      val partiqlResponse = PartiQLQueryResponse(
        eventTime = SimTime.of(7L),
        usecase = "partiql-response-usecase",
        queryText = "select * from orders"
      )

      partiqlRequest shouldBe a[DynamoDBRequest]
      queryResponse shouldBe a[DynamoDBResponse]
      scanResponse shouldBe a[DynamoDBResponse]
      partiqlResponse shouldBe a[DynamoDBResponse]

      partiqlRequest.queryText shouldBe "select * from orders"
      queryResponse.readConsistency shouldBe ReadConsistency.EventuallyConsistent
      queryResponse.evaluatedItemCount shouldBe 4L
      queryResponse.evaluatedBytes shouldBe 4096L
      queryResponse.returnedItemCount shouldBe 2L
      queryResponse.returnedBytes shouldBe 1024L
      scanResponse.readConsistency shouldBe ReadConsistency.EventuallyConsistent
      scanResponse.evaluatedItemCount shouldBe 8L
      scanResponse.evaluatedBytes shouldBe 16384L
      scanResponse.returnedItemCount shouldBe 3L
      scanResponse.returnedBytes shouldBe 3072L
      partiqlResponse.queryText shouldBe "select * from orders"
    }
  }
