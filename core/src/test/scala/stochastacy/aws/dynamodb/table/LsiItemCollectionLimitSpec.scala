package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedElement, TimedEvent}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class LsiItemCollectionLimitSpec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  given ActorSystem = ActorSystem("lsi-item-collection-limit-test")
  given Materializer = Materializer.matFromSystem

  "DynamoDbTable LSI item-collection-size limit" should {

    "reject a write that grows an LSI-backed item collection past the configured limit" in {
      val limit = 10_000L
      // With an All-projection LSI, totalDelta = baseDelta + lsiDelta = 1024 + 1024 = 2048.
      // current 9000 + 2048 = 11048 > 10000 → reject.
      val config = lsiBackedConfig(
        useCases = Map(
          "put-near-limit" -> SizingPutItemBehavior(
            writtenItemBytes = 1024L,
            previousItemBytes = None,
            currentItemCollectionBytes = 9_000L
          )
        ),
        itemCollectionSizeLimitBytes = Some(limit)
      )

      val (responses, consumption, metrics) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-near-limit", itemBytes = 1024L)),
        config
      )

      val rejections = responses.collect { case r: ItemCollectionSizeLimitExceededResponse => r }
      rejections should have size 1
      rejections.head.limitBytes shouldBe limit
      rejections.head.resultingCollectionBytes shouldBe 11_048L
      rejections.head.operation shouldBe DynamoDbOperationKind.PutItem

      responses.collect { case _: PutItemResponse => 1 } shouldBe empty
      consumption.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } shouldBe empty

      val metricEvents = metrics.collect { case m: StorageMetricEvent.ItemCollectionSizeLimitExceeded => m }
      metricEvents should have size 1
      metricEvents.head.limitBytes shouldBe limit
      metricEvents.head.resultingCollectionBytes shouldBe 11_048L
    }

    "admit a write whose resulting item collection stays within the limit" in {
      val limit = 10_000L
      val config = lsiBackedConfig(
        useCases = Map(
          "put-cool" -> SizingPutItemBehavior(
            writtenItemBytes = 1_000L,
            previousItemBytes = None,
            currentItemCollectionBytes = 0L
          )
        ),
        itemCollectionSizeLimitBytes = Some(limit)
      )

      val (responses, consumption, _) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-cool", itemBytes = 1_000L)),
        config
      )

      responses.collect { case _: PutItemResponse => 1 } should have size 1
      responses.collect { case _: ItemCollectionSizeLimitExceededResponse => 1 } shouldBe empty
      consumption.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } should not be empty
    }

    "admit a delete that shrinks an item collection currently over the limit" in {
      val limit = 10_000L
      // Anomalous prior state: collection currently at 11_000. Delete shrinks the
      // collection, so the rule must allow it regardless of whether current > limit.
      val config = lsiBackedConfig(
        useCases = Map(
          "delete-from-over-limit" -> SizingDeleteItemBehavior(
            deletedItemBytes = Some(2_000L),
            currentItemCollectionBytes = 11_000L
          )
        ),
        itemCollectionSizeLimitBytes = Some(limit)
      )

      val (responses, _, _) = runAndCollect(
        Source.single(DeleteItemRequest(SimTime.of(1L), usecase = "delete-from-over-limit")),
        config
      )

      responses.collect { case _: DeleteItemResponse => 1 } should have size 1
      responses.collect { case _: ItemCollectionSizeLimitExceededResponse => 1 } shouldBe empty
    }

    "admit a write whose resulting collection equals the limit exactly (boundary)" in {
      val limit = 10_000L
      // baseDelta=500, lsiDelta=500 (All projection), totalDelta=1000.
      // current 9000 + 1000 = 10000 == limit → admit (rule is "newSize > limit").
      val config = lsiBackedConfig(
        useCases = Map(
          "put-at-limit" -> SizingPutItemBehavior(
            writtenItemBytes = 500L,
            previousItemBytes = None,
            currentItemCollectionBytes = 9_000L
          )
        ),
        itemCollectionSizeLimitBytes = Some(limit)
      )

      val (responses, _, _) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-at-limit", itemBytes = 500L)),
        config
      )

      responses.collect { case _: PutItemResponse => 1 } should have size 1
      responses.collect { case _: ItemCollectionSizeLimitExceededResponse => 1 } shouldBe empty
    }

    "not enforce the limit when no LSIs are configured" in {
      val config = noLsiConfig(
        useCases = Map(
          "put-large" -> SizingPutItemBehavior(
            writtenItemBytes = 100_000L,
            previousItemBytes = None,
            currentItemCollectionBytes = 100_000_000L
          )
        ),
        itemCollectionSizeLimitBytes = Some(1_000L)
      )

      val (responses, _, _) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-large", itemBytes = 100_000L)),
        config
      )

      responses.collect { case _: PutItemResponse => 1 } should have size 1
      responses.collect { case _: ItemCollectionSizeLimitExceededResponse => 1 } shouldBe empty
    }

    "default the limit to 10 GiB when LSIs are configured and the limit is unset" in {
      val tenGiB = 10L * 1024L * 1024L * 1024L
      // baseDelta=2, lsiDelta=2 (All projection), totalDelta=4.
      // current=tenGiB - 1 → resulting = tenGiB + 3 > tenGiB → reject; reported limit = tenGiB.
      val config = lsiBackedConfig(
        useCases = Map(
          "put-just-over-default" -> SizingPutItemBehavior(
            writtenItemBytes = 2L,
            previousItemBytes = None,
            currentItemCollectionBytes = tenGiB - 1L
          )
        ),
        itemCollectionSizeLimitBytes = None
      )

      val (responses, _, _) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-just-over-default", itemBytes = 2L)),
        config
      )

      val rejections = responses.collect { case r: ItemCollectionSizeLimitExceededResponse => r }
      rejections should have size 1
      rejections.head.limitBytes shouldBe tenGiB
    }

    "suppress index-maintenance side-effects when a write is rejected" in {
      val limit = 10_000L
      val config = lsiBackedConfig(
        useCases = Map(
          "put-rejected" -> SizingPutItemBehavior(
            writtenItemBytes = 5_000L,
            previousItemBytes = None,
            currentItemCollectionBytes = 9_000L
          )
        ),
        itemCollectionSizeLimitBytes = Some(limit)
      )

      val (responses, consumption, metrics) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-rejected", itemBytes = 5_000L)),
        config
      )

      responses.collect { case _: ItemCollectionSizeLimitExceededResponse => 1 } should have size 1
      consumption.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } shouldBe empty
      consumption.collect { case _: DynamoDbConsumptionEvent.StorageBytesWritten => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent.PutItemStored => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent.IndexEntryInserted => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent.IndexEntryReplaced => 1 } shouldBe empty
      metrics.collect { case _: StorageMetricEvent.IndexEntryDeleted => 1 } shouldBe empty
    }
  }

  private def lsiBackedConfig(
                               useCases: Map[Any, UseCaseSampler[TableState]],
                               itemCollectionSizeLimitBytes: Option[Long]
                             ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(0L, 0L),
      useCaseBehaviors = useCases,
      readConsistency = ReadConsistency.StronglyConsistent,
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition("created-at-index")
      ),
      itemCollectionSizeLimitBytes = itemCollectionSizeLimitBytes
    )

  private def noLsiConfig(
                           useCases: Map[Any, UseCaseSampler[TableState]],
                           itemCollectionSizeLimitBytes: Option[Long]
                         ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(0L, 0L),
      useCaseBehaviors = useCases,
      readConsistency = ReadConsistency.StronglyConsistent,
      itemCollectionSizeLimitBytes = itemCollectionSizeLimitBytes
    )

  private def runAndCollect(
                             requestSource: Source[TimedElement[DynamoDBRequest], ?],
                             config: DynamoDbTable.Config
                           ): (Seq[TimedEvent], Seq[TimedEvent], Seq[TimedEvent]) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    val (rF, cF, mF) = RunnableGraph.fromGraph(
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

    (Await.result(rF, 5.seconds), Await.result(cF, 5.seconds), Await.result(mF, 5.seconds))

  private case class SizingPutItemSample(
                                          override val writtenItemBytes: Long,
                                          override val previousItemBytes: Option[Long],
                                          override val currentItemCollectionBytes: Long,
                                          override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-pk")
                                        ) extends PutItemSample

  private case class SizingDeleteItemSample(
                                             override val deletedItemBytes: Option[Long],
                                             override val currentItemCollectionBytes: Long,
                                             override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-pk")
                                           ) extends DeleteItemSample

  private case class SizingPutItemBehavior(
                                            writtenItemBytes: Long,
                                            previousItemBytes: Option[Long],
                                            currentItemCollectionBytes: Long,
                                            logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-pk")
                                          ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      SizingPutItemSample(writtenItemBytes, previousItemBytes, currentItemCollectionBytes, logicalPartitionAccess)

  private case class SizingDeleteItemBehavior(
                                               deletedItemBytes: Option[Long],
                                               currentItemCollectionBytes: Long,
                                               logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-pk")
                                             ) extends UseCaseSampler[TableState]:
    override def deleteItem(request: DeleteItemRequest, ctx: SamplerContext[TableState]): DeleteItemSample =
      SizingDeleteItemSample(deletedItemBytes, currentItemCollectionBytes, logicalPartitionAccess)
