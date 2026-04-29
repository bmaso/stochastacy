package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class TableAdmissionStageSpec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  given ActorSystem = ActorSystem("table-admission-test")
  given Materializer = Materializer.matFromSystem

  "TableAdmissionStage" should {
    "admit a request under the configured hard check" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(1024L)),
            stateModel = FixedTableState(1L, 1024L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted.collect { case sample: AdmittedGetItemSample => sample.throughputDemand } shouldBe Vector(BigDecimal(1))
      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.throughputDemand } shouldBe Vector(BigDecimal(1))
    }

    "throttle a request over the configured hard check immediately" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted shouldBe empty
      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.throughputDemand } shouldBe Vector(BigDecimal(2))
    }

    "evaluate read and write hard checks separately" in {
      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L),
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")
        )
      )

      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          requests,
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "put-new" -> FixedPutItemBehavior(1024L, None),
              "get-hit" -> FixedHitGetItemBehavior(8192L)
            ),
            stateModel = FixedTableState(0L, 0L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
            maxWriteRequestUnitsPerSecond = Some(BigDecimal(1))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted.collect { case _: AdmittedPutItemSample => 1 } should have size 1
      admitted.collect { case _: AdmittedGetItemSample => 1 } shouldBe empty
      responses.collect { case response: ThrottledResponse => response.dimension } shouldBe Vector(DynamoDbThroughputDimension.Read)
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.dimension } shouldBe Vector(DynamoDbThroughputDimension.Write)
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.dimension } shouldBe Vector(DynamoDbThroughputDimension.Read)
    }

    "apply GSI read checks to GSI-targeted reads" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            admissionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            useCaseBehaviors = Map("query-usecase" -> FixedQueryBehavior(12288L)),
            stateModel = FixedTableState(5L, 12288L),
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")
      )
    }

    "apply base-table read checks to LSI-targeted reads" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "scan-usecase",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("scan-usecase" -> FixedScanBehavior(8192L)),
            stateModel = FixedTableState(5L, 8192L),
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.target } shouldBe Vector(
        DynamoDbTarget.Table("orders")
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
    }

    "preserve control timing events on all outputs" in {
      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          TimedControlEvent.Tick(SimTime.of(1L)),
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit"),
          TimedControlEvent.EndOfTime
        )
      )

      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          requests,
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(1024L)),
            stateModel = FixedTableState(1L, 1024L),
            maxReadRequestUnitsPerSecond = Some(BigDecimal(2))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L))
      responses.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L))
      metrics.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L))

      admitted.last shouldBe TimedControlEvent.EndOfTime
      responses.last shouldBe TimedControlEvent.EndOfTime
      metrics.last shouldBe TimedControlEvent.EndOfTime
    }

    "throttle for a hot read partition even when overall read throughput remains under the slice-1 hard check" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey("hot"))),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(10)),
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.resolvedPartitionFootprint.partitionDemandById.values.sum } shouldBe Vector(BigDecimal(2))
    }

    "throttle base-table writes for a hot write partition" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 2048L)),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "put-new" -> FixedPutItemBehavior(2048L, None, SingleLogicalPartitionKey("hot-write"))
            ),
            stateModel = FixedTableState(0L, 0L),
            maxWriteRequestUnitsPerSecond = Some(BigDecimal(10)),
            partitionCount = 4,
            maxWriteRequestUnitsPerSecondPerPartition = Some(BigDecimal(1))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableWriteHotPartitionThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.dimension } shouldBe Vector(
        DynamoDbThroughputDimension.Write
      )
    }

    "throttle GSI reads for a hot index partition" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            admissionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            useCaseBehaviors = Map(
              "query-usecase" -> FixedQueryBehavior(8192L, SingleLogicalPartitionKey("hot-gsi"))
            ),
            stateModel = FixedTableState(5L, 12288L),
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal("0.5"))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.GlobalSecondaryIndexReadHotPartitionThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")
      )
    }

    "apply table hot-partition limits to LSI-targeted reads" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "query-usecase" -> FixedQueryBehavior(8192L, SingleLogicalPartitionKey("hot-lsi"))
            ),
            stateModel = FixedTableState(5L, 8192L),
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal("0.5"))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.target } shouldBe Vector(
        DynamoDbTarget.Table("orders")
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
    }

    "admit a multi-partition query when demand is spread and throttle when logical-key collisions make one partition hot" in {
      val (distinctKeyA, distinctKeyB) = twoKeysForDifferentPartitions(partitionCount = 4)
      val (collidingKeyA, collidingKeyB) = twoKeysForSamePartition(partitionCount = 4)

      val admitted =
        Await.result(
          runStage(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "spread-query",
                target = DynamoDbReadTarget.Table("orders"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            TableAdmissionStage.Config(
              executionTarget = DynamoDbTarget.Table("orders"),
              admissionTarget = DynamoDbTarget.Table("orders"),
              useCaseBehaviors = Map(
                "spread-query" -> FixedQueryBehavior(
                  8192L,
                  MultipleLogicalPartitionKeys(Vector(distinctKeyA, distinctKeyB))
                )
              ),
              stateModel = FixedTableState(5L, 8192L),
              readConsistency = ReadConsistency.StronglyConsistent,
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1))
            )
          )._1,
          3.seconds
        )

      val throttledResponses =
        Await.result(
          runStage(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "colliding-query",
                target = DynamoDbReadTarget.Table("orders"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            TableAdmissionStage.Config(
              executionTarget = DynamoDbTarget.Table("orders"),
              admissionTarget = DynamoDbTarget.Table("orders"),
              useCaseBehaviors = Map(
                "colliding-query" -> FixedQueryBehavior(
                  8192L,
                  MultipleLogicalPartitionKeys(Vector(collidingKeyA, collidingKeyB))
                )
              ),
              stateModel = FixedTableState(5L, 8192L),
              readConsistency = ReadConsistency.StronglyConsistent,
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1))
            )
          )._2,
          3.seconds
        )

      admitted.collect { case _: AdmittedQuerySample => 1 } shouldBe Vector(1)
      throttledResponses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
    }

    "adaptively admit a hot read partition without spending burst when cooler partitions have unused headroom" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "cool-read"),
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read")
        )
      )

      val (_, responseFuture, metricFuture) =
        runStage(
          requests,
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "cool-read" -> FixedHitGetItemBehavior(2048L, SingleLogicalPartitionKey(coolKey)),
              "hot-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(hotKey))
            ),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
            adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(2))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.AdaptiveBacked
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.adaptiveConsumedRequestUnits } shouldBe Vector(
        BigDecimal(0),
        BigDecimal(1)
      )
    }

    "adaptively admit a hot write partition without spending burst" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          PutItemRequest(eventTime = SimTime.of(1L), usecase = "cool-write", itemBytes = 1024L),
          PutItemRequest(eventTime = SimTime.of(1L), usecase = "hot-write", itemBytes = 2048L)
        )
      )

      val (_, responseFuture, metricFuture) =
        runStage(
          requests,
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "cool-write" -> FixedPutItemBehavior(1024L, None, SingleLogicalPartitionKey(coolKey)),
              "hot-write" -> FixedPutItemBehavior(2048L, None, SingleLogicalPartitionKey(hotKey))
            ),
            stateModel = FixedTableState(0L, 0L),
            partitionCount = 4,
            maxWriteRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
            adaptiveMaxWriteRequestUnitsPerSecondPerPartition = Some(BigDecimal(2))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.AdaptiveBacked
      )
    }

    "use GSI-local adaptive relief for hot GSI reads and table adaptive relief for LSI reads" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val (_, gsiResponseFuture, gsiMetricFuture) =
        runStage(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "gsi-cool",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
              ),
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "gsi-hot",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
              )
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            admissionTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
            useCaseBehaviors = Map(
              "gsi-cool" -> FixedQueryBehavior(2048L, SingleLogicalPartitionKey(coolKey)),
              "gsi-hot" -> FixedQueryBehavior(8192L, SingleLogicalPartitionKey(hotKey))
            ),
            stateModel = FixedTableState(5L, 8192L),
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal("0.5")),
            adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1))
          )
        )

      val (_, lsiResponseFuture, lsiMetricFuture) =
        runStage(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "lsi-cool",
                target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              ),
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "lsi-hot",
                target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "lsi-cool" -> FixedQueryBehavior(2048L, SingleLogicalPartitionKey(coolKey)),
              "lsi-hot" -> FixedQueryBehavior(8192L, SingleLogicalPartitionKey(hotKey))
            ),
            stateModel = FixedTableState(5L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
            adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(2))
          )
        )

      val gsiResponses = Await.result(gsiResponseFuture, 3.seconds)
      val gsiMetrics = Await.result(gsiMetricFuture, 3.seconds)
      val lsiResponses = Await.result(lsiResponseFuture, 3.seconds)
      val lsiMetrics = Await.result(lsiMetricFuture, 3.seconds)

      gsiResponses shouldBe empty
      lsiResponses shouldBe empty
      gsiMetrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode }.last shouldBe AdmissionMode.AdaptiveBacked
      lsiMetrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode }.last shouldBe AdmissionMode.AdaptiveBacked
    }

    "not use adaptive capacity for pure whole-resource overage and combine adaptive relief with burst when needed" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val pureWholeResourceResponses =
        Await.result(
          runStage(
            Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "whole-only")),
            TableAdmissionStage.Config(
              executionTarget = DynamoDbTarget.Table("orders"),
              admissionTarget = DynamoDbTarget.Table("orders"),
              useCaseBehaviors = Map("whole-only" -> FixedHitGetItemBehavior(8192L)),
              stateModel = FixedTableState(1L, 8192L),
              readConsistency = ReadConsistency.StronglyConsistent,
              maxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(10)),
              adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(20))
            )
          )._2,
          3.seconds
        )

      val comboMetrics =
        Await.result(
          runStage(
            Source(
              Vector[TimedElement[DynamoDBRequest]](
                GetItemRequest(eventTime = SimTime.of(1L), usecase = "cool-for-combo"),
                GetItemRequest(eventTime = SimTime.of(1L), usecase = "combo-hot")
              )
            ),
            TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "cool-for-combo" -> FixedHitGetItemBehavior(1024L, SingleLogicalPartitionKey(coolKey)),
              "combo-hot" -> FixedHitGetItemBehavior(10240L, SingleLogicalPartitionKey(hotKey))
            ),
              stateModel = FixedTableState(1L, 10240L),
              readConsistency = ReadConsistency.StronglyConsistent,
              maxReadRequestUnitsPerSecond = Some(BigDecimal(3)),
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
              adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal("1.5")),
              burstRetentionWindowSeconds = Some(300),
              initialReadBurstRequestUnits = Some(BigDecimal(1))
            )
          )._3,
          3.seconds
        )

      pureWholeResourceResponses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
      comboMetrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.AdaptiveAndBurstBacked
      )
    }

    "burst-admit a request that exceeds steady-state read throughput and report burst usage" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
            burstRetentionWindowSeconds = Some(300),
            initialReadBurstRequestUnits = Some(BigDecimal(2))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted.collect { case _: AdmittedGetItemSample => 1 } shouldBe Vector(1)
      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.BurstBacked
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.burstConsumedRequestUnits } shouldBe Vector(
        BigDecimal(1)
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.burstRemainingRequestUnits } shouldBe Vector(
        BigDecimal(1)
      )
    }

    "throttle when the relevant burst reservoir is insufficient" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
            burstRetentionWindowSeconds = Some(300),
            initialReadBurstRequestUnits = Some(BigDecimal("0.5"))
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.burstAvailableRequestUnits } shouldBe Vector(
        BigDecimal("0.5")
      )
    }

    "burst-admit a hot partition request and preserve the hot-partition throttle reason when burst is insufficient" in {
      val rescuedMetrics =
        Await.result(
          runStage(
            Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "burst-hot")),
            TableAdmissionStage.Config(
              executionTarget = DynamoDbTarget.Table("orders"),
              admissionTarget = DynamoDbTarget.Table("orders"),
              useCaseBehaviors = Map(
                "burst-hot" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey("hot"))
              ),
              stateModel = FixedTableState(1L, 8192L),
              readConsistency = ReadConsistency.StronglyConsistent,
              maxReadRequestUnitsPerSecond = Some(BigDecimal(10)),
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
              burstRetentionWindowSeconds = Some(300),
              initialReadBurstRequestUnits = Some(BigDecimal(2))
            )
          )._3,
          3.seconds
        )

      val throttledResponses =
        Await.result(
          runStage(
            Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "throttle-hot")),
            TableAdmissionStage.Config(
              executionTarget = DynamoDbTarget.Table("orders"),
              admissionTarget = DynamoDbTarget.Table("orders"),
              useCaseBehaviors = Map(
                "throttle-hot" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey("hot"))
              ),
              stateModel = FixedTableState(1L, 8192L),
              readConsistency = ReadConsistency.StronglyConsistent,
              maxReadRequestUnitsPerSecond = Some(BigDecimal(10)),
              partitionCount = 4,
              maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
              burstRetentionWindowSeconds = Some(300),
              initialReadBurstRequestUnits = Some(BigDecimal("0.5"))
            )
          )._2,
          3.seconds
        )

      rescuedMetrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.BurstBacked
      )
      throttledResponses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
    }

    "replenish burst from unused steady-state capacity on later ticks" in {
      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "small-read"),
          GetItemRequest(eventTime = SimTime.of(2L), usecase = "large-read")
        )
      )

      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          requests,
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "small-read" -> FixedHitGetItemBehavior(4096L),
              "large-read" -> FixedHitGetItemBehavior(12288L)
            ),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(2)),
            burstRetentionWindowSeconds = Some(300),
            initialReadBurstRequestUnits = Some(BigDecimal(0))
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      admitted.collect { case _: AdmittedGetItemSample => 1 } shouldBe Vector(1, 1)
      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.BurstBacked
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.burstConsumedRequestUnits } shouldBe Vector(
        BigDecimal(0),
        BigDecimal(1)
      )
    }

    "grow topology at tick boundaries from throughput pressure and resolve later requests against the new topology" in {
      val movingKey = keyForPartition(partitionCount = 2, partitionId = 1)

      val (_, responseFuture, metricFuture) =
        runStage(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "moving-read"),
              GetItemRequest(eventTime = SimTime.of(2L), usecase = "moving-read")
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "moving-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(movingKey))
            ),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            dynamicPartitionTopologyConfig = Some(
              TableAdmissionStage.DynamicPartitionTopologyConfig(
                initialPartitionCount = 1,
                readThroughputGrowthSplitThresholdRequestUnitsPerSecond = Some(BigDecimal(1)),
                maxPartitionCount = Some(2)
              )
            )
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.TopologyChanged => (metric.reason, metric.previousPartitionCount, metric.newPartitionCount) } shouldBe
        Vector((TopologyChangeReason.ThroughputGrowth, 1, 2))
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.topologyPartitionCount } shouldBe Vector(1, 2)
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.resolvedPartitionFootprint.partitionDemandById.head._1 } shouldBe Vector(0, 1)
    }

    "grow topology from sustained heat only after the full sustain window" in {
      val hotKey = SingleLogicalPartitionKey("sustained-heat")

      val (_, _, metricFuture) =
        runStage(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read"),
              GetItemRequest(eventTime = SimTime.of(2L), usecase = "hot-read"),
              GetItemRequest(eventTime = SimTime.of(3L), usecase = "hot-read")
            )
          ),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("hot-read" -> FixedHitGetItemBehavior(8192L, hotKey)),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            dynamicPartitionTopologyConfig = Some(
              TableAdmissionStage.DynamicPartitionTopologyConfig(
                initialPartitionCount = 1,
                heatSplitSustainWindowSeconds = 2,
                readHeatSplitTriggerRequestUnitsPerSecondPerPartition = Some(BigDecimal(2)),
                maxPartitionCount = Some(2)
              )
            )
          )
        )

      val metrics = Await.result(metricFuture, 3.seconds)

      metrics.collect { case metric: AdmissionMetricEvent.TopologyChanged => metric.reason } shouldBe Vector(
        TopologyChangeReason.SustainedHeat
      )
      metrics.collect { case metric: AdmissionMetricEvent.TopologyChanged => metric.eventTime } shouldBe Vector(SimTime.of(3L))
    }

    "throttle a base-table write when an internal GSI write scope is over its max throughput" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "put-new" -> FixedPutItemBehavior(1024L, None, SingleLogicalPartitionKey("hot-gsi-write"))
            ),
            stateModel = FixedTableState(0L, 0L),
            maxWriteRequestUnitsPerSecond = Some(BigDecimal(10)),
            gsiWriteScopes = Vector(
              TableAdmissionStage.GsiWriteScopeConfig(
                target = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
                stateModel = FixedTableState(0L, 0L),
                maxWriteRequestUnitsPerSecond = Some(BigDecimal("0.5"))
              )
            )
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => (response.target, response.reason, response.dimension) } shouldBe Vector(
        (
          DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
          DynamoDbThrottleReason.GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded,
          DynamoDbThroughputDimension.Write
        )
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")
      )
    }

    "memorialize a precise index maintenance plan and skip GSI pressure for no-op maintenance" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(UpdateItemRequest(eventTime = SimTime.of(1L), usecase = "update-existing", itemBytes = 2048L)),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map(
              "update-existing" -> FixedUpdateItemBehavior(2048L, Some(1024L), SingleLogicalPartitionKey("same-key"))
            ),
            stateModel = FixedTableState(1L, 1024L),
            maxWriteRequestUnitsPerSecond = Some(BigDecimal(10)),
            indexMaintenanceTargets = Vector(
              TableAdmissionStage.IndexMaintenanceTargetConfig(
                target = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
                projection = DynamoDbTable.IndexProjection.KeysOnly
              ),
              TableAdmissionStage.IndexMaintenanceTargetConfig(
                target = DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index"),
                projection = DynamoDbTable.IndexProjection.All
              )
            ),
            gsiWriteScopes = Vector(
              TableAdmissionStage.GsiWriteScopeConfig(
                target = DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
                stateModel = FixedTableState(1L, 128L),
                maxWriteRequestUnitsPerSecond = Some(BigDecimal("0.5"))
              )
            )
          )
        )

      val admitted = Await.result(admittedFuture, 3.seconds)
      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricFuture, 3.seconds)

      val writeSample =
        admitted.collectFirst { case sample: AdmittedUpdateItemSample => sample }
          .getOrElse(fail("Expected admitted update sample"))
      val planByTarget = writeSample.indexMaintenancePlan.map(plan => plan.target -> plan).toMap

      responses shouldBe empty
      planByTarget(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")).action shouldBe IndexMaintenanceAction.NoOp
      planByTarget(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")).throughputDemand shouldBe BigDecimal(0)
      planByTarget(DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index")).action shouldBe IndexMaintenanceAction.ReplaceEntry
      planByTarget(DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index")).newIndexEntryBytes shouldBe Some(2048L)
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.indexMaintenanceSummary.size } shouldBe Vector(2)
    }

    "throttle at provisioned RCU ceiling with TableReadProvisionedThroughputExceeded reason" in {
      val (_, responseFuture, _) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
            billingMode = DynamoDbTable.BillingMode.Provisioned(
              readCapacityUnits = 1L,
              writeCapacityUnits = 1L
            )
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)

      responses.collect { case r: ThrottledResponse => r.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
      )
    }

    "throttle at provisioned WCU ceiling with TableWriteProvisionedThroughputExceeded reason" in {
      val (_, responseFuture, _) =
        runStage(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 4096L)),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("put-new" -> FixedPutItemBehavior(writtenItemBytes = 4096L, previousItemBytes = None)),
            stateModel = FixedTableState(0L, 0L),
            maxWriteRequestUnitsPerSecond = Some(BigDecimal(1)),
            billingMode = DynamoDbTable.BillingMode.Provisioned(
              readCapacityUnits = 1L,
              writeCapacityUnits = 1L
            )
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)

      responses.collect { case r: ThrottledResponse => r.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableWriteProvisionedThroughputExceeded
      )
    }

    "reject Config with adaptive capacity in provisioned billing mode" in {
      val error =
        the[IllegalArgumentException] thrownBy {
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map.empty,
            stateModel = FixedTableState(0L, 0L),
            maxReadRequestUnitsPerSecond = Some(BigDecimal(10)),
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(5)),
            partitionCount = 2,
            adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(8)),
            billingMode = DynamoDbTable.BillingMode.Provisioned(
              readCapacityUnits = 10L,
              writeCapacityUnits = 5L
            )
          )
        }

      error.getMessage should include("provisioned billing mode")
    }

    "hot partition throttle reason is unchanged in provisioned mode (hot-partition check fires before whole-resource)" in {
      val hotKey = "hot-partition-key"
      val (_, responseFuture, _) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read")),
          TableAdmissionStage.Config(
            executionTarget = DynamoDbTarget.Table("orders"),
            admissionTarget = DynamoDbTarget.Table("orders"),
            useCaseBehaviors = Map("hot-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(hotKey))),
            stateModel = FixedTableState(1L, 8192L),
            readConsistency = ReadConsistency.StronglyConsistent,
            maxReadRequestUnitsPerSecond = Some(BigDecimal(1000)),
            partitionCount = 4,
            maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
            billingMode = DynamoDbTable.BillingMode.Provisioned(
              readCapacityUnits = 1000L,
              writeCapacityUnits = 100L
            )
          )
        )

      val responses = Await.result(responseFuture, 3.seconds)

      responses.collect { case r: ThrottledResponse => r.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
    }

    "emit BillingModeSwitched metric when billing mode ref changes at tick boundary" in {
      val billingModeRef = new BillingModeRef(DynamoDbTable.BillingMode.OnDemand())
      billingModeRef.currentMode = DynamoDbTable.BillingMode.Provisioned(100L, 100L)

      val (_, _, metricFuture) = runStageWithBillingRef(
        Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get")),
        TableAdmissionStage.Config(
          executionTarget = DynamoDbTarget.Table("t"),
          admissionTarget = DynamoDbTarget.Table("t"),
          useCaseBehaviors = Map("get" -> FixedHitGetItemBehavior(1024L)),
          stateModel = FixedTableState(1L, 1024L),
          readConsistency = ReadConsistency.StronglyConsistent
        ),
        billingModeRef
      )

      val metrics = Await.result(metricFuture, 3.seconds)
      val switched = metrics.collect { case m: AdmissionMetricEvent.BillingModeSwitched => m }
      switched should have size 1
      switched.head.previousMode shouldBe DynamoDbTable.BillingMode.OnDemand()
      switched.head.newMode shouldBe DynamoDbTable.BillingMode.Provisioned(100L, 100L)
    }

    "use provisioned throttle reason after billing mode switch to provisioned" in {
      val billingModeRef = new BillingModeRef(DynamoDbTable.BillingMode.OnDemand())
      billingModeRef.currentMode = DynamoDbTable.BillingMode.Provisioned(1L, 1L)

      val (_, responseFuture, _) = runStageWithBillingRef(
        Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get")),
        TableAdmissionStage.Config(
          executionTarget = DynamoDbTarget.Table("t"),
          admissionTarget = DynamoDbTarget.Table("t"),
          useCaseBehaviors = Map("get" -> FixedHitGetItemBehavior(16384L)),
          stateModel = FixedTableState(1L, 16384L),
          readConsistency = ReadConsistency.StronglyConsistent
        ),
        billingModeRef
      )

      val responses = Await.result(responseFuture, 3.seconds)
      responses.collect { case t: ThrottledResponse => t.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
      )
    }

    "apply new capacity ceiling after billing mode switch" in {
      val billingModeRef = new BillingModeRef(DynamoDbTable.BillingMode.OnDemand())
      billingModeRef.currentMode = DynamoDbTable.BillingMode.Provisioned(3L, 3L)

      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "under-limit"),
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "over-limit")
        )
      )

      val (_, responseFuture, _) = runStageWithBillingRef(
        requests,
        TableAdmissionStage.Config(
          executionTarget = DynamoDbTarget.Table("t"),
          admissionTarget = DynamoDbTarget.Table("t"),
          useCaseBehaviors = Map(
            "under-limit" -> FixedHitGetItemBehavior(8192L),
            "over-limit" -> FixedHitGetItemBehavior(8192L)
          ),
          stateModel = FixedTableState(2L, 16384L),
          readConsistency = ReadConsistency.StronglyConsistent
        ),
        billingModeRef
      )

      val responses = Await.result(responseFuture, 3.seconds)
      val throttled = responses.collect { case t: ThrottledResponse => t }
      throttled should have size 1
      throttled.head.reason shouldBe DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
    }

    "suppress adaptive capacity after billing mode switch to provisioned" in {
      val (hotKey1, hotKey2) = twoKeysForSamePartition(4)
      val billingModeRef = new BillingModeRef(DynamoDbTable.BillingMode.OnDemand())
      billingModeRef.currentMode = DynamoDbTable.BillingMode.Provisioned(1000L, 100L)

      val (_, responseFuture, _) = runStageWithBillingRef(
        Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read")),
        TableAdmissionStage.Config(
          executionTarget = DynamoDbTarget.Table("t"),
          admissionTarget = DynamoDbTarget.Table("t"),
          useCaseBehaviors = Map("hot-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(hotKey1))),
          stateModel = FixedTableState(1L, 8192L),
          readConsistency = ReadConsistency.StronglyConsistent,
          partitionCount = 4,
          maxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(1)),
          adaptiveMaxReadRequestUnitsPerSecondPerPartition = Some(BigDecimal(100))
        ),
        billingModeRef
      )

      val responses = Await.result(responseFuture, 3.seconds)
      responses.collect { case t: ThrottledResponse => t.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
    }

    "restore on-demand throttle reason after billing mode switch back to on-demand" in {
      val billingModeRef = new BillingModeRef(DynamoDbTable.BillingMode.Provisioned(1L, 1L))
      billingModeRef.currentMode = DynamoDbTable.BillingMode.OnDemand(
        DynamoDbTable.OnDemandMaxThroughput(tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(1)))
      )

      val (_, responseFuture, _) = runStageWithBillingRef(
        Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get")),
        TableAdmissionStage.Config(
          executionTarget = DynamoDbTarget.Table("t"),
          admissionTarget = DynamoDbTarget.Table("t"),
          useCaseBehaviors = Map("get" -> FixedHitGetItemBehavior(16384L)),
          stateModel = FixedTableState(1L, 16384L),
          readConsistency = ReadConsistency.StronglyConsistent,
          billingMode = DynamoDbTable.BillingMode.Provisioned(1L, 1L)
        ),
        billingModeRef
      )

      val responses = Await.result(responseFuture, 3.seconds)
      responses.collect { case t: ThrottledResponse => t.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
    }
  }

  private def runStageWithBillingRef(
                                      requestSource: Source[TimedElement[DynamoDBRequest], ?],
                                      config: TableAdmissionStage.Config,
                                      billingModeRef: BillingModeRef
                                    ): (Future[Seq[TimedEvent]], Future[Seq[TimedEvent]], Future[Seq[TimedEvent]]) =
    val admittedSink = Sink.seq[TimedEvent]
    val responseSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(admittedSink, responseSink, metricsSink)((a, r, m) => (a, r, m)) { implicit b =>
        (admSink, respSink, metrSink) =>
          import GraphDSL.Implicits.*

          val stage = b.add(TableAdmissionStage.componentOf(config, Some(billingModeRef)))

          requestSource ~> stage.in
          stage.out0 ~> admSink
          stage.out1 ~> respSink
          stage.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private def runStage(
                        requestSource: Source[TimedElement[DynamoDBRequest], ?],
                        config: TableAdmissionStage.Config
                      ): (Future[Seq[TimedEvent]], Future[Seq[TimedEvent]], Future[Seq[TimedEvent]]) =
    val admittedSink = Sink.seq[TimedEvent]
    val responseSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(admittedSink, responseSink, metricsSink)((a, r, m) => (a, r, m)) { implicit b =>
        (admSink, respSink, metrSink) =>
          import GraphDSL.Implicits.*

          val stage = b.add(TableAdmissionStage.componentOf(config))

          requestSource ~> stage.in
          stage.out0 ~> admSink
          stage.out1 ~> respSink
          stage.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long],
                                         override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-put")
                                       ) extends PutItemSample

  private case class FixedUpdateItemSample(
                                            override val writtenItemBytes: Long,
                                            override val previousItemBytes: Option[Long],
                                            override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-update")
                                          ) extends UpdateItemSample

  private case class FixedHitGetItemBehavior(
                                              bytes: Long,
                                              logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-get")
                                            ) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = Some(bytes), logicalPartitionAccess = logicalPartitionAccess)

  private case class FixedPutItemBehavior(
                                           writtenItemBytes: Long,
                                           previousItemBytes: Option[Long],
                                           logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-put")
                                         ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes, logicalPartitionAccess)

  private case class FixedUpdateItemBehavior(
                                              writtenItemBytes: Long,
                                              previousItemBytes: Option[Long],
                                              logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-update")
                                            ) extends UseCaseSampler[TableState]:
    override def updateItem(request: UpdateItemRequest, ctx: SamplerContext[TableState]): UpdateItemSample =
      FixedUpdateItemSample(writtenItemBytes, previousItemBytes, logicalPartitionAccess)

  private case class FixedQueryBehavior(
                                         evaluatedBytes: Long,
                                         logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-query")
                                       ) extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(
        evaluatedItemCount = 4L,
        evaluatedBytes = evaluatedBytes,
        returnedItemCount = 1L,
        returnedBytes = 512L,
        logicalPartitionAccess = logicalPartitionAccess
      )

  private case class FixedScanBehavior(evaluatedBytes: Long) extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 6L,
        evaluatedBytes = evaluatedBytes,
        returnedItemCount = 2L,
        returnedBytes = 1024L
      )

  private def twoKeysForSamePartition(partitionCount: Int): (String, String) =
    val grouped =
      (0 until 10_000)
        .map(i => s"key-$i")
        .groupBy { token =>
          PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1
        }

    grouped.values.collectFirst {
      case tokens if tokens.size >= 2 => (tokens(0), tokens(1))
    }.getOrElse(fail("Unable to find colliding partition keys"))

  private def twoKeysForDifferentPartitions(partitionCount: Int): (String, String) =
    val keysByPartition =
      (0 until 10_000)
        .map(i => s"key-$i")
        .groupBy { token =>
          PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1
        }
        .toVector

    if keysByPartition.size < 2 then fail("Unable to find keys for different partitions")
    else (keysByPartition(0)._2.head, keysByPartition(1)._2.head)

  private def keyForPartition(partitionCount: Int, partitionId: Int): String =
    (0 until 10_000)
      .map(i => s"partition-key-$i")
      .find { token =>
        PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1 == partitionId
      }
      .getOrElse(fail(s"Unable to find key for partition $partitionId with partitionCount=$partitionCount"))
