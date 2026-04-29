package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class DynamoDbTableReplicatedSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("dynamodb-table-replicated-test")
  given Materializer = Materializer.matFromSystem

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long] = None,
                                         override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("pk")
                                       ) extends PutItemSample

  private case class FixedPutItemBehavior(
                                           writtenItemBytes: Long,
                                           previousItemBytes: Option[Long] = None
                                         ) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes)

  private case class FixedHitGetItemBehavior(itemBytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = Some(itemBytes))

  private case class FixedGsiQueryBehavior() extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(evaluatedItemCount = 1, evaluatedBytes = 128L, returnedItemCount = 1, returnedBytes = 64L,
        logicalPartitionAccess = SingleLogicalPartitionKey("k"))

  private case class FixedLsiQueryBehavior() extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(evaluatedItemCount = 1, evaluatedBytes = 256L, returnedItemCount = 1, returnedBytes = 128L,
        logicalPartitionAccess = SingleLogicalPartitionKey("k"))

  private def baseConfig: DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = FixedTableState(0L, 0L),
      useCaseBehaviors = Map(
        "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
        "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L)
      ),
      readConsistency = ReadConsistency.StronglyConsistent
    )

  private def replicatedPut(
                             eventTime: SimTime,
                             itemBytes: Long,
                             usecase: Any = "replicated-put"
                           ): Replicated[AdmittedPutItemSample] =
    Replicated(AdmittedPutItemSample(
      req = PutItemRequest(eventTime = eventTime, usecase = usecase, itemBytes = itemBytes),
      executionTarget = DynamoDbTarget.Table("orders"),
      admissionTarget = DynamoDbTarget.Table("orders"),
      sample = FixedPutItemSample(itemBytes),
      throughputDemand = BigDecimal(1),
      resolvedPartitionFootprint = ResolvedPartitionFootprint(
        totalPartitionCount = 1,
        partitionDemandById = SortedMap(0 -> BigDecimal(1))
      ),
      indexMaintenancePlan = Vector.empty
    ))

  private def runReplicated(
                             config: DynamoDbTable.Config,
                             requestSource: Source[TimedElement[DynamoDBRequest], ?],
                             replicatedSource: Source[TimedElement[AdmittedRequestSample], ?]
                           ): (Seq[TimedEvent], Seq[TimedEvent], Seq[TimedEvent], Seq[TimedEvent]) =
    val responseSink = Sink.seq[TimedEvent]
    val consumptionSink = Sink.seq[TimedEvent]
    val metricSink = Sink.seq[TimedEvent]
    val outboundSink = Sink.seq[TimedEvent]

    val (rF, cF, mF, oF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, consumptionSink, metricSink, outboundSink)((r, c, m, o) => (r, c, m, o)) {
        implicit b => (respSink, consSink, metrSink, outboundSinkPort) =>
          import GraphDSL.Implicits.*
          val table = b.add(DynamoDbTable.componentOfReplicated(config))
          requestSource ~> table.requestIn
          replicatedSource ~> table.replicatedIn
          table.responseOut ~> respSink
          table.consumptionOut ~> consSink
          table.metricOut ~> metrSink
          table.outboundReplicationOut ~> outboundSinkPort
          ClosedShape
      }
    ).run()

    (
      Await.result(rF, 5.seconds),
      Await.result(cF, 5.seconds),
      Await.result(mF, 5.seconds),
      Await.result(oF, 5.seconds)
    )

  "DynamoDbTable.componentOfReplicated" should {

    "process client requests through admission and storage like componentOf" in {
      val (responses, consumption, _, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.single(GetItemRequest(SimTime.of(1L), usecase = "get-hit")),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      responses.collect { case _: GetItemResponse => 1 } should have size 1
      consumption.collect { case _: DynamoDbConsumptionEvent.ReadCapacityConsumed => 1 } should not be empty
      // Reads do not appear on the outbound replication output (only admitted writes do).
      outbound.collect { case _: AdmittedRequestSample => 1 } shouldBe empty
    }

    "emit validated admitted writes on the outbound replication output" in {
      val (responses, _, _, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      responses.collect { case _: PutItemResponse => 1 } should have size 1
      val outboundWrites = outbound.collect { case s: AdmittedPutItemSample => s }
      outboundWrites should have size 1
      outboundWrites.head.sample.writtenItemBytes shouldBe 1024L
    }

    "apply replicated writes through storage WITHOUT going through admission, accruing rWCU (not WCU)" in {
      val replicated = replicatedPut(SimTime.of(1L), 2048L)
      val (responses, consumption, metrics, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.empty[TimedElement[DynamoDBRequest]],
        replicatedSource = Source.single(replicated)
      )

      // Replicated write produced a PutItemResponse and accrued rWCU (not ordinary WCU).
      responses.collect { case r: PutItemResponse => r.storedItemBytes } shouldBe Vector(2048L)
      consumption.collect { case _: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed => 1 } should not be empty
      consumption.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } shouldBe empty

      // Metric stream includes a PutItemStored from storage. Note: admission stage was bypassed,
      // so no AdmissionMetricEvent.RequestAdmitted should appear for the replicated write.
      metrics.collect { case _: StorageMetricEvent.PutItemStored => 1 } should have size 1
      metrics.collect {
        case m: AdmissionMetricEvent.RequestAdmitted if m.usecase == "replicated-put" => 1
      } shouldBe empty

      // The replicated write does NOT re-emit on the outbound replication output. The outbound
      // port forks from admission.out0, which the replicated write bypasses. This loop-
      // prevention is critical: otherwise replicated writes would be re-replicated infinitely.
      outbound.collect { case _: AdmittedWriteRequestSample => 1 } shouldBe empty
    }

    "process both client requests and replicated writes in the same materialization, only forwarding client writes outbound" in {
      val replicated = replicatedPut(SimTime.of(1L), 4096L, usecase = "replicated-put")
      val (responses, _, metrics, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
        replicatedSource = Source.single(replicated)
      )

      // Two writes total: one from client requests, one from replicated input.
      responses.collect { case _: PutItemResponse => 1 }.size shouldBe 2
      metrics.collect { case _: StorageMetricEvent.PutItemStored => 1 }.size shouldBe 2

      // Outbound forwards ONLY the local-origin write (1024 bytes). The replicated write is
      // suppressed (Replicated[?] bypasses admission and never appears on the outbound port).
      val outboundBytes = outbound.collect { case s: AdmittedPutItemSample => s.sample.writtenItemBytes }.toSet
      outboundBytes shouldBe Set(1024L)
    }

    "route a GSI query to the GSI branch and produce GSI ReadCapacityConsumed, not base-table" in {
      val gsiName = "status-index"
      val configWithGsi = baseConfig.copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L),
          "gsi-query" -> FixedGsiQueryBehavior()
        )
      )

      val (responses, consumption, _, outbound) = runReplicated(
        configWithGsi,
        requestSource = Source.single(
          QueryRequest(SimTime.of(1L), usecase = "gsi-query",
            target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", gsiName))
        ),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      responses.collect { case _: QueryResponse => 1 } should have size 1
      // GSI read produces consumption for the GSI target, not the base table
      val gsiTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", gsiName)
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReadCapacityConsumed if e.target == gsiTarget => 1
      } should not be empty
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReadCapacityConsumed
          if e.target == DynamoDbTarget.Table("orders") => 1
      } shouldBe empty
      // Reads are never forwarded on the outbound replication port
      outbound.collect { case _: AdmittedWriteRequestSample => 1 } shouldBe empty
    }

    "replicated write with GSI triggers index maintenance accruing rWCU for the GSI target" in {
      val gsiName = "status-index"
      val gsiTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", gsiName)
      val configWithGsi = baseConfig.copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L)
        )
      )

      // Build a replicated put with a non-empty index maintenance plan so the maintenance
      // graph emits consumption for the GSI target.
      val plan = IndexMaintenancePlan(
        target = gsiTarget,
        action = IndexMaintenanceAction.InsertEntry,
        throughputDemand = BigDecimal(1),
        logicalPartitionAccess = SingleLogicalPartitionKey("pk"),
        resolvedPartitionFootprint = ResolvedPartitionFootprint(
          totalPartitionCount = 1,
          partitionDemandById = SortedMap(0 -> BigDecimal(1))
        ),
        newIndexEntryBytes = Some(64L),
        previousIndexEntryBytes = None,
        storageBytesDelta = 64L
      )
      val replicatedWithPlan = Replicated(AdmittedPutItemSample(
        req = PutItemRequest(eventTime = SimTime.of(1L), usecase = "replicated-put", itemBytes = 1024L),
        executionTarget = DynamoDbTarget.Table("orders"),
        admissionTarget = DynamoDbTarget.Table("orders"),
        sample = FixedPutItemSample(1024L),
        throughputDemand = BigDecimal(1),
        resolvedPartitionFootprint = ResolvedPartitionFootprint(
          totalPartitionCount = 1,
          partitionDemandById = SortedMap(0 -> BigDecimal(1))
        ),
        indexMaintenancePlan = Vector(plan)
      ))

      val (_, consumption, _, outbound) = runReplicated(
        configWithGsi,
        requestSource = Source.empty[TimedElement[DynamoDBRequest]],
        replicatedSource = Source.single(replicatedWithPlan)
      )

      // Index maintenance from a replicated write emits rWCU (not WCU) for the GSI target.
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed if e.target == gsiTarget => 1
      } should not be empty
      consumption.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == gsiTarget => 1
      } shouldBe empty
      // Replicated writes never re-appear on outbound.
      outbound.collect { case _: AdmittedWriteRequestSample => 1 } shouldBe empty
    }

    "route an LSI query to the LSI branch and produce LSI ReadCapacityConsumed, not base-table" in {
      val lsiName = "created-index"
      val lsiTarget = DynamoDbTarget.LocalSecondaryIndex("orders", lsiName)
      val configWithLsi = baseConfig.copy(
        localSecondaryIndexes = Vector(DynamoDbTable.LocalSecondaryIndexDefinition(lsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L),
          "lsi-query" -> FixedLsiQueryBehavior()
        )
      )

      val (responses, consumption, _, outbound) = runReplicated(
        configWithLsi,
        requestSource = Source.single(
          QueryRequest(SimTime.of(1L), usecase = "lsi-query",
            target = DynamoDbReadTarget.LocalSecondaryIndex("orders", lsiName))
        ),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      responses.collect { case _: QueryResponse => 1 } should have size 1
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReadCapacityConsumed if e.target == lsiTarget => 1
      } should not be empty
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReadCapacityConsumed
          if e.target == DynamoDbTarget.Table("orders") => 1
      } shouldBe empty
      outbound.collect { case _: AdmittedWriteRequestSample => 1 } shouldBe empty
    }

    "replicated write with LSI triggers index maintenance accruing rWCU for the LSI target" in {
      val lsiName = "created-index"
      val lsiTarget = DynamoDbTarget.LocalSecondaryIndex("orders", lsiName)
      val configWithLsi = baseConfig.copy(
        localSecondaryIndexes = Vector(DynamoDbTable.LocalSecondaryIndexDefinition(lsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L)
        )
      )

      val plan = IndexMaintenancePlan(
        target = lsiTarget,
        action = IndexMaintenanceAction.InsertEntry,
        throughputDemand = BigDecimal(1),
        logicalPartitionAccess = SingleLogicalPartitionKey("pk"),
        resolvedPartitionFootprint = ResolvedPartitionFootprint(
          totalPartitionCount = 1,
          partitionDemandById = SortedMap(0 -> BigDecimal(1))
        ),
        newIndexEntryBytes = Some(128L),
        previousIndexEntryBytes = None,
        storageBytesDelta = 128L
      )
      val replicatedWithLsiPlan = Replicated(AdmittedPutItemSample(
        req = PutItemRequest(eventTime = SimTime.of(1L), usecase = "replicated-put", itemBytes = 1024L),
        executionTarget = DynamoDbTarget.Table("orders"),
        admissionTarget = DynamoDbTarget.Table("orders"),
        sample = FixedPutItemSample(1024L),
        throughputDemand = BigDecimal(1),
        resolvedPartitionFootprint = ResolvedPartitionFootprint(
          totalPartitionCount = 1,
          partitionDemandById = SortedMap(0 -> BigDecimal(1))
        ),
        indexMaintenancePlan = Vector(plan)
      ))

      val (_, consumption, _, outbound) = runReplicated(
        configWithLsi,
        requestSource = Source.empty[TimedElement[DynamoDBRequest]],
        replicatedSource = Source.single(replicatedWithLsiPlan)
      )

      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed if e.target == lsiTarget => 1
      } should not be empty
      consumption.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == lsiTarget => 1
      } shouldBe empty
      outbound.collect { case _: AdmittedWriteRequestSample => 1 } shouldBe empty
    }

    "client write with GSI accrues WCU for both base-table target and GSI target at origin" in {
      val gsiName = "status-index"
      val gsiTarget = DynamoDbTarget.GlobalSecondaryIndex("orders", gsiName)
      val configWithGsi = baseConfig.copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L)
        )
      )

      val (responses, consumption, _, outbound) = runReplicated(
        configWithGsi,
        requestSource = Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      responses.collect { case _: PutItemResponse => 1 } should have size 1
      // Base-table write capacity consumed at the base table target
      consumption.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed
          if e.target == DynamoDbTarget.Table("orders") => 1
      } should not be empty
      // Index maintenance at the GSI target also accrues WCU (client write, not replicated)
      consumption.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == gsiTarget => 1
      } should not be empty
      // No rWCU for a client (non-replicated) write
      consumption.collect {
        case e: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed => 1
      } shouldBe empty
      // Client write appears on outbound for replication
      outbound.collect { case _: AdmittedPutItemSample => 1 } should have size 1
    }

    "GSI write back-pressure throttles a base-table write when GSI write limit is saturated" in {
      val gsiName = "status-index"
      val configWithTightGsi = baseConfig.copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        billingMode = DynamoDbTable.BillingMode.OnDemand(DynamoDbTable.OnDemandMaxThroughput(
          tableMaxWriteRequestUnitsPerSecond = Some(BigDecimal(10)),
          globalSecondaryIndexMaxWriteRequestUnitsPerSecond = Map(gsiName -> BigDecimal("0.5"))
        )),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L),
          "get-hit" -> FixedHitGetItemBehavior(itemBytes = 512L)
        )
      )

      val (responses, consumption, _, _) = runReplicated(
        configWithTightGsi,
        requestSource = Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
        replicatedSource = Source.empty[TimedElement[AdmittedRequestSample]]
      )

      // The write should be throttled because the GSI write limit (0.5) is instantly saturated.
      responses.collect { case r: ThrottledResponse => r.reason } shouldBe Vector(
        DynamoDbThrottleReason.GlobalSecondaryIndexWriteMaxOnDemandThroughputExceeded
      )
      // Throttled writes produce no consumption events.
      consumption.collect { case _: DynamoDbConsumptionEvent => 1 } shouldBe empty
    }
  }
