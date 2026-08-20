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
import stochastacy.core.component.{ComponentResult, Timed}
import stochastacy.core.sampler.ConstantSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.*
import stochastacy.sim.TimedControlEvent.EndOfTime

class DynamoDbTableSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("DynamoDbTableSpec")
  override def afterAll(): Unit = system.terminate()

  private val strong = ReadConsistency.StronglyConsistent
  private val Table  = DynamoDbTarget.Table

  /** A deterministic behavior driven by a fixed script of outcomes, one per request (in order). The
   *  real stochastic behavior arrives in Slice 3; here we pin outcomes so the mechanics are exactly
   *  checked. Single-use (its iterator is consumed once per materialization). */
  private final class ScriptedBehavior(script: Seq[OperationOutcome]) extends TableBehavior:
    private val it = script.iterator
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider): OperationOutcome =
      it.next()

  private def config(
    behavior:     TableBehavior,
    initialState: TableSummaryState = TableSummaryState.initial(10L, 768L),
    latency:      Double            = 0.5
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(initialState, behavior, ConstantSampler(latency))

  private def req(tick: Long, r: DynamoDbRequest): Timed[DynamoDbRequest] = Timed(r, SimTime.of(tick), 0.0, "orders")

  private def framed(input: Vector[Timed[DynamoDbRequest]], ticks: Long): Vector[TimedElement[Timed[DynamoDbRequest]]] =
    TickFraming.frame(input.iterator, ticks).toVector

  /** Materialize the table and drain both output planes (one materialization; the component Mat is
   *  discarded). */
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

  /** Materialize the table and return its `ComponentResult` Mat, ignoring both planes (one
   *  materialization). */
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

  "DynamoDbTable, driven through the materialized stage," should {

    "return a get response after the latency delay, with capacity consumed at execution time" in {
      val (resp, cons) = runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Get(Some(768L), strong)))),
        Vector(req(1L, GetItemRequest)),
        ticks = 3L
      )
      val r = responses(resp)
      r.map(_.event) shouldBe Seq(GetItemResponse(itemFound = true, itemBytes = Some(768L)))
      r.head.eventTime.ticks shouldBe 1L      // 1 + floor(0 + 0.5)
      r.head.intraTick       shouldBe 0.5     // latency applied to the response

      val c = consumptions(cons)
      c.map(_.event)         shouldBe Seq(ReadCapacityConsumed(BigDecimal(1), strong, Table))
      c.head.eventTime.ticks shouldBe 1L      // consumption at execution time...
      c.head.intraTick       shouldBe 0.0     // ...delay 0, no latency
    }

    "emit WCU and a storage delta for a put, and delay only the response" in {
      val (resp, cons) = runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None)))),
        Vector(req(1L, PutItemRequest(800L))),
        ticks = 3L
      )
      responses(resp).map(_.event) shouldBe Seq(PutItemResponse(storedItemBytes = 800L, createdNewItem = true, previousItemBytes = None))
      consumptions(cons).map(_.event) should contain theSameElementsAs
        Seq(WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(800L, Table))
    }

    "thread table state across a sequence of operations (final state in the materialized value)" in {
      val script = Seq(
        OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None),          // 10 -> 11 items, +800 bytes
        OperationOutcome.Update(writtenItemBytes = 900L, previousItemBytes = Some(768L)), // bytes +132
        OperationOutcome.Delete(deletedItemBytes = Some(768L))                            // 11 -> 10 items, -768 bytes
      )
      val result = runResult(
        config(ScriptedBehavior(script)),
        Vector(req(1L, PutItemRequest(800L)), req(2L, UpdateItemRequest(900L)), req(3L, DeleteItemRequest)),
        ticks = 5L
      )
      // 7680 +800 +132 -768 = 7844 bytes; 10 +1 -1 = 10 items
      result.finalState.base shouldBe TableSummaryState(10L, 7844L)
      result.residue.total   shouldBe 0L
    }

    "maintain a GSI and an LSI on a base write (per-index facts, tagged and delayed; index state evolves)" in {
      // a fresh config per materialization — the scripted behavior's iterator is single-use
      def cfg = config(ScriptedBehavior(Seq(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None))))
        .withGlobalSecondaryIndex(GlobalSecondaryIndex("customerId-status")) // All projection; async delay 0 (default)
        .withLocalSecondaryIndex(LocalSecondaryIndex("createdAt-priority"))  // All projection; synchronous

      val (_, cons) = runPlanes(cfg, Vector(req(1L, PutItemRequest(800L))), ticks = 3L)
      // base put + one insert per index (All projection: entry = 800 bytes -> 1 WCU, +800 storage), tagged by target
      consumptions(cons).map(_.event) should contain theSameElementsAs Seq(
        WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(800L, Table),
        WriteCapacityConsumed(BigDecimal(1), DynamoDbTarget.Gsi("customerId-status")), StorageBytesDelta(800L, DynamoDbTarget.Gsi("customerId-status")),
        WriteCapacityConsumed(BigDecimal(1), DynamoDbTarget.Lsi("createdAt-priority")), StorageBytesDelta(800L, DynamoDbTarget.Lsi("createdAt-priority"))
      )

      // final state: base 11 items / 8480 bytes; each index seeded from 10 base items (All -> 7680) + this insert
      val result = runResult(cfg, Vector(req(1L, PutItemRequest(800L))), ticks = 3L)
      result.finalState.base                       shouldBe TableSummaryState(11L, 8480L)
      result.finalState.index("customerId-status") shouldBe TableSummaryState(11L, 8480L)
      result.finalState.index("createdAt-priority") shouldBe TableSummaryState(11L, 8480L)
    }

    "preserve control events, ending both planes with EndOfTime" in {
      val (resp, cons) = runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Get(None, strong)))),
        Vector(req(1L, GetItemRequest)),
        ticks = 3L
      )
      resp.last shouldBe EndOfTime
      cons.last shouldBe EndOfTime
    }

    "serve a query: response carries the read shape, RCU charged from evaluated bytes at execution time" in {
      val shape = TableMechanics.ReadShape(evaluatedItemCount = 20L, evaluatedBytes = 20L * 768L, returnedItemCount = 12L, returnedBytes = 12L * 768L)
      val (resp, cons) = runPlanes(
        config(ScriptedBehavior(Seq(OperationOutcome.Query(Table, strong, shape)))),
        Vector(req(1L, QueryRequest(Table, strong))),
        ticks = 3L
      )
      responses(resp).map(_.event) shouldBe Seq(QueryResponse(20L, 15360L, 12L, 9216L))

      val c = consumptions(cons)
      c.map(_.event)         shouldBe Seq(ReadCapacityConsumed(BigDecimal(4), strong, Table)) // ceil(15360/4096)=4
      c.head.eventTime.ticks shouldBe 1L
      c.head.intraTick       shouldBe 0.0
    }

    "be deterministic under a fixed seed" in {
      def once(): Seq[DynamoDbResponse] =
        val (resp, _) = runPlanes(
          config(ScriptedBehavior(Seq(OperationOutcome.Get(Some(768L), strong), OperationOutcome.Delete(None)))),
          Vector(req(1L, GetItemRequest), req(2L, DeleteItemRequest)),
          ticks = 4L
        )
        responses(resp).map(_.event)
      once() shouldBe once()
    }
  }
