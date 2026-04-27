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
    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(writtenItemBytes, previousItemBytes)

  private case class FixedHitGetItemBehavior(itemBytes: Long) extends UseCaseSampler[TableState]:
    override def getItem(request: GetItemRequest, state: TableState): GetItemSample =
      GetItemSample(itemBytes = Some(itemBytes))

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

  private def admittedReplicatedPut(
                                     eventTime: SimTime,
                                     itemBytes: Long,
                                     usecase: Any = "replicated-put"
                                   ): AdmittedPutItemSample =
    AdmittedPutItemSample(
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
    )

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

    "apply replicated writes through storage WITHOUT going through admission, accruing destination consumption" in {
      val replicated = admittedReplicatedPut(SimTime.of(1L), 2048L)
      val (responses, consumption, metrics, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.empty[TimedElement[DynamoDBRequest]],
        replicatedSource = Source.single(replicated)
      )

      // Replicated write produced a PutItemResponse and accrued WCU consumption.
      responses.collect { case r: PutItemResponse => r.storedItemBytes } shouldBe Vector(2048L)
      consumption.collect { case _: DynamoDbConsumptionEvent.WriteCapacityConsumed => 1 } should not be empty

      // Metric stream includes a PutItemStored from storage. Note: admission stage was bypassed,
      // so no AdmissionMetricEvent.RequestAdmitted should appear for the replicated write.
      metrics.collect { case _: StorageMetricEvent.PutItemStored => 1 } should have size 1
      metrics.collect {
        case m: AdmissionMetricEvent.RequestAdmitted if m.usecase == "replicated-put" => 1
      } shouldBe empty

      // The replicated write does NOT re-emit on the outbound replication output. The outbound
      // port forks from admission.out0, which the replicated write bypasses. This loop-
      // prevention is critical: otherwise replicated writes would be re-replicated infinitely.
      outbound.collect { case _: AdmittedPutItemSample => 1 } shouldBe empty
    }

    "process both client requests and replicated writes in the same materialization, only forwarding client writes outbound" in {
      val replicated = admittedReplicatedPut(SimTime.of(1L), 4096L, usecase = "replicated-put")
      val (responses, _, metrics, outbound) = runReplicated(
        baseConfig,
        requestSource = Source.single(PutItemRequest(SimTime.of(1L), usecase = "put-new", itemBytes = 1024L)),
        replicatedSource = Source.single(replicated)
      )

      // Two writes total: one from client requests, one from replicated input.
      responses.collect { case _: PutItemResponse => 1 }.size shouldBe 2
      metrics.collect { case _: StorageMetricEvent.PutItemStored => 1 }.size shouldBe 2

      // Outbound forwards ONLY the local-origin write (1024 bytes). The replicated-write's
      // applied effect (4096 bytes) is suppressed to prevent the replication loop.
      val outboundBytes = outbound.collect { case s: AdmittedPutItemSample => s.sample.writtenItemBytes }.toSet
      outboundBytes shouldBe Set(1024L)
    }

    "reject configurations with GSIs in slice 10" in {
      val configWithGsi = baseConfig.copy(
        globalSecondaryIndexes = Vector(DynamoDbTable.GlobalSecondaryIndexDefinition("status-index"))
      )
      an[IllegalArgumentException] should be thrownBy {
        DynamoDbTable.componentOfReplicated(configWithGsi)
      }
    }

    "reject configurations with LSIs in slice 10" in {
      val configWithLsi = baseConfig.copy(
        localSecondaryIndexes = Vector(DynamoDbTable.LocalSecondaryIndexDefinition("created-index"))
      )
      an[IllegalArgumentException] should be thrownBy {
        DynamoDbTable.componentOfReplicated(configWithLsi)
      }
    }
  }
