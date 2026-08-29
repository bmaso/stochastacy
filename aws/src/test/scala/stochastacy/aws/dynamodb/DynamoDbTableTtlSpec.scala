package stochastacy.aws.dynamodb

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, Keep, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.{ComponentResult, Timed, TickBoundaryUsecase}
import stochastacy.core.sampler.ConstantSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.*

/** TTL mechanism (Slice 2): items expire exactly `ttlPeriodTicks` after their write, freeing base and
 *  per-index storage at the tick boundary, consuming no capacity; TTL-off tables are byte-identical. */
class DynamoDbTableTtlSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("DynamoDbTableTtlSpec")
  override def afterAll(): Unit = system.terminate()

  private val Table = DynamoDbTarget.Table

  /** A deterministic behavior driven by a fixed script of outcomes, one per request (single-use). */
  private final class ScriptedBehavior(script: Seq[OperationOutcome]) extends TableBehavior:
    private val it = script.iterator
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      it.next()

  private def config(
    behavior:       TableBehavior,
    ttlPeriodTicks: Option[Int],
    initialState:   TableSummaryState = TableSummaryState.initial(10L, 768L)
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(initialState, behavior, ConstantSampler(0.5), ttlPeriodTicks = ttlPeriodTicks)

  private def req(tick: Long, r: DynamoDbRequest): Timed[DynamoDbRequest] = Timed(r, SimTime.of(tick), 0.0, "telemetry")

  private def framed(input: Vector[Timed[DynamoDbRequest]], ticks: Long): Vector[TimedElement[Timed[DynamoDbRequest]]] =
    TickFraming.frame(input.iterator, ticks).toVector

  private def runPlanes(
    cfg:   DynamoDbTable.Config,
    input: Vector[Timed[DynamoDbRequest]],
    ticks: Long
  ): Seq[TimedElement[Timed[DynamoDbConsumption]]] =
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(
        Sink.seq[TimedElement[Timed[DynamoDbResponse]]],
        Sink.seq[TimedElement[Timed[DynamoDbConsumption]]]
      )(Keep.both) { implicit b => (respSink, consSink) =>
        import GraphDSL.Implicits.*
        val td = b.add(DynamoDbTable.componentOf(cfg, RandomSource.KISS.create(1L)))
        b.add(Source(framed(input, ticks))) ~> td.in
        td.out0 ~> respSink.in
        td.out1 ~> consSink.in
        ClosedShape
      }
    )
    val (_, cf) = graph.run()
    Await.result(cf, 5.seconds)

  private def runResult(
    cfg:   DynamoDbTable.Config,
    input: Vector[Timed[DynamoDbRequest]],
    ticks: Long
  ): ComponentResult[TableState] =
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(DynamoDbTable.componentOf(cfg, RandomSource.KISS.create(1L))) { implicit b => td =>
        import GraphDSL.Implicits.*
        b.add(Source(framed(input, ticks))) ~> td.in
        td.out0 ~> b.add(Sink.ignore)
        td.out1 ~> b.add(Sink.ignore)
        ClosedShape
      }
    )
    Await.result(graph.run(), 5.seconds)

  private def consumptions(s: Seq[TimedElement[Timed[DynamoDbConsumption]]]): Seq[Timed[DynamoDbConsumption]] =
    s.collect { case x: Timed[DynamoDbConsumption] @unchecked => x }

  "DynamoDbTable with TTL" should {

    "expire a written item exactly ttlPeriodTicks later, freeing base storage at the tick boundary" in {
      // put an item (insert, 800 B) at tick 1 with a 2-tick TTL → it expires at tick 3.
      val cons = consumptions(runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))), ttlPeriodTicks = Some(2)),
        Vector(req(1L, PutItemRequest(800L))),
        ticks = 5L
      ))

      // the write bills WCU + a positive storage delta at tick 1
      cons.filter(_.eventTime.ticks == 1L).map(_.event) should contain theSameElementsAs
        Seq(WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(800L, Table))

      // the expiry frees the storage at tick 3 — stamped (3, 0), tagged with the tick-boundary usecase
      val expiry = cons.filter(_.eventTime.ticks == 3L)
      expiry.map(_.event) shouldBe Seq(StorageBytesDelta(-800L, Table))
      expiry.head.intraTick shouldBe 0.0
      expiry.head.usecase   shouldBe TickBoundaryUsecase
    }

    "shrink the base item count back to the pre-expiry level" in {
      // initial 10 items / 7680 B; +1 item / +800 B on the put; the expiry restores 10 items / 7680 B.
      val result = runResult(
        config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))), ttlPeriodTicks = Some(2)),
        Vector(req(1L, PutItemRequest(800L))),
        ticks = 5L
      )
      result.finalState.base shouldBe TableSummaryState(10L, 7680L)
    }

    "free per-index storage at expiry, projection-sized, with no capacity consumed" in {
      def cfg = config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))), ttlPeriodTicks = Some(2))
        .withGlobalSecondaryIndex(GlobalSecondaryIndex("g", IndexProjection.KeysOnly)) // entry floored at 128 B
        .withLocalSecondaryIndex(LocalSecondaryIndex("l"))                              // All projection: entry = 800 B

      val cons   = consumptions(runPlanes(cfg, Vector(req(1L, PutItemRequest(800L))), ticks = 5L))
      val expiry = cons.filter(_.eventTime.ticks == 3L)

      // base + each index freed, tagged by target; KeysOnly GSI freed 128 B, All LSI freed 800 B
      expiry.map(_.event) should contain theSameElementsAs Seq(
        StorageBytesDelta(-800L, Table),
        StorageBytesDelta(-128L, DynamoDbTarget.Gsi("g")),
        StorageBytesDelta(-800L, DynamoDbTarget.Lsi("l"))
      )
      // expiry consumes no capacity — every boundary fact is a storage delta
      expiry.map(_.event).collect { case _: WriteCapacityConsumed => true; case _: ReadCapacityConsumed => true } shouldBe empty

      // index summaries shrink back to their seeded (pre-put) level: 10 base items projected
      val result = runResult(cfg, Vector(req(1L, PutItemRequest(800L))), ticks = 5L)
      result.finalState.index("g") shouldBe TableSummaryState(10L, 10L * 128L)
      result.finalState.index("l") shouldBe TableSummaryState(10L, 10L * 768L)
    }

    "free an item deleted before its TTL exactly once — the expiring cohort shrinks, no double-free" in {
      // Three items inserted at tick 1 (300 B each); one explicitly deleted at tick 2. With a 2-tick TTL
      // the tick-1 cohort expires at tick 3 — but only the two survivors, so 600 B is freed, not 900 B.
      val script = Seq(
        OperationOutcome.Put(writtenItemBytes = 300L, previousItemBytes = None),
        OperationOutcome.Put(writtenItemBytes = 300L, previousItemBytes = None),
        OperationOutcome.Put(writtenItemBytes = 300L, previousItemBytes = None),
        OperationOutcome.Delete(deletedItemBytes = Some(300L)) // explicit early delete (e.g. logout)
      )
      def cfg = config(ScriptedBehavior(script), ttlPeriodTicks = Some(2), initialState = TableSummaryState.empty)
      val input = Vector(
        req(1L, PutItemRequest(300L)), req(1L, PutItemRequest(300L)), req(1L, PutItemRequest(300L)),
        req(2L, DeleteItemRequest)
      )

      val cons = consumptions(runPlanes(cfg, input, ticks = 5L))
      // the early delete frees 300 B at tick 2; the expiry frees the remaining 600 B at tick 3 (not 900)
      cons.filter(_.eventTime.ticks == 2L).map(_.event) should contain (StorageBytesDelta(-300L, Table))
      cons.filter(_.eventTime.ticks == 3L).map(_.event) shouldBe Seq(StorageBytesDelta(-600L, Table))

      // net: 3 inserted, 1 deleted early, 2 expired → the table returns to empty (nothing double-freed)
      runResult(cfg, input, ticks = 5L).finalState.base shouldBe TableSummaryState.empty
    }

    "leave a TTL-off table byte-identical — no expiry facts, the item persists" in {
      val cons = consumptions(runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))), ttlPeriodTicks = None),
        Vector(req(1L, PutItemRequest(800L))),
        ticks = 5L
      ))
      // no tick-boundary facts, no negative storage deltas
      cons.map(_.usecase) should not contain TickBoundaryUsecase
      cons.collect { case t if t.event.isInstanceOf[StorageBytesDelta] => t.event.asInstanceOf[StorageBytesDelta].bytesDelta }
        .foreach(_ should be >= 0L)

      // the item is never expired: base retains the put (11 items / 8480 B)
      val result = runResult(
        config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))), ttlPeriodTicks = None),
        Vector(req(1L, PutItemRequest(800L))),
        ticks = 5L
      )
      result.finalState.base shouldBe TableSummaryState(11L, 8480L)
    }
  }
