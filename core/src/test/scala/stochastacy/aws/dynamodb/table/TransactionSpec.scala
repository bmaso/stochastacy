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

class TransactionSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("transaction-spec-test")
  given Materializer = Materializer.matFromSystem

  "DynamoDbTable TransactWriteItems" should {

    "emit TransactWriteItemsResponse and charge 2× WCU per item" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
        useCaseBehaviors = Map("cmd" -> FixedTransactWriteBehavior(Vector(200L, 150L)))
      )

      val (responseFuture, consumptionFuture, _) = runComponent(
        Source.single(TransactWriteItemsRequest(SimTime.of(1L), "cmd", Vector(200L, 150L))),
        config
      )

      val responses = Await.result(responseFuture, 3.seconds)
      val consumption = Await.result(consumptionFuture, 3.seconds)

      responses.collect { case r: TransactWriteItemsResponse => r } shouldBe Vector(
        TransactWriteItemsResponse(SimTime.of(1L), "cmd", itemCount = 2)
      )

      // Item 1: 200 bytes → ceil(200/1024) = 1 WCU × 2 = 2; Item 2: 150 bytes → 1 WCU × 2 = 2
      val wcuEvents = consumption.collect { case e: DynamoDbConsumptionEvent.WriteCapacityConsumed => e }
      wcuEvents.map(_.units).sum shouldBe BigDecimal(4)
    }

    "charge 2× write capacity per item (transactional billing)" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
        useCaseBehaviors = Map("cmd" -> FixedTransactWriteBehavior(Vector(1024L, 1024L, 1024L)))
      )

      val (_, consumptionFuture, _) = runComponent(
        Source.single(TransactWriteItemsRequest(SimTime.of(1L), "cmd", Vector(1024L, 1024L, 1024L))),
        config
      )

      val consumption = Await.result(consumptionFuture, 3.seconds)
      // 3 items × 1 WCU × 2 (transactional) = 6 WCU
      consumption.collect { case e: DynamoDbConsumptionEvent.WriteCapacityConsumed => e.units }.sum shouldBe BigDecimal(6)
    }

    "emit SystemErrorResponse and SystemError metric on simulated internal error" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
        useCaseBehaviors = Map("cmd" -> FixedTransactWriteBehavior(Vector(200L, 150L))),
        systemErrorRate = 0.9999
      )

      val (responseFuture, consumptionFuture, metricsFuture) = runComponent(
        Source.single(TransactWriteItemsRequest(SimTime.of(1L), "cmd", Vector(200L, 150L))),
        config
      )

      val responses = Await.result(responseFuture, 3.seconds)
      val consumption = Await.result(consumptionFuture, 3.seconds)
      val metrics = Await.result(metricsFuture, 3.seconds)

      responses.collect { case r: SystemErrorResponse => r } should have size 1
      consumption.collect { case e: DynamoDbConsumptionEvent.WriteCapacityConsumed => e } shouldBe empty
      metrics.collect { case e: StorageMetricEvent.SystemError => e } should have size 1
    }

    "reject transaction and emit ItemCollectionSizeLimitExceededResponse when LSI limit exceeded" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
        useCaseBehaviors = Map("cmd" ->
          FixedTransactWriteBehavior(Vector(200L, 150L), currentItemCollectionBytes = 9_999_999_900L)
        ),
        itemCollectionSizeLimitBytes = Some(10_000_000_000L),
        localSecondaryIndexes = Vector(
          DynamoDbTable.LocalSecondaryIndexDefinition("lsi1", stateModel = FixedTableState(0L, 0L))
        )
      )

      val (responseFuture, consumptionFuture, _) = runComponent(
        Source.single(TransactWriteItemsRequest(SimTime.of(1L), "cmd", Vector(200L, 150L))),
        config
      )

      val responses = Await.result(responseFuture, 3.seconds)
      val consumption = Await.result(consumptionFuture, 3.seconds)

      responses.collect { case r: ItemCollectionSizeLimitExceededResponse => r } should have size 1
      consumption.collect { case e: DynamoDbConsumptionEvent.WriteCapacityConsumed => e } shouldBe empty
    }

    "mutate table state for all items in the transaction (all-or-nothing commit)" in {
      val stateModel = FixedTableState(itemCount = 0L, totalItemBytes = 0L)
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = stateModel,
        useCaseBehaviors = Map("cmd" -> FixedTransactWriteBehavior(Vector(200L, 150L)))
      )

      val (_, _, metricsFuture) = runComponent(
        Source.single(TransactWriteItemsRequest(SimTime.of(1L), "cmd", Vector(200L, 150L))),
        config
      )

      Await.result(metricsFuture, 3.seconds)

      stateModel.itemCount shouldBe 2L
      stateModel.totalItemBytes shouldBe (200L + 150L)
    }
  }

  "DynamoDbTable TransactGetItems" should {

    "emit TransactGetItemsResponse and charge 2× RCU (strongly consistent) per item" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 100L, totalItemBytes = 100_000L),
        useCaseBehaviors = Map("cmd" -> FixedTransactGetBehavior(Vector(Some(512L), Some(256L))))
      )

      val (responseFuture, consumptionFuture, _) = runComponent(
        Source.single(TransactGetItemsRequest(SimTime.of(1L), "cmd", itemCount = 2)),
        config
      )

      val responses = Await.result(responseFuture, 3.seconds)
      val consumption = Await.result(consumptionFuture, 3.seconds)

      val txnResponses = responses.collect { case r: TransactGetItemsResponse => r }
      txnResponses should have size 1
      txnResponses.head.items shouldBe Vector(Some(512L), Some(256L))

      // 512 bytes → 1 RCU × 2 = 2; 256 bytes → 1 RCU × 2 = 2; total = 4
      val rcuEvents = consumption.collect { case e: DynamoDbConsumptionEvent.ReadCapacityConsumed => e }
      rcuEvents.map(_.units).sum shouldBe BigDecimal(4)
      rcuEvents.map(_.consistency).forall(_ == ReadConsistency.StronglyConsistent) shouldBe true
    }

    "charge 2× read capacity (strongly consistent) per item" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 100L, totalItemBytes = 100_000L),
        useCaseBehaviors = Map("cmd" -> FixedTransactGetBehavior(Vector(Some(4096L), Some(4096L), Some(4096L))))
      )

      val (_, consumptionFuture, _) = runComponent(
        Source.single(TransactGetItemsRequest(SimTime.of(1L), "cmd", itemCount = 3)),
        config
      )

      val consumption = Await.result(consumptionFuture, 3.seconds)
      // 3 items × 1 RCU (strongly consistent: 4096/4096=1) × 2 = 6 RCU
      consumption.collect { case e: DynamoDbConsumptionEvent.ReadCapacityConsumed => e.units }.sum shouldBe BigDecimal(6)
    }

    "emit SystemErrorResponse for TransactGetItems on simulated error" in {
      val config = DynamoDbTable.Config(
        tableName = "commands",
        stateModel = FixedTableState(itemCount = 100L, totalItemBytes = 100_000L),
        useCaseBehaviors = Map("cmd" -> FixedTransactGetBehavior(Vector(Some(512L)))),
        systemErrorRate = 0.9999
      )

      val (responseFuture, _, _) = runComponent(
        Source.single(TransactGetItemsRequest(SimTime.of(1L), "cmd", itemCount = 1)),
        config
      )

      val responses = Await.result(responseFuture, 3.seconds)
      responses.collect { case r: SystemErrorResponse => r } should have size 1
    }
  }

  private def runComponent(
    requestSource: Source[TimedElement[DynamoDBRequest], ?],
    config: DynamoDbTable.Config
  ): (Future[Seq[TimedEvent]], Future[Seq[TimedEvent]], Future[Seq[TimedEvent]]) =
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

  private case class FixedTransactWriteSample(
    override val items: Vector[WriteItemSample]
  ) extends TransactWriteItemsSample

  private case class FixedWriteSample(
    override val writtenItemBytes: Long,
    override val previousItemBytes: Option[Long],
    override val currentItemCollectionBytes: Long
  ) extends PutItemSample:
    override val logicalPartitionAccess: LogicalPartitionAccess =
      LogicalPartitionAccess.SingleLogicalPartitionKey("default")

  private case class FixedTransactWriteBehavior(
    itemBytes: Vector[Long],
    currentItemCollectionBytes: Long = 0L
  ) extends UseCaseSampler[TableState]:
    override def transactWriteItems(request: TransactWriteItemsRequest, ctx: SamplerContext[TableState]): TransactWriteItemsSample =
      FixedTransactWriteSample(itemBytes.map { bytes =>
        FixedWriteSample(writtenItemBytes = bytes, previousItemBytes = None, currentItemCollectionBytes = currentItemCollectionBytes)
      })

  private case class FixedTransactGetSample(
    override val items: Vector[GetItemSample]
  ) extends TransactGetItemsSample

  private case class FixedTransactGetBehavior(
    items: Vector[Option[Long]]
  ) extends UseCaseSampler[TableState]:
    override def transactGetItems(request: TransactGetItemsRequest, ctx: SamplerContext[TableState]): TransactGetItemsSample =
      FixedTransactGetSample(items.map { maybeBytes =>
        GetItemSample(itemBytes = maybeBytes)
      })
