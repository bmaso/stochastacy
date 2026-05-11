package stochastacy.aws.dynamodb.table

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedEvent}

import scala.collection.immutable.SortedMap
import scala.concurrent.Await
import scala.concurrent.duration.*

class TableStorageSystemErrorSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem = ActorSystem("table-storage-system-error-test")
  given Materializer = Materializer.matFromSystem

  private case class FixedPutItemSample(
    override val writtenItemBytes: Long,
    override val previousItemBytes: Option[Long] = None,
    override val logicalPartitionAccess: LogicalPartitionAccess = LogicalPartitionAccess.SingleLogicalPartitionKey("k")
  ) extends PutItemSample

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

  private def admittedPut(tick: Long, bytes: Long): AdmittedPutItemSample =
    AdmittedPutItemSample(
      req = PutItemRequest(SimTime.of(tick), usecase = "test", itemBytes = bytes),
      executionTarget = DynamoDbTarget.Table("t"),
      admissionTarget = DynamoDbTarget.Table("t"),
      sample = FixedPutItemSample(bytes),
      throughputDemand = BigDecimal(1),
      resolvedPartitionFootprint = ResolvedPartitionFootprint(1, SortedMap(0 -> BigDecimal(1))),
      indexMaintenancePlan = Vector.empty
    )

  private def runAdmitted(
    requests: Seq[AdmittedPutItemSample],
    systemErrorRate: Double,
    rng: Option[UniformRandomProvider],
    stateModel: TableState = SummaryTableState(0L, 0L)
  ): (Vector[DynamoDBResponse], Vector[DynamoDbConsumptionEvent], Vector[StorageMetricEvent], Vector[AdmittedRequestSample]) =
    val responseSink = TestSink.probe[TimedEvent]
    val consumptionSink = TestSink.probe[TimedEvent]
    val metricSink = TestSink.probe[TimedEvent]
    val validatedSink = TestSink.probe[TimedEvent]

    val (rProbe, cProbe, mProbe, vProbe) =
      RunnableGraph.fromGraph(
        GraphDSL.createGraph(responseSink, consumptionSink, metricSink, validatedSink)(
          (r, c, m, v) => (r, c, m, v)
        ) { implicit b =>
          (rSink, cSink, mSink, vSink) =>
            import GraphDSL.Implicits.*
            val stage = b.add(
              TableStorageStage.componentOfAdmitted(
                stateModel = stateModel,
                systemErrorRate = systemErrorRate,
                rng = rng
              )
            )
            Source(requests.toVector.map(r => r: AdmittedRequestSample)) ~> stage.in
            stage.out0 ~> rSink
            stage.out1 ~> cSink
            stage.out2 ~> mSink
            stage.out3 ~> vSink
            ClosedShape
        }
      ).run()

    rProbe.request(1000)
    cProbe.request(1000)
    mProbe.request(1000)
    vProbe.request(1000)

    def drain[T](probe: TestSubscriber.Probe[TimedEvent], pf: PartialFunction[Any, T]): Vector[T] =
      val buf = Vector.newBuilder[T]
      var done = false
      while !done do
        probe.expectNextOrComplete() match
          case Right(x) if pf.isDefinedAt(x) => buf += pf(x)
          case Right(_) => ()
          case Left(_) => done = true
      buf.result()

    (
      drain(rProbe, { case r: DynamoDBResponse => r }),
      drain(cProbe, { case e: DynamoDbConsumptionEvent => e }),
      drain(mProbe, { case e: StorageMetricEvent => e }),
      drain(vProbe, { case s: AdmittedRequestSample => s })
    )

  "TableStorageStage system error simulation" should {

    "produce no system errors when systemErrorRate is 0.0" in {
      val requests = (1 to 50).map(i => admittedPut(i.toLong, 512L))
      val (responses, consumption, metrics, validated) =
        runAdmitted(requests, systemErrorRate = 0.0, rng = None)

      responses.collect { case _: SystemErrorResponse => 1 } shouldBe empty
      responses.collect { case _: PutItemResponse => 1 } should have size 50
      consumption should not be empty
      validated should have size 50
    }

    "return SystemErrorResponse for every request when RNG always returns 0.0 and rate > 0" in {
      val requests = (1 to 10).map(i => admittedPut(i.toLong, 512L))
      val (responses, _, _, _) =
        runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng))

      responses.collect { case _: SystemErrorResponse => 1 } should have size 10
      responses.collect { case _: PutItemResponse => 1 } shouldBe empty
    }

    "emit SystemErrorResponse with correct operation and target" in {
      val requests = Seq(admittedPut(1L, 512L))
      val (responses, _, _, _) =
        runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng))

      val err = responses.collectFirst { case e: SystemErrorResponse => e }.get
      err.operation shouldBe DynamoDbOperationKind.PutItem
      err.target shouldBe DynamoDbTarget.Table("t")
      err.eventTime shouldBe SimTime.of(1L)
    }

    "emit StorageMetricEvent.SystemError for each system-errored request" in {
      val requests = (1 to 5).map(i => admittedPut(i.toLong, 512L))
      val (_, _, metrics, _) =
        runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng))

      val sysErrors = metrics.collect { case e: StorageMetricEvent.SystemError => e }
      sysErrors should have size 5
      sysErrors.foreach { e =>
        e.operation shouldBe DynamoDbOperationKind.PutItem
        e.target shouldBe DynamoDbTarget.Table("t")
      }
    }

    "produce no consumption events for system-errored requests" in {
      val requests = (1 to 5).map(i => admittedPut(i.toLong, 512L))
      val (_, consumption, _, _) =
        runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng))

      consumption shouldBe empty
    }

    "suppress index-maintenance (validatedSampleFlow) for system-errored requests" in {
      val requests = (1 to 5).map(i => admittedPut(i.toLong, 512L))
      val (_, _, _, validated) =
        runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng))

      validated shouldBe empty
    }

    "not mutate table state for system-errored requests" in {
      val stateModel = SummaryTableState(0L, 0L)
      val requests = (1 to 5).map(i => admittedPut(i.toLong, 512L))
      runAdmitted(requests, systemErrorRate = 0.999, rng = Some(alwaysZeroRng), stateModel = stateModel)

      stateModel.itemCount shouldBe 0L
      stateModel.totalItemBytes shouldBe 0L
    }
  }
