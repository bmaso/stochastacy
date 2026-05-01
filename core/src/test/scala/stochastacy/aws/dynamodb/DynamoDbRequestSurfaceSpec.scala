package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbTarget, LogicalPartitionAccess, ReadConsistency, ResolvedPartitionFootprint, AdmissionMode, AdmissionMetricEvent, StorageMetricEvent, TableMetricEvent, TopologyChangeReason, TopologyScope}
import scala.collection.immutable.SortedMap
import stochastacy.sim.SimTime

class DynamoDbRequestSurfaceSpec extends AnyWordSpec with should.Matchers:

  "The phase-2 DynamoDB request surface" should {
    "treat Query and Scan requests as DynamoDB requests with explicit read targets" in {
      val queryRequest = QueryRequest(
        eventTime = SimTime.of(1L),
        usecase = "query-usecase",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.StronglyConsistent,
        requestedReadShape = RequestedReadShape.RequestedAttributeBytes(256L)
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
      queryRequest.requestedReadShape shouldBe RequestedReadShape.RequestedAttributeBytes(256L)
      scanRequest.target shouldBe DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
      scanRequest.readConsistency shouldBe ReadConsistency.EventuallyConsistent
      scanRequest.requestedReadShape shouldBe RequestedReadShape.AllProjectedOrFullItem
      lsiRequest.target shouldBe DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index")
      lsiRequest.readConsistency shouldBe ReadConsistency.StronglyConsistent
      lsiRequest.requestedReadShape shouldBe RequestedReadShape.AllProjectedOrFullItem
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
      val throttledResponse = ThrottledResponse(
        eventTime = SimTime.of(8L),
        usecase = "throttled-usecase",
        operation = DynamoDbOperationKind.Query,
        target = stochastacy.aws.dynamodb.table.DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
        dimension = DynamoDbThroughputDimension.Read,
        reason = DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
      )

      partiqlRequest shouldBe a[DynamoDBRequest]
      queryResponse shouldBe a[DynamoDBResponse]
      scanResponse shouldBe a[DynamoDBResponse]
      partiqlResponse shouldBe a[DynamoDBResponse]
      throttledResponse shouldBe a[DynamoDBResponse]

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
      throttledResponse.operation shouldBe DynamoDbOperationKind.Query
      throttledResponse.dimension shouldBe DynamoDbThroughputDimension.Read
      throttledResponse.reason shouldBe DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
    }

    "expose a unified table metric surface spanning admission and storage events" in {
      val admittedMetric: TableMetricEvent =
        AdmissionMetricEvent.RequestAdmitted(
          eventTime = SimTime.of(9L),
          usecase = "get-hit",
          operation = DynamoDbOperationKind.GetItem,
          target = DynamoDbTarget.Table("orders"),
          dimension = DynamoDbThroughputDimension.Read,
          throughputDemand = BigDecimal(1),
          admissionMode = AdmissionMode.Normal,
          adaptiveConsumedRequestUnits = BigDecimal(0),
          adaptiveAvailableRequestUnits = BigDecimal(0),
          burstConsumedRequestUnits = BigDecimal(0),
          burstRemainingRequestUnits = BigDecimal(300),
          topologyPartitionCount = 1,
          resolvedPartitionFootprint = ResolvedPartitionFootprint(
            totalPartitionCount = 1,
            partitionDemandById = SortedMap(0 -> BigDecimal(1))
          )
        )
      val topologyMetric: TableMetricEvent =
        AdmissionMetricEvent.TopologyChanged(
          eventTime = SimTime.of(9L),
          usecase = "topology-change",
          scope = TopologyScope.Table,
          reason = TopologyChangeReason.ThroughputGrowth,
          previousPartitionCount = 1,
          newPartitionCount = 2
        )
      val observedMetric: TableMetricEvent =
        StorageMetricEvent.GetItemObserved(
          eventTime = SimTime.of(10L),
          usecase = "get-hit"
        )

      admittedMetric shouldBe a[TableMetricEvent]
      topologyMetric shouldBe a[TableMetricEvent]
      observedMetric shouldBe a[TableMetricEvent]
      admittedMetric shouldBe a[AdmissionMetricEvent.RequestAdmitted]
      topologyMetric shouldBe a[AdmissionMetricEvent.TopologyChanged]
      observedMetric shouldBe a[StorageMetricEvent.GetItemObserved]
    }
  }
