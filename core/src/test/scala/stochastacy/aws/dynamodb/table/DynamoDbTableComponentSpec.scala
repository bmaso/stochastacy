package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class DynamoDbTableComponentSpec extends AnyWordSpec with should.Matchers:

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

      metrics.collect { case _: Stage4MetricEvent.GetItemObserved => 1 }.size shouldBe 3
      metrics.collect { case Stage4MetricEvent.GetItemReturned(_, _, bytes) => bytes }.sum shouldBe 1536L
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

      metrics.collect { case _: Stage4MetricEvent.PutItemObserved => 1 }.size shouldBe 1
      metrics.collect { case Stage4MetricEvent.TableBytesChanged(_, _, delta) => delta }.sum shouldBe 1024L
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

      metrics.collect { case _: Stage4MetricEvent.PutItemObserved => 1 }.size shouldBe 1
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
      val config = queryCapableIndexedConfig()

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
      metrics.collect { case evt: Stage4MetricEvent.QueryObserved => evt.target } should contain(DynamoDbReadTarget.Table("orders"))
    }

    "execute GSI and LSI Query requests against internal index state" in {
      val config = queryCapableIndexedConfig()

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

    "reject strongly consistent GSI Query requests" in {
      val config = queryCapableIndexedConfig()

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

    "route index-targeted reads to the configured placeholder execution unit" in {
      val config = indexedConfig()

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "scan-usecase",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index")
            )
          ),
          config
        )

      val responseError = Await.result(responseFuture.failed, 3.seconds)
      val resourceError = Await.result(resourceFuture.failed, 3.seconds)
      val metricsError = Await.result(metricsFuture.failed, 3.seconds)

      responseError.getMessage should include("Scan is not yet supported for local secondary index 'created-at-index'")
      resourceError.getMessage should include("Scan is not yet supported for local secondary index 'created-at-index'")
      metricsError.getMessage should include("Scan is not yet supported for local secondary index 'created-at-index'")
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

  private def queryCapableIndexedConfig(): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(itemCount = 10L, totalItemBytes = 10000L),
      useCaseBehaviors = Map("query-usecase" -> FixedQueryBehavior),
      readConsistency = ReadConsistency.StronglyConsistent,
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition("status-index", stateModel = FixedTableState(4L, 4096L))
      ),
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index", stateModel = FixedTableState(5L, 5120L))
      )
    )

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

  private case class FixedGetItemSample(override val getItemBytes: Long) extends GetItemSample

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long]
                                       ) extends PutItemSample

  private case class FixedUpdateItemSample(
                                            override val writtenItemBytes: Long,
                                            override val previousItemBytes: Option[Long]
                                          ) extends UpdateItemSample

  private case class FixedDeleteItemSample(
                                            override val deletedItemBytes: Option[Long]
                                          ) extends DeleteItemSample

  private case class FixedHitGetItemBehavior(bytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] =
      Some(FixedGetItemSample(bytes))

  private case class FixedPutItemBehavior(
                                           writtenItemBytes: Long,
                                           previousItemBytes: Option[Long]
                                         ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes)

  private case class FixedUpdateItemBehavior(
                                              writtenItemBytes: Long,
                                              previousItemBytes: Option[Long]
                                            ) extends UseCaseSampler[TableState]:
    override def updateItem(request: UpdateItemRequest, state: TableState): UpdateItemSample =
      FixedUpdateItemSample(writtenItemBytes, previousItemBytes)

  private case class FixedDeleteItemBehavior(
                                              deletedItemBytes: Option[Long]
                                            ) extends UseCaseSampler[TableState]:
    override def deleteItem(request: DeleteItemRequest, state: TableState): DeleteItemSample =
      FixedDeleteItemSample(deletedItemBytes)

  private object FixedQueryBehavior extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, state: TableState): QuerySample =
      QuerySample(
        evaluatedItemCount = 8L,
        evaluatedBytes = 4096L,
        returnedItemCount = 2L,
        returnedBytes = 1024L
      )
