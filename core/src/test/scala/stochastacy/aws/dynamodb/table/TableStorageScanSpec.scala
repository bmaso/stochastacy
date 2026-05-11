package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{DynamoDbOperationKind, DynamoDbReadTarget, RequestedReadShape, ScanRequest, ScanResponse}
import stochastacy.sim.{SimTime, TimedEvent}

class TableStorageStageScanSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-scan-test")
  given Materializer = Materializer.matFromSystem

  "TableStorageStage (scan path)" should {
    "produce summary scan responses and read consumption from evaluated bytes without mutating table state" in {
      val tableState = SummaryTableState(
        initialItemCount = 7L,
        initialTotalItemBytes = 7168L
      )

      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "filtered-scan",
              target = DynamoDbReadTarget.Table("orders"),
              readConsistency = ReadConsistency.StronglyConsistent
            )
          ),
          tableState = tableState,
          behaviors = Map("filtered-scan" -> FilteredScanBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "filtered-scan",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.StronglyConsistent,
        evaluatedItemCount = 16L,
        evaluatedBytes = 12288L,
        returnedItemCount = 5L,
        returnedBytes = 2048L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal(3))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(12288L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case evt: StorageMetricEvent.ScanObserved => evt.target } shouldBe Vector(DynamoDbReadTarget.Table("orders"))
      metricEvents.collect { case evt: StorageMetricEvent.ScanEvaluated => evt.bytes } shouldBe Vector(12288L)
      metricEvents.collect { case evt: StorageMetricEvent.ScanReturned => evt.bytes } shouldBe Vector(2048L)
      metricEvents.collect { case e: StorageMetricEvent.ReturnedItemCount => (e.operation, e.count) } shouldBe
        Vector((DynamoDbOperationKind.Scan, 5L))

      tableState.itemCount shouldBe 7L
      tableState.totalItemBytes shouldBe 7168L
    }

    "charge reads even when a scan returns no items but evaluates data" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "empty-scan",
              target = DynamoDbReadTarget.Table("orders")
            )
          ),
          tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          behaviors = Map("empty-scan" -> EmptyButEvaluatedScanBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.StronglyConsistent
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "empty-scan",
        target = DynamoDbReadTarget.Table("orders"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 6L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => evt.units } shouldBe Vector(BigDecimal("0.5"))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => evt.bytes } shouldBe Vector(4096L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case _: StorageMetricEvent.ScanReturned => 1 } shouldBe empty
      metricEvents.collect { case evt: StorageMetricEvent.ScanEvaluated => evt.itemCount } shouldBe Vector(6L)
    }

    "limit a GSI scan to projected bytes without fetching from the base table" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "gsi-projected-scan",
              target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
              requestedReadShape = RequestedReadShape.AllProjectedOrFullItem
            )
          ),
          tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          behaviors = Map("gsi-projected-scan" -> GsiProjectionLimitedBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent,
          indexProjection = Some(DynamoDbTable.IndexProjection.Include(128L))
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "gsi-projected-scan",
        target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"),
        readConsistency = ReadConsistency.EventuallyConsistent,
        evaluatedItemCount = 6L,
        evaluatedBytes = 6144L,
        returnedItemCount = 3L,
        returnedBytes = 768L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => (evt.target, evt.units) } shouldBe
        Vector(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> BigDecimal(1))
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => (evt.target, evt.bytes) } shouldBe
        Vector(DynamoDbTarget.GlobalSecondaryIndex("orders", "status-index") -> 6144L)

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case evt: StorageMetricEvent.ScanReturned => evt.bytes } shouldBe Vector(768L)
      metricEvents.collect { case evt: StorageMetricEvent.ScanUsedIndexOnly => evt.target } shouldBe
        Vector(DynamoDbReadTarget.GlobalSecondaryIndex("orders", "status-index"))
      metricEvents.collect { case _: StorageMetricEvent.ScanFetchedFromBaseTable => 1 } shouldBe empty
    }

    "add base-table fetch consumption for an LSI scan that needs non-projected attributes" in {
      val (responseProbe, resourceProbe, metricsProbe) =
        runTable(
          requestSource = Source.single(
            ScanRequest(
              eventTime = SimTime.of(1L),
              usecase = "lsi-fetch-scan",
              target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
              readConsistency = ReadConsistency.StronglyConsistent,
              requestedReadShape = RequestedReadShape.AllProjectedOrFullItem
            )
          ),
          tableState = FixedTableState(itemCount = 0L, totalItemBytes = 0L),
          behaviors = Map("lsi-fetch-scan" -> LsiFetchScanBehavior),
          tableTarget = DynamoDbTarget.Table("orders"),
          readConsistency = ReadConsistency.EventuallyConsistent,
          indexProjection = Some(DynamoDbTable.IndexProjection.KeysOnly)
        )

      resourceProbe.request(100)
      metricsProbe.request(100)

      responseProbe.request(1).expectNext() shouldBe ScanResponse(
        eventTime = SimTime.of(1L),
        usecase = "lsi-fetch-scan",
        target = DynamoDbReadTarget.LocalSecondaryIndex("orders", "created-at-index"),
        readConsistency = ReadConsistency.StronglyConsistent,
        evaluatedItemCount = 8L,
        evaluatedBytes = 8192L,
        returnedItemCount = 4L,
        returnedBytes = 3072L
      )
      responseProbe.expectComplete()

      val consumptionEvents = drainConsumptionEvents(resourceProbe)
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.ReadCapacityConsumed => (evt.target, evt.units) } shouldBe
        Vector(
          DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> BigDecimal(2),
          DynamoDbTarget.Table("orders") -> BigDecimal(1)
        )
      consumptionEvents.collect { case evt: DynamoDbConsumptionEvent.StorageBytesRead => (evt.target, evt.bytes) } shouldBe
        Vector(
          DynamoDbTarget.LocalSecondaryIndex("orders", "created-at-index") -> 8192L,
          DynamoDbTarget.Table("orders") -> 2048L
        )

      val metricEvents = drainMetricEvents(metricsProbe)
      metricEvents.collect { case evt: StorageMetricEvent.ScanFetchedFromBaseTable => (evt.itemCount, evt.bytes) } shouldBe
        Vector(4L -> 2048L)
      metricEvents.collect { case _: StorageMetricEvent.ScanUsedIndexOnly => 1 } shouldBe empty
    }
  }

  private def runTable(
                        requestSource: Source[ScanRequest, ?],
                        tableState: TableState,
                        behaviors: Map[Any, UseCaseSampler[TableState]],
                        tableTarget: DynamoDbTarget,
                        readConsistency: ReadConsistency,
                        indexProjection: Option[DynamoDbTable.IndexProjection] = None
                      ) =
    val responseSink = TestSink.probe[TimedEvent]
    val resourceSink = TestSink.probe[TimedEvent]
    val metricsSink = TestSink.probe[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)((r, c, m) => (r, c, m)) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits.*

          val table = b.add(TableStorageStage.componentOf(tableState, behaviors, tableTarget, readConsistency, indexProjection))

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private def drainMetricEvents(
                                 probe: TestSubscriber.Probe[_]
                               ): Vector[StorageMetricEvent] =
    val buf = Vector.newBuilder[StorageMetricEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: StorageMetricEvent) =>
          buf += evt
        case Right(_) =>
          done = true
        case Left(_) =>
          done = true

    buf.result()

  private def drainConsumptionEvents(
                                      probe: TestSubscriber.Probe[_]
                                    ): Vector[DynamoDbConsumptionEvent] =
    val buf = Vector.newBuilder[DynamoDbConsumptionEvent]
    var done = false

    while !done do
      probe.expectNextOrComplete() match
        case Right(evt: DynamoDbConsumptionEvent) =>
          buf += evt
        case Right(_) =>
          done = true
        case Left(_) =>
          done = true

    buf.result()

  private object FilteredScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 16L,
        evaluatedBytes = 12288L,
        returnedItemCount = 5L,
        returnedBytes = 2048L
      )

  private object EmptyButEvaluatedScanBehavior extends UseCaseSampler[TableState]:
    override def scan(request: ScanRequest, ctx: SamplerContext[TableState]): ScanSample =
      ScanSample(
        evaluatedItemCount = 6L,
        evaluatedBytes = 4096L,
        returnedItemCount = 0L,
        returnedBytes = 0L
      )

  private object GsiProjectionLimitedBehavior extends UseCaseSampler[TableState]:
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

  private object LsiFetchScanBehavior extends UseCaseSampler[TableState]:
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
