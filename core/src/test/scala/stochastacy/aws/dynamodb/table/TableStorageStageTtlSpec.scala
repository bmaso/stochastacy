package stochastacy.aws.dynamodb.table

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.TableMetricEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent}

import scala.concurrent.Await
import scala.concurrent.duration.*

class TableStorageStageTtlSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem  = ActorSystem("table-storage-ttl-test")
  given Materializer = Materializer.matFromSystem
  import scala.concurrent.ExecutionContext.Implicits.global

  private val ttlPeriodTicks = 3

  private object FixedPutBehavior extends UseCaseSampler[TableState]:
    override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
      new PutItemSample:
        val writtenItemBytes: Long           = request.itemBytes
        val previousItemBytes: Option[Long]  = None

  private def buildConfig(
    ttlSampler: Option[TtlSampler] = None
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName          = "test-table",
      stateModel         = SummaryTableState(0L, 0L),
      useCaseBehaviors   = Map("uc" -> FixedPutBehavior),
      ttlSampler         = ttlSampler
    )

  private def runAndCollectMetrics(
    events: List[TimedElement[DynamoDBRequest]],
    config: DynamoDbTable.Config
  ): (List[DynamoDbConsumptionEvent], List[StorageMetricEvent]) =
    val consSink    = Sink.seq[TimedElement[DynamoDbConsumptionEvent]]
    val metricSink  = Sink.seq[TimedElement[TableMetricEvent]]

    val (consF, metF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consSink, metricSink)((c, m) => (c, m)) { implicit b =>
        (cSink, mSink) =>
          import GraphDSL.Implicits._
          val table = b.add(DynamoDbTable.componentOf(config))
          Source(events) ~> table.in
          table.out0 ~> b.add(Sink.ignore)
          table.out1 ~> cSink
          table.out2 ~> mSink
          ClosedShape
      }
    ).run()

    val cons    = Await.result(consF, 10.seconds).collect { case e: DynamoDbConsumptionEvent => e }.toList
    val metrics = Await.result(metF, 10.seconds).collect { case e: StorageMetricEvent => e }.toList
    (cons, metrics)

  "TableStorageStage with TTL sampler" should {

    "emit no TtlItemsExpired events when no TTL sampler is configured" in {
      val events: List[TimedElement[DynamoDBRequest]] = List(
        TimedControlEvent.Tick(SimTime.of(1L)),
        PutItemRequest(SimTime.of(1L), "uc", itemBytes = 100L),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.Tick(SimTime.of(3L)),
        TimedControlEvent.Tick(SimTime.of(4L))
      )
      val (_, metrics) = runAndCollectMetrics(events, buildConfig(ttlSampler = None))
      metrics.collect { case e: StorageMetricEvent.TtlItemsExpired => e } shouldBe Nil
    }

    "emit TtlItemsExpired after the TTL period elapses" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = ttlPeriodTicks)
      val events: List[TimedElement[DynamoDBRequest]] = List(
        TimedControlEvent.Tick(SimTime.of(1L)),
        PutItemRequest(SimTime.of(1L), "uc", itemBytes = 200L),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.Tick(SimTime.of(3L)),
        // tick 4 = 1 + ttlPeriodTicks → expires the write from tick 1
        TimedControlEvent.Tick(SimTime.of(4L)),
        TimedControlEvent.Tick(SimTime.of(5L))
      )
      val (_, metrics) = runAndCollectMetrics(events, buildConfig(ttlSampler = Some(sampler)))
      val ttlEvents = metrics.collect { case e: StorageMetricEvent.TtlItemsExpired => e }
      ttlEvents should have size 1
      ttlEvents.head.count shouldBe 1L
      ttlEvents.head.freedBytes shouldBe 200L
    }

    "emit EstimatedItemCount metric at each tick when TTL is configured" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = ttlPeriodTicks)
      val events: List[TimedElement[DynamoDBRequest]] = List(
        TimedControlEvent.Tick(SimTime.of(1L)),
        PutItemRequest(SimTime.of(1L), "uc", itemBytes = 100L),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.Tick(SimTime.of(3L)),
        TimedControlEvent.Tick(SimTime.of(4L))
      )
      val (_, metrics) = runAndCollectMetrics(events, buildConfig(ttlSampler = Some(sampler)))
      val countEvents = metrics.collect { case e: StorageMetricEvent.EstimatedItemCount => e }
      countEvents should have size 4
    }

    "emit StorageBytesDelta consumption events for TTL expiry" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = ttlPeriodTicks)
      val events: List[TimedElement[DynamoDBRequest]] = List(
        TimedControlEvent.Tick(SimTime.of(1L)),
        PutItemRequest(SimTime.of(1L), "uc", itemBytes = 300L),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.Tick(SimTime.of(3L)),
        TimedControlEvent.Tick(SimTime.of(4L))
      )
      val (cons, _) = runAndCollectMetrics(events, buildConfig(ttlSampler = Some(sampler)))
      val deltas = cons.collect { case e: DynamoDbConsumptionEvent.StorageBytesDelta => e }
      // Put creates +300 delta, TTL expiry creates -300 delta
      val posDeltas = deltas.filter(_.bytesDelta > 0)
      val negDeltas = deltas.filter(_.bytesDelta < 0)
      posDeltas.map(_.bytesDelta).sum shouldBe 300L
      negDeltas.map(_.bytesDelta).sum shouldBe -300L
    }

    "update stateModel item count when TTL expires items" in {
      val stateModel = SummaryTableState(0L, 0L)
      val sampler    = SimpleTtlSampler(ttlPeriodTicks = ttlPeriodTicks)
      val config = DynamoDbTable.Config(
        tableName        = "test-table",
        stateModel       = stateModel,
        useCaseBehaviors = Map("uc" -> FixedPutBehavior),
        ttlSampler       = Some(sampler)
      )
      val events: List[TimedElement[DynamoDBRequest]] = List(
        TimedControlEvent.Tick(SimTime.of(1L)),
        PutItemRequest(SimTime.of(1L), "uc", itemBytes = 128L),
        TimedControlEvent.Tick(SimTime.of(2L)),
        TimedControlEvent.Tick(SimTime.of(3L)),
        TimedControlEvent.Tick(SimTime.of(4L))
      )
      runAndCollectMetrics(events, config)
      stateModel.itemCount shouldBe 0L
      stateModel.totalItemBytes shouldBe 0L
    }

    "not double-count items deleted before TTL fires" in {
      val sampler = SimpleTtlSampler(ttlPeriodTicks = ttlPeriodTicks)

      // Record two writes then one delete manually
      sampler.recordWrite(bytes = 100L, tick = 1L)
      sampler.recordWrite(bytes = 100L, tick = 1L)
      sampler.recordDelete(tick = 2L)

      // TTL fires at tick 4 — should see only 1 item expired, not 2
      val result = sampler.expiryAt(TtlSamplerContext(tick = 4L))
      result.expiredItemCount shouldBe 1L
    }

  }
