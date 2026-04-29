package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class DynamoDbTableComponentSpec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  given ActorSystem = ActorSystem("dynamodb-table-component-test")
  given Materializer = Materializer.matFromSystem

  "DynamoDbTable component" should {
    "preserve current GetItem behavior for a table-only configuration" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
          useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(512L)),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source((1 to 3).map(i => GetItemRequest(eventTime = SimTime.of(i.toLong), usecase = "get-hit"))),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case r: GetItemResponse => r } shouldBe Vector(
        GetItemResponse(SimTime.of(1L), "get-hit", itemFound = true, itemBytes = Some(512L)),
        GetItemResponse(SimTime.of(2L), "get-hit", itemFound = true, itemBytes = Some(512L)),
        GetItemResponse(SimTime.of(3L), "get-hit", itemFound = true, itemBytes = Some(512L))
      )

      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units }.sum shouldBe BigDecimal(3)
      resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes }.sum shouldBe 1536L
      resources.collect { case evt: DynamoDbConsumptionEvent => evt.target }.toSet shouldBe Set(DynamoDbTarget.Table("orders"))

      metrics.collect { case _: StorageMetricEvent.GetItemObserved => 1 }.size shouldBe 3
      metrics.collect { case StorageMetricEvent.GetItemReturned(_, _, bytes) => bytes }.sum shouldBe 1536L
    }

    "preserve current PutItem behavior for a table-only configuration" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map("put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L, previousItemBytes = None)),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case r: PutItemResponse => r } shouldBe Vector(
        PutItemResponse(
          eventTime = SimTime.of(1L),
          usecase = "put-new",
          storedItemBytes = 1024L,
          createdNewItem = true,
          previousItemBytes = None
        )
      )

      resources.collect { case evt: DynamoDbConsumptionEvent.WriteCapacityConsumed => evt.units }.sum shouldBe BigDecimal(1)
      resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesWritten => evt.bytes }.sum shouldBe 1024L
      resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesDelta => evt.bytesDelta }.sum shouldBe 1024L
      resources.collect { case evt: DynamoDbConsumptionEvent => evt.target }.toSet shouldBe Set(DynamoDbTarget.Table("orders"))

      metrics.collect { case _: StorageMetricEvent.PutItemObserved => 1 }.size shouldBe 1
      metrics.collect { case StorageMetricEvent.TableBytesChanged(_, _, delta) => delta }.sum shouldBe 1024L
    }

    "propagate successful writes into configured index state and emit index-targeted write consumption" in {
      val statusIndexState = FixedTableState(itemCount = 0L, totalItemBytes = 0L)
      val createdAtIndexState = FixedTableState(itemCount = 0L, totalItemBytes = 0L)

      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map("put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L, previousItemBytes = None)),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = statusIndexState)
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = createdAtIndexState)
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case r: PutItemResponse => r } should have size 1

      statusIndexState.itemCount shouldBe 1L
      statusIndexState.totalItemBytes shouldBe 1024L
      createdAtIndexState.itemCount shouldBe 1L
      createdAtIndexState.totalItemBytes shouldBe 1024L

      val writeCapacityByTarget =
        resources.collect { case evt: DynamoDbConsumptionEvent.WriteCapacityConsumed => evt.target -> evt.units }.groupMapReduce(_._1)(_._2)(_ + _)
      writeCapacityByTarget shouldBe Map(
        DynamoDbTarget.Table("orders") -> BigDecimal(1),
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> BigDecimal(1),
        DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> BigDecimal(1)
      )

      val deltaByTarget =
        resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesDelta => evt.target -> evt.bytesDelta }.groupMapReduce(_._1)(_._2)(_ + _)
      deltaByTarget shouldBe Map(
        DynamoDbTarget.Table("orders") -> 1024L,
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 1024L,
        DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 1024L
      )

      metrics.collect { case _: StorageMetricEvent.PutItemObserved => 1 }.size shouldBe 1
    }

    "apply projection-sized downstream index maintenance for inserted entries" in {
      val statusIndexState = FixedTableState(itemCount = 0L, totalItemBytes = 0L)
      val createdAtIndexState = FixedTableState(itemCount = 0L, totalItemBytes = 0L)

      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map("put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L, previousItemBytes = None)),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition(
              "status-index",
              stateModel = statusIndexState,
              projection = DynamoDbTable.IndexProjection.Include(256L)
            )
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition(
              "created-at-index",
              stateModel = createdAtIndexState,
              projection = DynamoDbTable.IndexProjection.KeysOnly
            )
          )
        )

      val (_, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
          config
        )

      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      statusIndexState.totalItemBytes shouldBe 384L
      createdAtIndexState.totalItemBytes shouldBe 128L

      resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesWritten => evt.target -> evt.bytes }.toSet shouldBe Set(
        DynamoDbTarget.Table("orders") -> 1024L,
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 384L,
        DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 128L
      )
      metrics.collect { case evt: StorageMetricEvent.IndexEntryInserted => evt.target -> evt.bytes }.toSet shouldBe Set(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 384L,
        DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 128L
      )
    }

    "propagate update and delete write effects into configured index state" in {
      val statusIndexState = FixedTableState(itemCount = 1L, totalItemBytes = 512L)
      val createdAtIndexState = FixedTableState(itemCount = 1L, totalItemBytes = 512L)

      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
          useCaseBehaviors = Map(
            "update-existing" -> FixedUpdateItemBehavior(writtenItemBytes = 768L, previousItemBytes = Some(512L)),
            "delete-existing" -> FixedDeleteItemBehavior(deletedItemBytes = Some(768L))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = statusIndexState)
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = createdAtIndexState)
          )
        )

      val (_, resourceFuture, _) =
        runComponent(
          Source(
            Seq[TimedElement[DynamoDBRequest]](
              UpdateItemRequest(eventTime = SimTime.of(1L), usecase = "update-existing", itemBytes = 768L),
              DeleteItemRequest(eventTime = SimTime.of(2L), usecase = "delete-existing")
            )
          ),
          config
        )

      val resources = Await.result(resourceFuture, 3.seconds)

      statusIndexState.itemCount shouldBe 0L
      statusIndexState.totalItemBytes shouldBe 0L
      createdAtIndexState.itemCount shouldBe 0L
      createdAtIndexState.totalItemBytes shouldBe 0L

      val deletedBytesByTarget =
        resources.collect { case evt: DynamoDbConsumptionEvent.StorageBytesDeleted => evt.target -> evt.bytes }.groupMapReduce(_._1)(_._2)(_ + _)
      deletedBytesByTarget shouldBe Map(
        DynamoDbTarget.Table("orders") -> 768L,
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 768L,
        DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 768L
      )
    }

    "skip downstream index mutation and write consumption for no-op maintenance entries" in {
      val statusIndexState = FixedTableState(itemCount = 1L, totalItemBytes = 128L)

      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
          useCaseBehaviors = Map(
            "update-existing" -> FixedUpdateItemBehavior(writtenItemBytes = 2048L, previousItemBytes = Some(1024L))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition(
              "status-index",
              stateModel = statusIndexState,
              projection = DynamoDbTable.IndexProjection.KeysOnly
            )
          )
        )

      val (_, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(UpdateItemRequest(eventTime = SimTime.of(1L), usecase = "update-existing", itemBytes = 2048L)),
          config
        )

      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      statusIndexState.itemCount shouldBe 1L
      statusIndexState.totalItemBytes shouldBe 128L
      resources.collect { case evt: DynamoDbConsumptionEvent.WriteCapacityConsumed if evt.target == DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") => evt.units } shouldBe empty
      metrics.collect { case evt: StorageMetricEvent.IndexEntryUnchanged => evt.target } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")
      )
    }

    "not propagate read-only requests into index state" in {
      val statusIndexState = FixedTableState(itemCount = 2L, totalItemBytes = 400L)
      val createdAtIndexState = FixedTableState(itemCount = 3L, totalItemBytes = 900L)

      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 256L),
          useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(256L)),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = statusIndexState)
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = createdAtIndexState)
          )
        )

      val (_, resourceFuture, _) =
        runComponent(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          config
        )

      val resources = Await.result(resourceFuture, 3.seconds)

      statusIndexState.itemCount shouldBe 2L
      statusIndexState.totalItemBytes shouldBe 400L
      createdAtIndexState.itemCount shouldBe 3L
      createdAtIndexState.totalItemBytes shouldBe 900L

      resources.collect { case evt: DynamoDbConsumptionEvent => evt.target }.toSet shouldBe Set(DynamoDbTarget.Table("orders"))
    }

    "execute table-targeted Query requests through the base-table path" in {
      val config = readCapableIndexedConfig()

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: QueryResponse => response } shouldBe Vector(
        QueryResponse(
          eventTime = SimTime.of(1L),
          usecase = "query-usecase",
          target = DynamoDbReadTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent,
          evaluatedItemCount = 8L,
          evaluatedBytes = 4096L,
          returnedItemCount = 2L,
          returnedBytes = 1024L
        )
      )

      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.target -> evt.units } should contain
        (DynamoDbTarget.Table("orders") -> BigDecimal(1))
      metrics.collect { case evt: StorageMetricEvent.QueryObserved => evt.target } should contain(DynamoDbReadTarget.Table("orders"))
    }

    "execute GSI and LSI Query requests against internal index state" in {
      val config = readCapableIndexedConfig()

      val gsiResponses =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
              )
            ),
            config
          )._1,
          3.seconds
        )

      val lsiResponses =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(2L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            config
          )._1,
          3.seconds
        )

      gsiResponses.collect { case response: QueryResponse => response.target } shouldBe Vector(
        DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
      )
      lsiResponses.collect { case response: QueryResponse => response.target } shouldBe Vector(
        DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index")
      )
    }

    "apply projection-aware behavior to GSI and LSI Query requests" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
          useCaseBehaviors = Map(
            "gsi-projection-query" -> ProjectionLimitedQueryBehavior,
            "lsi-projection-query" -> ProjectionFetchQueryBehavior
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition(
              "status-index",
              stateModel = FixedTableState(4L, 4096L),
              projection = DynamoDbTable.IndexProjection.Include(256L)
            )
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition(
              "created-at-index",
              stateModel = FixedTableState(5L, 5120L),
              projection = DynamoDbTable.IndexProjection.KeysOnly
            )
          )
        )

      val (gsiResponsesF, gsiResourcesF, gsiMetricsF) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "gsi-projection-query",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          config
        )
      val (lsiResponsesF, lsiResourcesF, lsiMetricsF) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(2L),
              usecase = "lsi-projection-query",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          config
        )

      Await.result(gsiResponsesF, 3.seconds).collect { case response: QueryResponse => response.returnedBytes } shouldBe Vector(512L)
      Await.result(gsiResourcesF, 3.seconds).collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.target -> evt.bytes } shouldBe
        Vector(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 4096L)
      Await.result(gsiMetricsF, 3.seconds).collect { case _: StorageMetricEvent.QueryUsedIndexOnly => 1 } shouldBe Vector(1)

      Await.result(lsiResponsesF, 3.seconds).collect { case response: QueryResponse => response.returnedBytes } shouldBe Vector(1536L)
      Await.result(lsiResourcesF, 3.seconds).collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.target -> evt.bytes } shouldBe
        Vector(
          DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 3072L,
          DynamoDbTarget.Table("orders") -> 1024L
        )
      Await.result(lsiMetricsF, 3.seconds).collect { case evt: StorageMetricEvent.QueryFetchedFromBaseTable => evt.bytes } shouldBe Vector(1024L)
    }

    "reject strongly consistent GSI Query requests" in {
      val config = readCapableIndexedConfig()

      val responseError =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            config
          )._1.failed,
          3.seconds
        )

      responseError.getMessage should include("Strongly consistent Query is not supported for global secondary index 'status-index'")
    }

    "execute table-targeted Scan requests through the base-table path" in {
      val config = readCapableIndexedConfig()

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "scan-usecase",
              target = DynamoDbReadTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: ScanResponse => response } shouldBe Vector(
        ScanResponse(
          eventTime = SimTime.of(1L),
          usecase = "scan-usecase",
          target = DynamoDbReadTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent,
          evaluatedItemCount = 14L,
          evaluatedBytes = 8192L,
          returnedItemCount = 3L,
          returnedBytes = 1536L
        )
      )

      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.target -> evt.units } should contain
        (DynamoDbTarget.Table("orders") -> BigDecimal(2))
      metrics.collect { case evt: StorageMetricEvent.ScanObserved => evt.target } should contain(DynamoDbReadTarget.Table("orders"))
    }

    "execute GSI and LSI Scan requests against internal index state" in {
      val config = readCapableIndexedConfig()

      val gsiResponses =
        Await.result(
          runComponent(
            Source.single(
              ScanRequest(
                eventTime = SimTime.of(1L),
                usecase = "scan-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
              )
            ),
            config
          )._1,
          3.seconds
        )

      val lsiResponses =
        Await.result(
          runComponent(
            Source.single(
              ScanRequest(
                eventTime = SimTime.of(2L),
                usecase = "scan-usecase",
                target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            config
          )._1,
          3.seconds
        )

      gsiResponses.collect { case response: ScanResponse => response.target } shouldBe Vector(
        DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
      )
      lsiResponses.collect { case response: ScanResponse => response.target } shouldBe Vector(
        DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index")
      )
    }

    "apply projection-aware behavior to GSI and LSI Scan requests" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
          useCaseBehaviors = Map(
            "gsi-projection-scan" -> ProjectionLimitedScanBehavior,
            "lsi-projection-scan" -> ProjectionFetchScanBehavior
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition(
              "status-index",
              stateModel = FixedTableState(4L, 4096L),
              projection = DynamoDbTable.IndexProjection.Include(128L)
            )
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition(
              "created-at-index",
              stateModel = FixedTableState(5L, 5120L),
              projection = DynamoDbTable.IndexProjection.KeysOnly
            )
          )
        )

      val (gsiResponsesF, gsiResourcesF, gsiMetricsF) =
        runComponent(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "gsi-projection-scan",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          config
        )
      val (lsiResponsesF, lsiResourcesF, lsiMetricsF) =
        runComponent(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(2L),
              usecase = "lsi-projection-scan",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          config
        )

      Await.result(gsiResponsesF, 3.seconds).collect { case response: ScanResponse => response.returnedBytes } shouldBe Vector(768L)
      Await.result(gsiResourcesF, 3.seconds).collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.target -> evt.bytes } shouldBe
        Vector(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 6144L)
      Await.result(gsiMetricsF, 3.seconds).collect { case _: StorageMetricEvent.ScanUsedIndexOnly => 1 } shouldBe Vector(1)

      Await.result(lsiResponsesF, 3.seconds).collect { case response: ScanResponse => response.returnedBytes } shouldBe Vector(3072L)
      Await.result(lsiResourcesF, 3.seconds).collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.target -> evt.bytes } shouldBe
        Vector(
          DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 8192L,
          DynamoDbTarget.Table("orders") -> 2048L
        )
      Await.result(lsiMetricsF, 3.seconds).collect { case evt: StorageMetricEvent.ScanFetchedFromBaseTable => evt.bytes } shouldBe Vector(2048L)
    }

    "reject strongly consistent GSI Scan requests" in {
      val config = readCapableIndexedConfig()

      val responseError =
        Await.result(
          runComponent(
            Source.single(
              ScanRequest(
                eventTime = SimTime.of(1L),
                usecase = "scan-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
                readConsistency = ReadConsistency.StronglyConsistent
              )
            ),
            config
          )._1.failed,
          3.seconds
        )

      responseError.getMessage should include("Strongly consistent Scan is not supported for global secondary index 'status-index'")
    }

    "fail fast for mismatched table names, unknown indexes, and wrong target kinds" in {
      val config = indexedConfig()

      val mismatchedTableError =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.Table("customers")
              )
            ),
            config
          )._1.failed,
          3.seconds
        )
      mismatchedTableError.getMessage should include("Read target table 'customers' does not match configured table 'orders'")

      val unknownGlobalSecondaryIndexError =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "missing-index")
              )
            ),
            config
          )._1.failed,
          3.seconds
        )
      unknownGlobalSecondaryIndexError.getMessage should include("Unknown global secondary index 'missing-index' for table 'orders'")

      val wrongTargetKindError =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "created-at-index")
              )
            ),
            config
          )._1.failed,
          3.seconds
        )
      wrongTargetKindError.getMessage should include(
        "Read target 'created-at-index' is configured as a local secondary index, not a global secondary index"
      )
    }

    "preserve control events through the composed graph for table-only configurations" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 256L),
          useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(256L)),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      val requests: Vector[TimedElement[DynamoDBRequest]] = Vector(
        TimedControlEvent.Tick(SimTime.of(1L)),
        GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit"),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.EndOfTime
      )

      val (responseFuture, resourceFuture, metricsFuture) = runComponent(Source(requests), config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L), SimTime.of(2L))
      responses.last shouldBe TimedControlEvent.EndOfTime

      resources.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L), SimTime.of(2L))
      resources.last shouldBe TimedControlEvent.EndOfTime

      metrics.collect { case tick: TimedControlEvent.Tick => tick.eventTime } shouldBe Vector(SimTime.of(1L), SimTime.of(2L))
      metrics.last shouldBe TimedControlEvent.EndOfTime
    }

    "throttle base-table reads when they exceed the configured on-demand read hard check" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 8192L),
          useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
          readConsistency = ReadConsistency.StronglyConsistent,
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          ))
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")), config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
      resources.collect { case _: DynamoDbConsumptionEvent => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent => 1 } shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.Table("orders")
      )
    }

    "throttle GSI reads against GSI max throughput and charge LSI reads against the base table" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
          useCaseBehaviors = Map(
            "query-usecase" -> FixedQueryBehavior(),
            "scan-usecase" -> FixedScanBehavior
          ),
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(4L, 4096L))
          ),
          localSecondaryIndexes = Vector(
            DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = FixedTableState(5L, 5120L))
          ),
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal("0.5")),
            globalSecondaryIndexMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal("0.25"))
          ))
        )

      val gsiResponses =
        Await.result(
          runComponent(
            Source.single(
              QueryRequest(
                eventTime = SimTime.of(1L),
                usecase = "query-usecase",
                target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
              )
            ),
            config
          )._1,
          3.seconds
        )

      val (lsiResponseFuture, _, lsiMetricFuture) =
        runComponent(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "scan-usecase",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.EventuallyConsistent
            )
          ),
          config
        )

      val lsiResponses = Await.result(lsiResponseFuture, 3.seconds)
      val lsiMetrics = Await.result(lsiMetricFuture, 3.seconds)

      gsiResponses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.GlobalSecondaryIndexReadMaxOnDemandThroughputExceeded
      )
      lsiResponses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded
      )
      lsiMetrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.Table("orders")
      )
    }

    "burst-admit base-table reads through the composed table path" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 8192L),
          useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(8192L)),
          readConsistency = ReadConsistency.StronglyConsistent,
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
          )),
          burstCapacityModel = Some(
            DynamoDbTable.BurstCapacityModel(
              initialTableReadBurstRequestUnits = Some(BigDecimal(2))
            )
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")), config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: GetItemResponse => response.itemFound } shouldBe Vector(true)
      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(2))
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.BurstBacked
      )
      metrics.collect { case StorageMetricEvent.GetItemObserved(_, _) => 1 } shouldBe Vector(1)
    }

    "use the selected GSI burst reservoir for GSI read admission" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
          useCaseBehaviors = Map(
            "query-usecase" -> FixedQueryBehavior(8192L)
          ),
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(4L, 4096L))
          ),
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            globalSecondaryIndexMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal("0.5"))
          )),
          burstCapacityModel = Some(
            DynamoDbTable.BurstCapacityModel(
              initialGlobalSecondaryIndexReadBurstRequestUnits = Map("status-index" -> BigDecimal("0.5"))
            )
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: QueryResponse => response.target } shouldBe Vector(
        DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
      )
      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.target -> evt.units } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> BigDecimal(1)
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.burstConsumedRequestUnits } shouldBe Vector(
        BigDecimal("0.5")
      )
    }

    "adaptively admit base-table reads through the composed table path without spending burst" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 8192L),
          useCaseBehaviors = Map(
            "cool-read" -> FixedHitGetItemBehavior(2048L, SingleLogicalPartitionKey(coolKey)),
            "hot-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(hotKey))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 4,
              tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
            )
          ),
          adaptiveCapacityModel = Some(
            DynamoDbTable.AdaptiveCapacityModel(
              tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Some(BigDecimal(2))
            )
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "cool-read"),
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read")
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case _: GetItemResponse => 1 } shouldBe Vector(1, 1)
      resources.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units }.sum shouldBe BigDecimal(3)
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.AdaptiveBacked
      )
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.burstConsumedRequestUnits } shouldBe Vector(
        BigDecimal(0),
        BigDecimal(0)
      )
    }

    "combine adaptive relief and burst for routed reads when both are needed" in {
      val (coolKey, hotKey) = twoKeysForDifferentPartitions(partitionCount = 4)
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 10240L),
          useCaseBehaviors = Map(
            "cool-read" -> FixedHitGetItemBehavior(1024L, SingleLogicalPartitionKey(coolKey)),
            "hot-read" -> FixedHitGetItemBehavior(10240L, SingleLogicalPartitionKey(hotKey))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(3))
          )),
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 4,
              tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
            )
          ),
          adaptiveCapacityModel = Some(
            DynamoDbTable.AdaptiveCapacityModel(
              tablePerPartitionAdaptiveMaxReadRequestUnitsPerSecond = Some(BigDecimal("1.5"))
            )
          ),
          burstCapacityModel = Some(
            DynamoDbTable.BurstCapacityModel(
              initialTableReadBurstRequestUnits = Some(BigDecimal(1))
            )
          )
        )

      val (responseFuture, _, metricsFuture) =
        runComponent(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "cool-read"),
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "hot-read")
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case _: GetItemResponse => 1 } shouldBe Vector(1, 1)
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.admissionMode } shouldBe Vector(
        AdmissionMode.Normal,
        AdmissionMode.AdaptiveAndBurstBacked
      )
    }

    "sample admitted requests once and carry that sampled outcome through storage execution" in {
      val invocationCount = AtomicInteger(0)
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map(
            "put-sampled-once" -> SamplingPutBehavior(invocationCount)
          ),
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal("1.5"))
          ))
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-sampled-once", itemBytes = 1024L)),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      invocationCount.get() shouldBe 1
      responses.collect { case response: PutItemResponse => response.storedItemBytes } shouldBe Vector(1024L)
      resources.collect { case evt: DynamoDbConsumptionEvent.WriteCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(1))
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.throughputDemand } shouldBe Vector(BigDecimal(1))
      metrics.collect { case StorageMetricEvent.TableBytesChanged(_, _, delta) => delta } shouldBe Vector(1024L)
    }

    "merge throttled admission responses with admitted storage responses in one public stream" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map(
            "get-hit" -> FixedHitGetItemBehavior(8192L),
            "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L, previousItemBytes = None)
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxReadRequestUnitsPerSecond = Some(BigDecimal(1)),
            tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal(1))
          ))
        )

      val requests = Source(
        Vector[TimedElement[DynamoDBRequest]](
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit"),
          PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
        )
      )

      val (responseFuture, resourceFuture, metricsFuture) = runComponent(requests, config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case _: ThrottledResponse => 1 } shouldBe Vector(1)
      responses.collect { case response: PutItemResponse => response.storedItemBytes } shouldBe Vector(1024L)
      resources.collect { case evt: DynamoDbConsumptionEvent.WriteCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(1))
      metrics.collect { case _: AdmissionMetricEvent.RequestThrottled => 1 } shouldBe Vector(1)
      metrics.collect { case _: AdmissionMetricEvent.RequestAdmitted => 1 } shouldBe Vector(1)
      metrics.collect { case _: StorageMetricEvent.PutItemObserved => 1 } shouldBe Vector(1)
    }

    "throttle a base-table read for a hot partition without emitting storage-side outputs" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 8192L),
          useCaseBehaviors = Map(
            "get-hot" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey("hot-key"))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 4,
              tablePerPartitionMaxReadRequestUnitsPerSecond = Some(BigDecimal(1))
            )
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hot")), config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.TableReadHotPartitionThroughputExceeded
      )
      resources.collect { case _: DynamoDbConsumptionEvent => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent => 1 } shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.resolvedPartitionFootprint.partitionDemandById.values.sum } shouldBe Vector(BigDecimal(2))
    }

    "apply configured GSI partition topology and limits to GSI reads" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
          useCaseBehaviors = Map(
            "query-usecase" -> FixedQueryBehavior(8192L, SingleLogicalPartitionKey("hot-gsi"))
          ),
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(4L, 4096L))
          ),
          hotPartitionModel = Some(
            DynamoDbTable.HotPartitionModel(
              tablePartitionCount = 8,
              globalSecondaryIndexPartitionCounts = Map("status-index" -> 2),
              globalSecondaryIndexPerPartitionMaxReadRequestUnitsPerSecond = Map("status-index" -> BigDecimal("0.5"))
            )
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index")
            )
          ),
          config
        )

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => response.reason } shouldBe Vector(
        DynamoDbThrottleReason.GlobalSecondaryIndexReadHotPartitionThroughputExceeded
      )
      resources.collect { case _: DynamoDbConsumptionEvent => 1 } shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.resolvedPartitionFootprint.totalPartitionCount } shouldBe Vector(2)
    }

    "evolve table topology over time and expose topology-change metrics through the composed table component" in {
      val movingKey = keyForPartition(partitionCount = 2, partitionId = 1)
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 8192L),
          useCaseBehaviors = Map(
            "moving-read" -> FixedHitGetItemBehavior(8192L, SingleLogicalPartitionKey(movingKey))
          ),
          readConsistency = ReadConsistency.StronglyConsistent,
          dynamicPartitionTopologyModel = Some(
            DynamoDbTable.DynamicPartitionTopologyModel(
              tableInitialPartitionCount = 1,
              tableThroughputGrowthSplitThresholdRequestUnitsPerSecond = Some(BigDecimal(1)),
              maxTablePartitionCount = Some(2)
            )
          )
        )

      val (_, resourceFuture, metricsFuture) =
        runComponent(
          Source(
            Vector[TimedElement[DynamoDBRequest]](
              GetItemRequest(eventTime = SimTime.of(1L), usecase = "moving-read"),
              GetItemRequest(eventTime = SimTime.of(2L), usecase = "moving-read")
            )
          ),
          config
        )

      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      resources.collect { case _: DynamoDbConsumptionEvent => 1 } should not be empty
      metrics.collect { case metric: AdmissionMetricEvent.TopologyChanged => (metric.reason, metric.previousPartitionCount, metric.newPartitionCount) } shouldBe
        Vector((TopologyChangeReason.ThroughputGrowth, 1, 2))
      metrics.collect { case metric: AdmissionMetricEvent.RequestAdmitted => metric.topologyPartitionCount } shouldBe Vector(1, 2)
    }

    "throttle a base-table write when GSI write back-pressure blocks internal propagation" in {
      val config =
        DynamoDbTable.Config(
          tableName = "orders",
          stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          useCaseBehaviors = Map(
            "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L, previousItemBytes = None, logicalPartitionAccess = SingleLogicalPartitionKey("hot-gsi-write"))
          ),
          billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
            tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal(10)),
            globalSecondaryIndexMaxWriteRequestUnitsPerSecond = Map("status-index" -> BigDecimal("0.5"))
          )),
          globalSecondaryIndexes = Vector(
            DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(0L, 0L))
          )
        )

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)), config)

      val responses = Await.result(responseFuture, 3.seconds)
      val resources = Await.result(resourceFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case response: ThrottledResponse => (response.target, response.reason) } shouldBe Vector(
        (
          DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index"),
          DynamoDbThrottleReason.GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded
        )
      )
      resources.collect { case _: DynamoDbConsumptionEvent => 1 } shouldBe empty
      metrics.collect { case metric: AdmissionMetricEvent.RequestThrottled => metric.target } shouldBe Vector(
        DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index")
      )
    }

    "componentOfManaged emits BillingModeSwitched metric and changes admission behavior on SwitchBillingMode event" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 5120L),
        useCaseBehaviors = Map("get" -> FixedHitGetItemBehavior(5120L)),
        readConsistency = ReadConsistency.StronglyConsistent
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick50 = TimedControlEvent.Tick(SimTime.of(50L))

      val switchEvent = DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(10L),
        usecase = "switch",
        newMode = DynamoDbTable.BillingMode.Provisioned(1L, 1L)
      )

      val (responseFuture, _, metricsFuture) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](
          tick1,
          GetItemRequest(eventTime = SimTime.of(1L), usecase = "get"),
          tick50,
          GetItemRequest(eventTime = SimTime.of(50L), usecase = "get")
        )),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          tick1,
          switchEvent,
          tick50
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val metrics = Await.result(metricsFuture, 5.seconds)

      val switched = metrics.collect { case m: AdmissionMetricEvent.BillingModeSwitched => m }
      switched should have size 1
      switched.head.newMode shouldBe DynamoDbTable.BillingMode.Provisioned(1L, 1L)

      val throttled = responses.collect { case t: ThrottledResponse => t }
      throttled should have size 1
      throttled.head.reason shouldBe DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
    }

    "componentOfManaged rejects billing mode switch within 24-hour cooldown" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
        useCaseBehaviors = Map.empty,
        readConsistency = ReadConsistency.StronglyConsistent
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick100 = TimedControlEvent.Tick(SimTime.of(100L))

      val firstSwitch = DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(1L),
        usecase = "switch-1",
        newMode = DynamoDbTable.BillingMode.Provisioned(10L, 10L)
      )
      val secondSwitch = DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(100L),
        usecase = "switch-2",
        newMode = DynamoDbTable.BillingMode.OnDemand()
      )

      val (responseFuture, _, _) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](tick1, tick100)),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          firstSwitch, secondSwitch
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val rejections = responses.collect { case r: ReconfigurationRejectedResponse => r }
      rejections should have size 1
      rejections.head.usecase shouldBe "switch-2"
    }

    "componentOfManaged propagates billing mode switch to GSI admission branch" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
        useCaseBehaviors = Map(
          "query" -> FixedQueryBehavior(evaluatedBytes = 12288L)
        ),
        readConsistency = ReadConsistency.StronglyConsistent,
        globalSecondaryIndexes = Vector(
          DynamoDbTable.GlobalSecondaryIndexDefinition("status-index",
            stateModel = FixedTableState(4L, 4096L),
            projection = DynamoDbTable.IndexProjection.All
          )
        ),
        billingMode = DynamoDbTable.BillingMode.Provisioned(
          readCapacityUnits = 1000L,
          writeCapacityUnits = 100L,
          globalSecondaryIndexReadCapacityUnits = Map("status-index" -> 1000L)
        )
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick50 = TimedControlEvent.Tick(SimTime.of(50L))

      val switchEvent = DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(10L),
        usecase = "switch",
        newMode = DynamoDbTable.BillingMode.Provisioned(
          readCapacityUnits = 1000L,
          writeCapacityUnits = 100L,
          globalSecondaryIndexReadCapacityUnits = Map("status-index" -> 1L)
        )
      )

      val (responseFuture, _, metricsFuture) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](
          tick1,
          tick50,
          QueryRequest(eventTime = SimTime.of(50L), usecase = "query",
            target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"))
        )),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          tick1,
          switchEvent,
          tick50
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val metrics = Await.result(metricsFuture, 5.seconds)

      val capacityChanged = metrics.collect { case m: AdmissionMetricEvent.ProvisionedCapacityChanged => m }
      capacityChanged.size should be >= 1

      val throttled = responses.collect { case t: ThrottledResponse => t }
      throttled should have size 1
      throttled.head.reason shouldBe DynamoDbThrottleReason.GlobalSecondaryIndexReadProvisionedThroughputExceeded
    }

    "componentOfManaged applies UpdateProvisionedCapacity and emits ProvisionedCapacityChanged" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 5120L),
        useCaseBehaviors = Map("get" -> FixedHitGetItemBehavior(5120L)),
        readConsistency = ReadConsistency.StronglyConsistent,
        billingMode = DynamoDbTable.BillingMode.Provisioned(1L, 1L)
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick50 = TimedControlEvent.Tick(SimTime.of(50L))

      val updateEvent = DynamoDbManagementEvent.UpdateProvisionedCapacity(
        eventTime = SimTime.of(10L),
        usecase = "scale-up",
        newCapacity = DynamoDbTable.BillingMode.Provisioned(100L, 100L)
      )

      val (responseFuture, _, metricsFuture) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](
          tick1,
          tick50,
          GetItemRequest(eventTime = SimTime.of(50L), usecase = "get")
        )),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          tick1,
          updateEvent,
          tick50
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val metrics = Await.result(metricsFuture, 5.seconds)

      val capacityChanged = metrics.collect { case m: AdmissionMetricEvent.ProvisionedCapacityChanged => m }
      capacityChanged.size should be >= 1
      metrics.collect { case _: AdmissionMetricEvent.BillingModeSwitched => 1 } shouldBe empty

      val throttled = responses.collect { case t: ThrottledResponse => t }
      throttled shouldBe empty
    }

    "componentOfManaged rejects UpdateProvisionedCapacity when table is on-demand" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
        useCaseBehaviors = Map.empty,
        readConsistency = ReadConsistency.StronglyConsistent
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))

      val updateEvent = DynamoDbManagementEvent.UpdateProvisionedCapacity(
        eventTime = SimTime.of(1L),
        usecase = "bad-update",
        newCapacity = DynamoDbTable.BillingMode.Provisioned(50L, 50L)
      )

      val (responseFuture, _, _) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](tick1)),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          updateEvent
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val rejections = responses.collect { case r: ReconfigurationRejectedResponse => r }
      rejections should have size 1
      rejections.head.usecase shouldBe "bad-update"
      rejections.head.reason should include("provisioned billing mode")
    }

    "componentOfManaged does not apply 24-hour cooldown to UpdateProvisionedCapacity" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
        useCaseBehaviors = Map.empty,
        readConsistency = ReadConsistency.StronglyConsistent,
        billingMode = DynamoDbTable.BillingMode.Provisioned(10L, 10L)
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick2 = TimedControlEvent.Tick(SimTime.of(2L))

      val switchEvent = DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(1L),
        usecase = "switch",
        newMode = DynamoDbTable.BillingMode.Provisioned(20L, 20L)
      )
      val updateEvent = DynamoDbManagementEvent.UpdateProvisionedCapacity(
        eventTime = SimTime.of(2L),
        usecase = "capacity-update",
        newCapacity = DynamoDbTable.BillingMode.Provisioned(30L, 30L)
      )

      val (responseFuture, _, metricsFuture) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](tick1, tick2)),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          switchEvent, updateEvent
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val metrics = Await.result(metricsFuture, 5.seconds)

      val rejections = responses.collect { case r: ReconfigurationRejectedResponse => r }
      rejections shouldBe empty

      val capacityChanged = metrics.collect { case m: AdmissionMetricEvent.ProvisionedCapacityChanged => m }
      capacityChanged.size should be >= 1
    }

    "componentOfManaged propagates provisioned capacity change to GSI branch" in {
      val config = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(itemCount = 1L, totalItemBytes = 512L),
        useCaseBehaviors = Map(
          "query" -> FixedQueryBehavior(evaluatedBytes = 12288L)
        ),
        readConsistency = ReadConsistency.StronglyConsistent,
        globalSecondaryIndexes = Vector(
          DynamoDbTable.GlobalSecondaryIndexDefinition("status-index",
            stateModel = FixedTableState(4L, 4096L),
            projection = DynamoDbTable.IndexProjection.All
          )
        ),
        billingMode = DynamoDbTable.BillingMode.Provisioned(
          readCapacityUnits = 1000L,
          writeCapacityUnits = 100L,
          globalSecondaryIndexReadCapacityUnits = Map("status-index" -> 1000L)
        )
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val tick50 = TimedControlEvent.Tick(SimTime.of(50L))

      val updateEvent = DynamoDbManagementEvent.UpdateProvisionedCapacity(
        eventTime = SimTime.of(10L),
        usecase = "reduce-gsi",
        newCapacity = DynamoDbTable.BillingMode.Provisioned(
          readCapacityUnits = 1000L,
          writeCapacityUnits = 100L,
          globalSecondaryIndexReadCapacityUnits = Map("status-index" -> 1L)
        )
      )

      val (responseFuture, _, metricsFuture) = runManagedComponent(
        requestSource = Source(Vector[TimedElement[DynamoDBRequest]](
          tick1,
          tick50,
          QueryRequest(eventTime = SimTime.of(50L), usecase = "query",
            target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"))
        )),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](
          tick1,
          updateEvent,
          tick50
        )),
        config = config
      )

      val responses = Await.result(responseFuture, 5.seconds)
      val metrics = Await.result(metricsFuture, 5.seconds)

      val capacityChanged = metrics.collect { case m: AdmissionMetricEvent.ProvisionedCapacityChanged => m }
      capacityChanged.size should be >= 1

      val throttled = responses.collect { case t: ThrottledResponse => t }
      throttled should have size 1
      throttled.head.reason shouldBe DynamoDbThrottleReason.GlobalSecondaryIndexReadProvisionedThroughputExceeded
    }
  }

  private def indexedConfig(): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
      useCaseBehaviors = Map.empty,
      readConsistency = ReadConsistency.StronglyConsistent,
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition("status-index")
      ),
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index")
      )
    )

  private def readCapableIndexedConfig(): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
      useCaseBehaviors = Map(
        "query-usecase" -> FixedQueryBehavior(),
        "scan-usecase" -> FixedScanBehavior
      ),
      readConsistency = ReadConsistency.StronglyConsistent,
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(4L, 4096L))
      ),
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = FixedTableState(5L, 5120L))
      )
    )

  private def runManagedComponent(
                                   requestSource: Source[TimedElement[DynamoDBRequest], ?],
                                   managementSource: Source[TimedElement[DynamoDbManagementEvent], ?],
                                   config: DynamoDbTable.Config
                                 ): (
                                   Future[Seq[TimedEvent]],
                                   Future[Seq[TimedEvent]],
                                   Future[Seq[TimedEvent]]
                                 ) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)((r, c, m) => (r, c, m)) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits.*

          val table = b.add(DynamoDbTable.componentOfManaged(config))

          requestSource ~> table.requestIn
          managementSource ~> table.managementIn
          table.responseOut ~> respSink
          table.consumptionOut ~> consSink
          table.metricOut ~> metrSink

          ClosedShape
      }
    ).run()

  private def runComponent(
                            requestSource: Source[TimedElement[DynamoDBRequest], ?],
                            config: DynamoDbTable.Config
                          ): (
                            Future[Seq[TimedEvent]],
                            Future[Seq[TimedEvent]],
                            Future[Seq[TimedEvent]]
                          ) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)((r, c, m) => (r, c, m)) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits.*

          val table = b.add(DynamoDbTable.componentOf(config))

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

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

  private case class FixedDeleteItemSample(
                                            override val deletedItemBytes: Option[Long],
                                            override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-delete")
                                          ) extends DeleteItemSample

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

  private case class FixedDeleteItemBehavior(
                                              deletedItemBytes: Option[Long],
                                              logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-delete")
                                            ) extends UseCaseSampler[TableState]:
    override def deleteItem(request: DeleteItemRequest, ctx: SamplerContext[TableState]): DeleteItemSample =
      FixedDeleteItemSample(deletedItemBytes, logicalPartitionAccess)

  private case class FixedQueryBehavior(
                                         evaluatedBytes: Long = 4096L,
                                         logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-query")
                                       ) extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(
        evaluatedItemCount = 8L,
        evaluatedBytes = evaluatedBytes,
        returnedItemCount = 2L,
        returnedBytes = 1024L,
        logicalPartitionAccess = logicalPartitionAccess
      )

  private object FixedScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 14L,
        evaluatedBytes = 8192L,
        returnedItemCount = 3L,
        returnedBytes = 1536L
      )

  private object ProjectionLimitedQueryBehavior extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(
        evaluatedItemCount = 5L,
        evaluatedBytes = 4096L,
        returnedItemCount = 2L,
        returnedBytes = 1536L,
        projectedBytesReturned = 512L,
        baseTableFetchBytes = 1024L,
        baseTableFetchItemCount = 2L,
        projectionSatisfaction = ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch
      )

  private object ProjectionFetchQueryBehavior extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(
        evaluatedItemCount = 3L,
        evaluatedBytes = 3072L,
        returnedItemCount = 2L,
        returnedBytes = 1536L,
        projectedBytesReturned = 512L,
        baseTableFetchBytes = 1024L,
        baseTableFetchItemCount = 2L,
        projectionSatisfaction = ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch
      )

  private object ProjectionLimitedScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 6L,
        evaluatedBytes = 6144L,
        returnedItemCount = 3L,
        returnedBytes = 1792L,
        projectedBytesReturned = 768L,
        baseTableFetchBytes = 1024L,
        baseTableFetchItemCount = 3L,
        projectionSatisfaction = ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch
      )

  private object ProjectionFetchScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 8L,
        evaluatedBytes = 8192L,
        returnedItemCount = 4L,
        returnedBytes = 3072L,
        projectedBytesReturned = 1024L,
        baseTableFetchBytes = 2048L,
        baseTableFetchItemCount = 4L,
        projectionSatisfaction = ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch
      )

  private case class SamplingPutBehavior(invocations: AtomicInteger) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      val invocation = invocations.incrementAndGet()
      invocation match
        case 1 => FixedPutItemSample(writtenItemBytes = 1024L, previousItemBytes = None)
        case _ => FixedPutItemSample(writtenItemBytes = 2048L, previousItemBytes = None)

  private def twoKeysForDifferentPartitions(partitionCount: Int): (String, String) =
    val keysByPartition =
      (0 until 10_000)
        .map(i => s"component-key-$i")
        .groupBy { token =>
          PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1
        }
        .toVector

    if keysByPartition.size < 2 then fail("Unable to find keys for different partitions")
    else (keysByPartition(0)._2.head, keysByPartition(1)._2.head)

  private def keyForPartition(partitionCount: Int, partitionId: Int): String =
    (0 until 10_000)
      .map(i => s"component-key-$i")
      .find { token =>
        PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1 == partitionId
      }
      .getOrElse(fail(s"Unable to find key for partition $partitionId with partitionCount=$partitionCount"))
