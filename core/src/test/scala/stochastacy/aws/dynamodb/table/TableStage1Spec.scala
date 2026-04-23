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

class TableStage1Spec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  given ActorSystem = ActorSystem("table-stage1-test")
  given Materializer = Materializer.matFromSystem

  "TableStage1" should {
    "admit a request under the configured hard check" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestAdmitted => metric.throughputDemand } shouldBe Vector(BigDecimal(1))
    }

    "throttle a request over the configured hard check immediately" in {
      val (admittedFuture, responseFuture, metricFuture) =
        runStage(
          Source.single(GetItemRequest(eventTime = SimTime.of(1L), usecase = "get-hit")),
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.throughputDemand } shouldBe Vector(BigDecimal(2))
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestAdmitted => metric.dimension } shouldBe Vector(DynamoDbThroughputDimension.Write)
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.dimension } shouldBe Vector(DynamoDbThroughputDimension.Read)
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.target } shouldBe Vector(
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.reason } shouldBe Vector(
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
          TableStage1.Config(
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.resolvedPartitionFootprint.partitionDemandById.values.sum } shouldBe Vector(BigDecimal(2))
    }

    "throttle base-table writes for a hot write partition" in {
      val (_, responseFuture, metricFuture) =
        runStage(
          Source.single(PutItemRequest(eventTime = SimTime.of(1L), usecase = "put-new", itemBytes = 2048L)),
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.dimension } shouldBe Vector(
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.target } shouldBe Vector(
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
          TableStage1.Config(
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
      metrics.collect { case metric: Stage1MetricEvent.RequestThrottled => metric.reason } shouldBe Vector(
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
            TableStage1.Config(
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
            TableStage1.Config(
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
  }

  private def runStage(
                        requestSource: Source[TimedElement[DynamoDBRequest], ?],
                        config: TableStage1.Config
                      ): (Future[Seq[TimedEvent]], Future[Seq[TimedEvent]], Future[Seq[TimedEvent]]) =
    val admittedSink = Sink.seq[TimedEvent]
    val responseSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(admittedSink, responseSink, metricsSink)((a, r, m) => (a, r, m)) { implicit b =>
        (admSink, respSink, metrSink) =>
          import GraphDSL.Implicits.*

          val stage = b.add(TableStage1.componentOf(config))

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

  private case class FixedHitGetItemBehavior(
                                              bytes: Long,
                                              logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-get")
                                            ) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): GetItemSample =
      GetItemSample(itemBytes = Some(bytes), logicalPartitionAccess = logicalPartitionAccess)

  private case class FixedPutItemBehavior(
                                           writtenItemBytes: Long,
                                           previousItemBytes: Option[Long],
                                           logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-put")
                                         ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes, logicalPartitionAccess)

  private case class FixedQueryBehavior(
                                         evaluatedBytes: Long,
                                         logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default-query")
                                       ) extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, state: TableState): QuerySample =
      QuerySample(
        evaluatedItemCount = 4L,
        evaluatedBytes = evaluatedBytes,
        returnedItemCount = 1L,
        returnedBytes = 512L,
        logicalPartitionAccess = logicalPartitionAccess
      )

  private case class FixedScanBehavior(evaluatedBytes: Long) extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, state: TableState): ScanSample =
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
