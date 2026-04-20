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

    "route table-targeted Query requests to the base-table path" in {
      val config = indexedConfig()

      val (responseFuture, resourceFuture, metricsFuture) =
        runComponent(
          Source.single(
            QueryRequest(
              eventTime = SimTime.of(1L),
              usecase = "query-usecase",
              target = DynamoDbReadTarget.Table("orders")
            )
          ),
          config
        )

      val responseError = Await.result(responseFuture.failed, 3.seconds)
      val resourceError = Await.result(resourceFuture.failed, 3.seconds)
      val metricsError = Await.result(metricsFuture.failed, 3.seconds)

      responseError.getMessage should include("Query is not yet supported")
      resourceError.getMessage should include("Query is not yet supported")
      metricsError.getMessage should include("Query is not yet supported")
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

  private case class FixedHitGetItemBehavior(bytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] =
      Some(FixedGetItemSample(bytes))

  private case class FixedPutItemBehavior(
                                           writtenItemBytes: Long,
                                           previousItemBytes: Option[Long]
                                         ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes)
