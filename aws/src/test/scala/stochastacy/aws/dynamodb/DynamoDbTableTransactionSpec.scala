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

import stochastacy.aws.dynamodb.TableMechanics.{OperationOutcome, TransactWriteItem}
import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.core.sampler.ConstantSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.*

/** Transactions mechanism (Slice 4): 2× base/LSI capacity, 1× GSI maintenance, atomic all-or-nothing under
 *  throttling, and TTL recording across every sub-write. */
class DynamoDbTableTransactionSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("DynamoDbTableTransactionSpec")
  override def afterAll(): Unit = system.terminate()

  private val Table  = DynamoDbTarget.Table
  private val strong = ReadConsistency.StronglyConsistent

  private final class ScriptedBehavior(script: Seq[OperationOutcome]) extends TableBehavior:
    private val it = script.iterator
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      it.next()

  private def config(
    behavior:       TableBehavior,
    ttlPeriodTicks: Option[Int]           = None,
    billingMode:    BillingMode           = BillingMode.OnDemand,
    initialState:   TableSummaryState     = TableSummaryState.empty
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(initialState, behavior, ConstantSampler(0.5), billingMode = billingMode, ttlPeriodTicks = ttlPeriodTicks)

  private def req(tick: Long, r: DynamoDbRequest): Timed[DynamoDbRequest] = Timed(r, SimTime.of(tick), 0.0, "txn")

  private def framed(input: Vector[Timed[DynamoDbRequest]], ticks: Long): Vector[TimedElement[Timed[DynamoDbRequest]]] =
    TickFraming.frame(input.iterator, ticks).toVector

  private def runPlanes(
    cfg:   DynamoDbTable.Config,
    input: Vector[Timed[DynamoDbRequest]],
    ticks: Long
  ): (Seq[TimedElement[Timed[DynamoDbResponse]]], Seq[TimedElement[Timed[DynamoDbConsumption]]]) =
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
    val (rf, cf) = graph.run()
    (Await.result(rf, 5.seconds), Await.result(cf, 5.seconds))

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

  private def responses(s: Seq[TimedElement[Timed[DynamoDbResponse]]]): Seq[Timed[DynamoDbResponse]] =
    s.collect { case x: Timed[DynamoDbResponse] @unchecked => x }

  private def consumptions(s: Seq[TimedElement[Timed[DynamoDbConsumption]]]): Seq[Timed[DynamoDbConsumption]] =
    s.collect { case x: Timed[DynamoDbConsumption] @unchecked => x }

  "DynamoDbTable transactions" should {

    "bill base and LSI writes 2x, GSI maintenance 1x, with per-target storage deltas" in {
      // a two-item transactional write (800 B each, inserts) over one All-projection GSI and LSI
      val items = Vector(
        TransactWriteItem(writtenItemBytes = 800L, previousItemBytes = None),
        TransactWriteItem(writtenItemBytes = 800L, previousItemBytes = None)
      )
      val cfg = config(ScriptedBehavior(Seq(OperationOutcome.TransactWrite(items))))
        .withGlobalSecondaryIndex(GlobalSecondaryIndex("g")) // All projection, async delay 0
        .withLocalSecondaryIndex(LocalSecondaryIndex("l"))   // All projection, synchronous

      val (resp, cons) = runPlanes(cfg, Vector(req(1L, TransactWriteItemsRequest(Vector(800L, 800L)))), ticks = 3L)
      responses(resp).map(_.event) shouldBe Seq(TransactWriteItemsResponse(2))

      val g = DynamoDbTarget.Gsi("g")
      val l = DynamoDbTarget.Lsi("l")
      consumptions(cons).map(_.event) should contain theSameElementsAs Seq(
        // base: 2x WCU per item + storage
        WriteCapacityConsumed(BigDecimal(2), Table), StorageBytesDelta(800L, Table),
        WriteCapacityConsumed(BigDecimal(2), Table), StorageBytesDelta(800L, Table),
        // GSI: 1x WCU per item (async post-commit) + storage
        WriteCapacityConsumed(BigDecimal(1), g), StorageBytesDelta(800L, g),
        WriteCapacityConsumed(BigDecimal(1), g), StorageBytesDelta(800L, g),
        // LSI: 2x WCU per item (synchronous, co-located) + storage
        WriteCapacityConsumed(BigDecimal(2), l), StorageBytesDelta(800L, l),
        WriteCapacityConsumed(BigDecimal(2), l), StorageBytesDelta(800L, l)
      )
    }

    "commit atomically — a throttled transaction applies nothing and bills no capacity" in {
      // base demand = 2 items x ceil(2000/1024)=2 x2 (transactional) = 8 WCU, over a base ceiling of 5
      val items = Vector(
        TransactWriteItem(2000L, None),
        TransactWriteItem(2000L, None)
      )
      def cfg = config(ScriptedBehavior(Seq(OperationOutcome.TransactWrite(items))), billingMode = BillingMode.Provisioned(1000L, 5L))
      val input = Vector(req(1L, TransactWriteItemsRequest(Vector(2000L, 2000L))))

      val (resp, cons) = runPlanes(cfg, input, ticks = 3L)
      responses(resp).map(_.event)    shouldBe Seq(ThrottledResponse)
      consumptions(cons).map(_.event) shouldBe Seq(RequestThrottled(Table)) // no WCU / no storage
      // all-or-nothing: no sub-write applied
      runResult(cfg, input, ticks = 3L).finalState.base shouldBe TableSummaryState.empty
    }

    "record every sub-write in the TTL ring buffer so the whole set expires together" in {
      // three inserts at tick 1 (300 B each) with a 2-tick TTL -> all expire at tick 3 (base -900)
      val items = Vector(TransactWriteItem(300L, None), TransactWriteItem(300L, None), TransactWriteItem(300L, None))
      def cfg   = config(ScriptedBehavior(Seq(OperationOutcome.TransactWrite(items))), ttlPeriodTicks = Some(2))
      val input = Vector(req(1L, TransactWriteItemsRequest(Vector(300L, 300L, 300L))))

      val (_, cons) = runPlanes(cfg, input, ticks = 5L)
      consumptions(cons).filter(_.eventTime.ticks == 3L).map(_.event) shouldBe Seq(StorageBytesDelta(-900L, Table))
      runResult(cfg, input, ticks = 5L).finalState.base shouldBe TableSummaryState.empty
    }

    "bill a transactional read 2x strong RCU per item, changing no state" in {
      val cfg = config(ScriptedBehavior(Seq(OperationOutcome.TransactGet(Vector(Some(4096L), Some(4096L))))), initialState = TableSummaryState.initial(5L, 4096L))
      val (resp, cons) = runPlanes(cfg, Vector(req(1L, TransactGetItemsRequest(2))), ticks = 3L)
      responses(resp).map(_.event)    shouldBe Seq(TransactGetItemsResponse(Vector(Some(4096L), Some(4096L))))
      consumptions(cons).map(_.event) shouldBe Seq(
        ReadCapacityConsumed(BigDecimal(2), strong, Table),
        ReadCapacityConsumed(BigDecimal(2), strong, Table)
      )
    }

    "be deterministic under a fixed seed" in {
      def once(): Seq[DynamoDbResponse] =
        val items = Vector(TransactWriteItem(800L, None))
        val (resp, _) = runPlanes(
          config(ScriptedBehavior(Seq(OperationOutcome.TransactWrite(items)))),
          Vector(req(1L, TransactWriteItemsRequest(Vector(800L)))),
          ticks = 3L
        )
        responses(resp).map(_.event)
      once() shouldBe once()
    }
  }
