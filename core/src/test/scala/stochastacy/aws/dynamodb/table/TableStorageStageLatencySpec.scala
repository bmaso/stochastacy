package stochastacy.aws.dynamodb.table

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedElement, TimedEvent}

import scala.collection.immutable.SortedMap
import scala.concurrent.Await
import scala.concurrent.duration.*

class TableStorageStageLatencySpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("latency-spec")
  given Materializer = Materializer.matFromSystem

  import LogicalPartitionAccess.*

  private val alwaysZeroRng: UniformRandomProvider = new UniformRandomProvider:
    override def nextInt(): Int = 0
    override def nextInt(n: Int): Int = 0
    override def nextLong(): Long = 0L
    override def nextLong(n: Long): Long = 0L
    override def nextBoolean(): Boolean = false
    override def nextFloat(): Float = 0.0f
    override def nextDouble(): Double = 0.0
    override def nextBytes(bytes: Array[Byte]): Unit = java.util.Arrays.fill(bytes, 0.toByte)
    override def nextBytes(bytes: Array[Byte], start: Int, len: Int): Unit =
      java.util.Arrays.fill(bytes, start, start + len, 0.toByte)

  "TableStorageStage SuccessfulRequestLatency" should {

    "emit SuccessfulRequestLatency with positive value for an admitted GetItem" in {
      val (_, _, metrics) = runAndCollect(
        Source.single(GetItemRequest(SimTime.of(1L), usecase = "q")),
        baseConfig()
      )
      val lat = metrics.collect { case e: StorageMetricEvent.SuccessfulRequestLatency => e }
      lat should have size 1
      lat.head.operation shouldBe DynamoDbOperationKind.GetItem
      lat.head.latencyMs should be > 0.0
    }

    "emit SuccessfulRequestLatency with PutItem operation kind for an admitted PutItem" in {
      val (_, _, metrics) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "q", itemBytes = 512L)),
        baseConfig()
      )
      val lat = metrics.collect { case e: StorageMetricEvent.SuccessfulRequestLatency => e }
      lat should have size 1
      lat.head.operation shouldBe DynamoDbOperationKind.PutItem
      lat.head.latencyMs should be > 0.0
    }

    "not emit SuccessfulRequestLatency when a write is rejected by the LSI item-collection limit" in {
      val config = baseConfig(
        lsi = true,
        useCases = Map("q" -> SizingPutBehavior(writtenItemBytes = 5_000L, currentItemCollectionBytes = 9_000L)),
        itemCollectionSizeLimitBytes = Some(10_000L)
      )
      val (_, _, metrics) = runAndCollect(
        Source.single(PutItemRequest(SimTime.of(1L), usecase = "q", itemBytes = 5_000L)),
        config
      )
      metrics.collect { case _: StorageMetricEvent.SuccessfulRequestLatency => 1 } shouldBe empty
    }

    "not emit SuccessfulRequestLatency when a system error is simulated" in {
      val admitted = admittedPut(tick = 1L, bytes = 512L)
      val metrics = runAdmitted(
        requests = Seq(admitted),
        systemErrorRate = 0.999,
        errorRng = Some(alwaysZeroRng)
      )
      metrics.collect { case _: StorageMetricEvent.SystemError => 1 } should have size 1
      metrics.collect { case _: StorageMetricEvent.SuccessfulRequestLatency => 1 } shouldBe empty
    }

    "use provided latencyModel params (custom model emits different distribution mean)" in {
      val customModel = DynamoDbTable.LatencyModel(Map(
        DynamoDbOperationKind.GetItem -> DynamoDbTable.LatencyParams(math.log(100.0), 0.01)
      ))
      val config = baseConfig().copy(latencyModel = customModel)
      val (_, _, metrics) = runAndCollect(
        Source.single(GetItemRequest(SimTime.of(1L), usecase = "q")),
        config
      )
      val lat = metrics.collect { case e: StorageMetricEvent.SuccessfulRequestLatency => e }
      lat should have size 1
      lat.head.latencyMs should be > 50.0
    }
  }

  private def baseConfig(
    lsi: Boolean = false,
    useCases: Map[Any, UseCaseSampler[TableState]] = Map("q" -> DefaultBehavior),
    itemCollectionSizeLimitBytes: Option[Long] = None
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = "orders",
      stateModel = SummaryTableState(0L, 0L),
      useCaseBehaviors = useCases,
      localSecondaryIndexes =
        if lsi then Vector(DynamoDbTable.LocalSecondaryIndexDefinition("lsi1")) else Vector.empty,
      itemCollectionSizeLimitBytes = itemCollectionSizeLimitBytes
    )

  private def runAndCollect(
    requestSource: Source[TimedElement[DynamoDBRequest], ?],
    config: DynamoDbTable.Config
  ): (Seq[TimedEvent], Seq[TimedEvent], Seq[TimedEvent]) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink  = Sink.seq[TimedEvent]

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

  private def admittedPut(tick: Long, bytes: Long): AdmittedPutItemSample =
    AdmittedPutItemSample(
      req = PutItemRequest(SimTime.of(tick), usecase = "test", itemBytes = bytes),
      executionTarget = DynamoDbTarget.Table("orders"),
      admissionTarget = DynamoDbTarget.Table("orders"),
      sample = FixedPutItemSample(bytes),
      throughputDemand = BigDecimal(1),
      resolvedPartitionFootprint = ResolvedPartitionFootprint(1, SortedMap(0 -> BigDecimal(1))),
      indexMaintenancePlan = Vector.empty
    )

  private def runAdmitted(
    requests: Seq[AdmittedPutItemSample],
    systemErrorRate: Double,
    errorRng: Option[UniformRandomProvider]
  ): Vector[StorageMetricEvent] =
    val metricSink = Sink.seq[TimedEvent]
    val ignoreSink = Sink.ignore

    val (mF, _, _, _) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(metricSink, ignoreSink, ignoreSink, ignoreSink)(
        (m, r, c, v) => (m, r, c, v)
      ) { implicit b =>
        (metrSink, respSink, consSink, valSink) =>
          import GraphDSL.Implicits.*
          val stage = b.add(
            TableStorageStage.componentOfAdmitted(
              stateModel = SummaryTableState(0L, 0L),
              systemErrorRate = systemErrorRate,
              rng = errorRng
            )
          )
          Source(requests.toVector.map(r => r: AdmittedRequestSample)) ~> stage.in
          stage.out0 ~> respSink
          stage.out1 ~> consSink
          stage.out2 ~> metrSink
          stage.out3 ~> valSink
          ClosedShape
      }
    ).run()

    Await.result(mF, 5.seconds).collect { case e: StorageMetricEvent => e }.toVector

  private object DefaultBehavior extends UseCaseSampler[TableState]:
    override def getItem(req: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
      GetItemSample(itemBytes = Some(256L))
    override def putItem(req: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      FixedPutItemSample(writtenItemBytes = req.itemBytes, previousItemBytes = None)

  private case class FixedPutItemSample(
    override val writtenItemBytes: Long,
    override val previousItemBytes: Option[Long] = None,
    override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("pk")
  ) extends PutItemSample

  private case class SizingPutSample(
    override val writtenItemBytes: Long,
    override val previousItemBytes: Option[Long],
    override val currentItemCollectionBytes: Long,
    override val logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("pk")
  ) extends PutItemSample

  private case class SizingPutBehavior(
    writtenItemBytes: Long,
    currentItemCollectionBytes: Long
  ) extends UseCaseSampler[TableState]:
    override def putItem(req: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      SizingPutSample(writtenItemBytes, None, currentItemCollectionBytes)
