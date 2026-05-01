package stochastacy.aws.dynamodb.table

import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.ContinuousDistribution
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class DynamoDbGlobalTableSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("dynamodb-global-table-test")
  given Materializer = Materializer.matFromSystem

  private case class FixedPutItemSample(
                                         override val writtenItemBytes: Long,
                                         override val previousItemBytes: Option[Long] = None,
                                         override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("pk")
                                       ) extends PutItemSample

  private case class FixedPutItemBehavior(writtenItemBytes: Long) extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      FixedPutItemSample(writtenItemBytes)

  private case class FixedHitGetItemBehavior(itemBytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = Some(itemBytes))

  private case class FixedGsiQueryBehavior() extends UseCaseSampler[TableState]:
    override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
      QuerySample(
        evaluatedItemCount = 1,
        evaluatedBytes = 12288L,
        returnedItemCount = 1,
        returnedBytes = 64L,
        logicalPartitionAccess = SingleLogicalPartitionKey("k")
      )

  /** Constant-zero distribution: every sample returns 0 (immediate replication). */
  private def zeroLagDistribution: ContinuousDistribution =
    new ContinuousDistribution:
      override def density(x: Double): Double = if x == 0.0 then Double.PositiveInfinity else 0.0
      override def probability(x0: Double, x1: Double): Double = if x0 <= 0.0 && x1 >= 0.0 then 1.0 else 0.0
      override def cumulativeProbability(x: Double): Double = if x >= 0.0 then 1.0 else 0.0
      override def inverseCumulativeProbability(p: Double): Double = 0.0
      override def getMean: Double = 0.0
      override def getVariance: Double = 0.0
      override def getSupportLowerBound: Double = 0.0
      override def getSupportUpperBound: Double = 0.0
      override def createSampler(rng: org.apache.commons.rng.UniformRandomProvider): ContinuousDistribution.Sampler =
        () => 0.0

  private def regionConfig(tableName: String = "orders"): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = tableName,
      stateModel = FixedTableState(0L, 0L),
      useCaseBehaviors = Map(
        "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L)
      ),
      readConsistency = ReadConsistency.StronglyConsistent
    )

  private def globalConfig(
                            regions: Seq[String],
                            replicationModel: ReplicationModel
                          ): DynamoDbGlobalTable.Config =
    DynamoDbGlobalTable.Config(
      regions = regions.map(r => r -> regionConfig()).toMap,
      replicationModel = replicationModel
    )

  private def runManagedGlobal(
                                config: DynamoDbGlobalTable.Config,
                                requestSources: Map[String, Source[TimedElement[DynamoDBRequest], ?]],
                                managementSource: Source[TimedElement[DynamoDbManagementEvent], ?]
                              ): (Map[String, Seq[TimedEvent]], Map[String, Seq[TimedEvent]], Seq[TimedEvent]) =
    val regions = config.regions.keys.toVector.sorted
    require(regions.size == 2, s"runManagedGlobal currently supports exactly 2 regions, got ${regions.size}")

    val responseSinkA = Sink.seq[TimedEvent]
    val responseSinkB = Sink.seq[TimedEvent]
    val metricSinkA = Sink.seq[TimedEvent]
    val metricSinkB = Sink.seq[TimedEvent]
    val transferSink = Sink.seq[TimedEvent]

    val regionA = regions(0)
    val regionB = regions(1)

    val (responseAF, responseBF, metricAF, metricBF, transferF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSinkA, responseSinkB, metricSinkA, metricSinkB, transferSink)(
        (ra, rb, ma, mb, t) => (ra, rb, ma, mb, t)
      ) { implicit builder => (respAS, respBS, metAS, metBS, transferS) =>
        import GraphDSL.Implicits.*
        val table = builder.add(DynamoDbGlobalTable.componentOfManaged(config))

        requestSources.getOrElse(regionA, Source.empty[TimedElement[DynamoDBRequest]]) ~> table.regionRequestInlets(regionA)
        requestSources.getOrElse(regionB, Source.empty[TimedElement[DynamoDBRequest]]) ~> table.regionRequestInlets(regionB)
        managementSource ~> table.managementIn

        table.regionResponseOutlets(regionA) ~> respAS
        table.regionResponseOutlets(regionB) ~> respBS
        table.regionConsumptionOutlets(regionA) ~> builder.add(Sink.ignore)
        table.regionConsumptionOutlets(regionB) ~> builder.add(Sink.ignore)
        table.regionMetricOutlets(regionA) ~> metAS
        table.regionMetricOutlets(regionB) ~> metBS
        table.transferEventsOutlet ~> transferS
        ClosedShape
      }
    ).run()

    (
      Map(
        regionA -> Await.result(responseAF, 5.seconds),
        regionB -> Await.result(responseBF, 5.seconds)
      ),
      Map(
        regionA -> Await.result(metricAF, 5.seconds),
        regionB -> Await.result(metricBF, 5.seconds)
      ),
      Await.result(transferF, 5.seconds)
    )

  "DynamoDbGlobalTable" should {

    "fan a write from one region to all peers with zero-lag replication" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(1L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val regions = Seq("ap-southeast-2", "eu-west-1", "us-east-1")  // alphabetical sort order
      val config = globalConfig(regions, model)

      // 3 regions × 1 response sink each + 1 transfer = 4 sinks. Capture responses + transfer.
      val sinkAp = Sink.seq[TimedEvent]
      val sinkEu = Sink.seq[TimedEvent]
      val sinkUs = Sink.seq[TimedEvent]
      val sinkTransfer = Sink.seq[TimedEvent]

      val (apF, euF, usF, transferF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkAp, sinkEu, sinkUs, sinkTransfer)((a, e, u, t) => (a, e, u, t)) {
          implicit builder => (apS, euS, usS, tS) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))

            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
            ) ~> table.regionRequestInlets("us-east-1")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("eu-west-1")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("ap-southeast-2")

            table.regionResponseOutlets("ap-southeast-2") ~> apS
            table.regionResponseOutlets("eu-west-1") ~> euS
            table.regionResponseOutlets("us-east-1") ~> usS
            // Drop consumption + metric from each region; we don't assert on them in this test.
            table.regionConsumptionOutlets("ap-southeast-2") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("eu-west-1") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("us-east-1") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("ap-southeast-2") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("eu-west-1") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("us-east-1") ~> builder.add(Sink.ignore)

            table.transferEventsOutlet ~> tS

            ClosedShape
        }
      ).run()

      val apResponses = Await.result(apF, 5.seconds)
      val euResponses = Await.result(euF, 5.seconds)
      val usResponses = Await.result(usF, 5.seconds)
      val transferEvents = Await.result(transferF, 5.seconds)

      // Origin region applied the local write.
      usResponses.collect { case _: PutItemResponse => 1 }.size shouldBe 1

      // Both peer regions also applied the replicated write (zero lag → same tick).
      euResponses.collect { case _: PutItemResponse => 1 }.size shouldBe 1
      apResponses.collect { case _: PutItemResponse => 1 }.size shouldBe 1

      // Transfer events: 2 (us-east-1 → eu-west-1, us-east-1 → ap-southeast-2). All from
      // sourceService = "DynamoDB", carrying 1024 bytes each.
      val transfers = transferEvents.collect { case e: CrossRegionTransferEvent => e }
      transfers should have size 2
      transfers.map(_.sourceRegion).toSet shouldBe Set("us-east-1")
      transfers.map(_.destinationRegion).toSet shouldBe Set("eu-west-1", "ap-southeast-2")
      transfers.foreach { e =>
        e.sourceService shouldBe "DynamoDB"
        e.bytes shouldBe 1024L
      }
    }

    "produce no replication when only one region is configured (degenerate single-region 'global' table)" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(2L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val config = globalConfig(Seq("us-east-1"), model)

      val sinkResp = Sink.seq[TimedEvent]
      val sinkTransfer = Sink.seq[TimedEvent]

      val (respF, transferF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkResp, sinkTransfer)((r, t) => (r, t)) {
          implicit builder => (rS, tS) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))
            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
            ) ~> table.regionRequestInlets("us-east-1")
            table.regionResponseOutlets("us-east-1") ~> rS
            table.regionConsumptionOutlets("us-east-1") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("us-east-1") ~> builder.add(Sink.ignore)
            table.transferEventsOutlet ~> tS
            ClosedShape
        }
      ).run()

      val responses = Await.result(respF, 5.seconds)
      val transferEvents = Await.result(transferF, 5.seconds)

      responses.collect { case _: PutItemResponse => 1 }.size shouldBe 1
      transferEvents.collect { case _: CrossRegionTransferEvent => 1 } shouldBe empty
    }

    "produce a single transfer event per replicated write in a 2-region setup" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(3L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val config = globalConfig(Seq("a", "b"), model)

      val sinkA = Sink.seq[TimedEvent]
      val sinkB = Sink.seq[TimedEvent]
      val sinkTransfer = Sink.seq[TimedEvent]

      val (aF, bF, transferF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkA, sinkB, sinkTransfer)((a, b, t) => (a, b, t)) {
          implicit builder => (aS, bS, tS) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))
            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 256L)
            ) ~> table.regionRequestInlets("a")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("b")
            table.regionResponseOutlets("a") ~> aS
            table.regionResponseOutlets("b") ~> bS
            table.regionConsumptionOutlets("a") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("b") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("a") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("b") ~> builder.add(Sink.ignore)
            table.transferEventsOutlet ~> tS
            ClosedShape
        }
      ).run()

      val aResp = Await.result(aF, 5.seconds)
      val bResp = Await.result(bF, 5.seconds)
      val transferEvents = Await.result(transferF, 5.seconds)

      aResp.collect { case _: PutItemResponse => 1 }.size shouldBe 1
      bResp.collect { case _: PutItemResponse => 1 }.size shouldBe 1
      val transfers = transferEvents.collect { case e: CrossRegionTransferEvent => e }
      transfers should have size 1
      transfers.head.sourceRegion shouldBe "a"
      transfers.head.destinationRegion shouldBe "b"
      // Bytes come from the sampler (FixedPutItemBehavior.writtenItemBytes = 1024),
      // not from the request's itemBytes field.
      transfers.head.bytes shouldBe 1024L
    }

    "origin region emits WriteCapacityConsumed; peer region emits ReplicatedWriteCapacityConsumed" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(10L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val config = globalConfig(Seq("a", "b"), model)

      val sinkConsA = Sink.seq[TimedEvent]
      val sinkConsB = Sink.seq[TimedEvent]

      val (consAF, consBF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkConsA, sinkConsB)((a, b) => (a, b)) {
          implicit builder => (consAS, consBs) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))
            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
            ) ~> table.regionRequestInlets("a")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("b")
            table.regionResponseOutlets("a") ~> builder.add(Sink.ignore)
            table.regionResponseOutlets("b") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("a") ~> consAS
            table.regionConsumptionOutlets("b") ~> consBs
            table.regionMetricOutlets("a") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("b") ~> builder.add(Sink.ignore)
            table.transferEventsOutlet ~> builder.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val consA = Await.result(consAF, 5.seconds)
      val consB = Await.result(consBF, 5.seconds)

      // Origin region (a): local write bills as WCU, not rWCU.
      consA.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } should not be empty
      consA.collect { case _: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed => 1 } shouldBe empty

      // Peer region (b): inbound replicated write bills as rWCU, not WCU.
      consB.collect { case _: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed => 1 } should not be empty
      consB.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } shouldBe empty
    }

    "global table with a GSI: peer region accrues rWCU for GSI target on replicated write" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(99L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val gsiName = "status-index"
      val gsiConfig = regionConfig().copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L)
        )
      )
      val config = DynamoDbGlobalTable.Config(
        regions = Map("a" -> gsiConfig, "b" -> gsiConfig),
        replicationModel = model
      )

      val sinkConsA = Sink.seq[TimedEvent]
      val sinkConsB = Sink.seq[TimedEvent]

      val (consAF, consBF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkConsA, sinkConsB)((a, b) => (a, b)) {
          implicit builder => (consAS, consBs) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))
            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
            ) ~> table.regionRequestInlets("a")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("b")
            table.regionResponseOutlets("a") ~> builder.add(Sink.ignore)
            table.regionResponseOutlets("b") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("a") ~> consAS
            table.regionConsumptionOutlets("b") ~> consBs
            table.regionMetricOutlets("a") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("b") ~> builder.add(Sink.ignore)
            table.transferEventsOutlet ~> builder.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val consA = Await.result(consAF, 5.seconds)
      val consB = Await.result(consBF, 5.seconds)

      val gsiTarget = DynamoDbTarget.GlobalSecondaryIndex(gsiConfig.tableName, gsiName)

      // Origin region (a): GSI maintenance emits WCU for the GSI target (client write).
      consA.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == gsiTarget => 1
      } should not be empty

      // Peer region (b): replicated write triggers GSI maintenance emitting rWCU, not WCU.
      consB.collect {
        case e: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed if e.target == gsiTarget => 1
      } should not be empty
      consB.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == gsiTarget => 1
      } shouldBe empty
    }

    "global table with an LSI: peer region accrues rWCU for LSI target on replicated write" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(100L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      val lsiName = "created-index"
      val lsiConfig = regionConfig().copy(
        localSecondaryIndexes = Vector(DynamoDbTable.LocalSecondaryIndexDefinition(lsiName)),
        useCaseBehaviors = Map(
          "put-new" -> FixedPutItemBehavior(writtenItemBytes = 1024L)
        )
      )
      val config = DynamoDbGlobalTable.Config(
        regions = Map("a" -> lsiConfig, "b" -> lsiConfig),
        replicationModel = model
      )

      val sinkConsA = Sink.seq[TimedEvent]
      val sinkConsB = Sink.seq[TimedEvent]

      val (consAF, consBF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(sinkConsA, sinkConsB)((a, b) => (a, b)) {
          implicit builder => (consAS, consBs) =>
            import GraphDSL.Implicits.*
            val table = builder.add(DynamoDbGlobalTable.componentOf(config))
            Source.single[TimedElement[DynamoDBRequest]](
              PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)
            ) ~> table.regionRequestInlets("a")
            Source.empty[TimedElement[DynamoDBRequest]] ~> table.regionRequestInlets("b")
            table.regionResponseOutlets("a") ~> builder.add(Sink.ignore)
            table.regionResponseOutlets("b") ~> builder.add(Sink.ignore)
            table.regionConsumptionOutlets("a") ~> consAS
            table.regionConsumptionOutlets("b") ~> consBs
            table.regionMetricOutlets("a") ~> builder.add(Sink.ignore)
            table.regionMetricOutlets("b") ~> builder.add(Sink.ignore)
            table.transferEventsOutlet ~> builder.add(Sink.ignore)
            ClosedShape
        }
      ).run()

      val consA = Await.result(consAF, 5.seconds)
      val consB = Await.result(consBF, 5.seconds)

      val lsiTarget = DynamoDbTarget.LocalSecondaryIndex(lsiConfig.tableName, lsiName)

      // Origin region (a): LSI maintenance emits WCU for the LSI target (client write).
      consA.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == lsiTarget => 1
      } should not be empty

      // Peer region (b): replicated write triggers LSI maintenance emitting rWCU, not WCU.
      consB.collect {
        case e: DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed if e.target == lsiTarget => 1
      } should not be empty
      consB.collect {
        case e: DynamoDbConsumptionEvent.WriteCapacityConsumed if e.target == lsiTarget => 1
      } shouldBe empty
    }

    "reject an empty regions map" in {
      val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(101L)
      val model = ReplicationModel(
        defaultLagDistribution = Some(zeroLagDistribution),
        rng = rng
      )
      an[IllegalArgumentException] should be thrownBy {
        DynamoDbGlobalTable.Config(
          regions = Map.empty,
          replicationModel = model
        )
      }
    }

    "componentOfManaged broadcasts a billing-mode switch to all replicas" in {
      val config = DynamoDbGlobalTable.Config(
        regions = Map(
          "us-east-1" -> DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(1L, 5120L),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(5120L)),
            readConsistency = ReadConsistency.StronglyConsistent
          ),
          "eu-west-1" -> DynamoDbTable.Config(
            tableName = "orders",
            stateModel = FixedTableState(1L, 5120L),
            useCaseBehaviors = Map("get-hit" -> FixedHitGetItemBehavior(5120L)),
            readConsistency = ReadConsistency.StronglyConsistent
          )
        ),
        replicationModel = ReplicationModel(
          defaultLagDistribution = Some(zeroLagDistribution),
          rng = RandomSource.XO_RO_SHI_RO_128_PP.create(111L)
        )
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val switchEvent = DynamoDbManagementEvent.SwitchBillingMode(
        SimTime.of(10L),
        "switch",
        DynamoDbTable.BillingMode.Provisioned(1L, 1L)
      )
      val requestStream =
        Vector.tabulate(50)(idx => TimedControlEvent.Tick(SimTime.of(idx.toLong + 1L)): TimedElement[DynamoDBRequest]) :+
          GetItemRequest(SimTime.of(50L), "get-hit")

      val (responsesByRegion, metricsByRegion, _) = runManagedGlobal(
        config,
        requestSources = Map(
          "us-east-1" -> Source(requestStream),
          "eu-west-1" -> Source(requestStream)
        ),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](tick1, switchEvent, TimedControlEvent.Tick(SimTime.of(50L))))
      )

      responsesByRegion.values.foreach { responses =>
        responses.collect { case t: ThrottledResponse => t.reason } shouldBe Vector(
          DynamoDbThrottleReason.TableReadProvisionedThroughputExceeded
        )
      }
      metricsByRegion.values.foreach { metrics =>
        metrics.collect { case _: AdmissionMetricEvent.BillingModeSwitched => 1 } should not be empty
      }
    }

    "componentOfManaged broadcasts provisioned-capacity changes to all replicas" in {
      val gsiName = "status-index"
      val provisioned = DynamoDbTable.BillingMode.Provisioned(
        readCapacityUnits = 1000L,
        writeCapacityUnits = 100L,
        globalSecondaryIndexReadCapacityUnits = Map(gsiName -> 1000L)
      )
      val regionConfigWithGsi = DynamoDbTable.Config(
        tableName = "orders",
        stateModel = FixedTableState(1L, 512L),
        useCaseBehaviors = Map("gsi-query" -> FixedGsiQueryBehavior()),
        readConsistency = ReadConsistency.StronglyConsistent,
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition(gsiName)),
        billingMode = provisioned
      )
      val config = DynamoDbGlobalTable.Config(
        regions = Map("us-east-1" -> regionConfigWithGsi, "eu-west-1" -> regionConfigWithGsi),
        replicationModel = ReplicationModel(
          defaultLagDistribution = Some(zeroLagDistribution),
          rng = RandomSource.XO_RO_SHI_RO_128_PP.create(112L)
        )
      )

      val tick1 = TimedControlEvent.Tick(SimTime.of(1L))
      val updateEvent = DynamoDbManagementEvent.UpdateProvisionedCapacity(
        SimTime.of(10L),
        "reduce-gsi",
        DynamoDbTable.BillingMode.Provisioned(
          readCapacityUnits = 1000L,
          writeCapacityUnits = 100L,
          globalSecondaryIndexReadCapacityUnits = Map(gsiName -> 1L)
        )
      )
      val requestStream =
        Vector.tabulate(50)(idx => TimedControlEvent.Tick(SimTime.of(idx.toLong + 1L)): TimedElement[DynamoDBRequest]) :+
          QueryRequest(SimTime.of(50L), "gsi-query", target = DynamoDbReadTarget.GlobalSecondaryIndex("orders", gsiName))

      val (responsesByRegion, metricsByRegion, _) = runManagedGlobal(
        config,
        requestSources = Map(
          "us-east-1" -> Source(requestStream),
          "eu-west-1" -> Source(requestStream)
        ),
        managementSource = Source(Vector[TimedElement[DynamoDbManagementEvent]](tick1, updateEvent, TimedControlEvent.Tick(SimTime.of(50L))))
      )

      responsesByRegion.values.foreach { responses =>
        responses.collect { case t: ThrottledResponse => t.reason } shouldBe Vector(
          DynamoDbThrottleReason.GlobalSecondaryIndexReadProvisionedThroughputExceeded
        )
      }
      metricsByRegion.values.foreach { metrics =>
        metrics.collect { case _: AdmissionMetricEvent.ProvisionedCapacityChanged => 1 } should not be empty
      }
    }
  }
