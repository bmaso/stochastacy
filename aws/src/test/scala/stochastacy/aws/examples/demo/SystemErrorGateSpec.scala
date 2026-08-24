package stochastacy.aws.examples.demo

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{FanOutShape2, Graph, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.*
import stochastacy.core.component.{Interface, Timed}
import stochastacy.core.component.gate.ChaosGate
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedElement}

/**
 * Component-level proof of the inbound system-error gate: wrapping a `DynamoDbTable` with a `ChaosGate`
 * (via `Interface.wrap`) rejects ~`rate` of requests with a `SystemErrorResponse`, and a rejected request
 * never reaches the table — so it consumes no capacity and mutates no state. The table here has **no
 * secondary indexes**, so every admitted put yields exactly one consumption fact; the reject fraction is
 * therefore visible one-to-one in the consumption count.
 */
class SystemErrorGateSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("SystemErrorGateSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // A minimal behavior: every put is an insert of its own bytes; reads are unused in this spec.
  private val insertBehavior: TableBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long) =
      request match
        case PutItemRequest(bytes) => TableMechanics.OperationOutcome.Put(bytes, None)
        case other                 => throw new MatchError(other)

  private def tableConfig = DynamoDbTable.Config(
    initialState           = TableSummaryState.empty,
    behavior               = insertBehavior,
    latency                = LogNormalSampler.constant(math.log(0.05), 0.5),
    globalSecondaryIndexes = Vector.empty,
    localSecondaryIndexes  = Vector.empty
  )

  private type TableGraph = Graph[FanOutShape2[
    TimedElement[Timed[DynamoDbRequest]],
    TimedElement[Timed[DynamoDbResponse]],
    TimedElement[Timed[DynamoDbConsumption]]
  ], Any]

  private def rawTable(seed: Long): TableGraph =
    DynamoDbTable.componentOf(tableConfig, RandomSource.KISS.create(seed))

  private def wrappedTable(rate: Double, tableSeed: Long, gateSeed: Long): TableGraph =
    Interface.wrap(
      rawTable(tableSeed),
      ChaosGate.constant[DynamoDbRequest, DynamoDbResponse](rate, SystemErrorResponse),
      RandomSource.KISS.create(gateSeed))

  private def putStream(ticks: Int, perTick: Int): Vector[TimedElement[Timed[DynamoDbRequest]]] =
    val arrivals = (1 to ticks).flatMap { tick =>
      (0 until perTick).map { j =>
        Timed(PutItemRequest(300L): DynamoDbRequest, SimTime.of(tick.toLong), (j.toDouble + 0.5) / perTick, "sys-err-test")
      }
    }.toVector
    // Frame with a two-tick cushion so latency-delayed responses land inside the horizon (nothing spills
    // into post-horizon residue), keeping the request/response count exact.
    TickFraming.frame(arrivals.iterator, (ticks + 2).toLong).toVector

  /** Run a framed request stream through a table component, returning its response and consumption events. */
  private def runThrough(component: TableGraph, framed: Vector[TimedElement[Timed[DynamoDbRequest]]])
      : (Vector[DynamoDbResponse], Vector[DynamoDbConsumption]) =
    val g = RunnableGraph.fromGraph(
      GraphDSL.createGraph(
        Sink.seq[TimedElement[Timed[DynamoDbResponse]]],
        Sink.seq[TimedElement[Timed[DynamoDbConsumption]]]
      )((r, c) => (r, c)) { implicit b => (respSink, consSink) =>
        import GraphDSL.Implicits.*
        val table = b.add(component)
        b.add(Source(framed)) ~> table.in
        table.out0 ~> respSink.in
        table.out1 ~> consSink.in
        org.apache.pekko.stream.ClosedShape
      }
    )
    val (respF, consF) = g.run()
    val responses   = Await.result(respF, 60.seconds).collect { case t: Timed[DynamoDbResponse] @unchecked => t.event }
    val consumption = Await.result(consF, 60.seconds).collect { case t: Timed[DynamoDbConsumption] @unchecked => t.event }
    (responses.toVector, consumption.toVector)

  private val TotalRequests = 50 * 20 // 1000

  "The inbound system-error gate" should {

    "reject ~rate of requests with a SystemErrorResponse, one response per request" in {
      val (responses, _) = runThrough(wrappedTable(rate = 0.2, tableSeed = 1L, gateSeed = 99L), putStream(50, 20))
      responses.size shouldBe TotalRequests // 1:1 request/response integrity
      val rejected = responses.count(_ == SystemErrorResponse)
      (rejected.toDouble / TotalRequests) shouldBe (0.2 +- 0.03)
    }

    "let rejected requests consume no capacity — consumption scales with the admitted fraction only" in {
      val framed        = putStream(50, 20)
      val (_, rawCons)  = runThrough(rawTable(seed = 1L), framed)
      val (_, wrapCons) = runThrough(wrappedTable(rate = 0.2, tableSeed = 1L, gateSeed = 99L), framed)
      rawCons.size should be > 0
      // ~20% of requests are rejected before reaching the table, so ~80% of the consumption facts remain
      // (the fact count per admitted put is constant, so the ratio is the admitted fraction).
      (wrapCons.size.toDouble / rawCons.size.toDouble) shouldBe (0.8 +- 0.05)
      wrapCons.size should be < rawCons.size
    }

    "be deterministic under fixed table and gate seeds" in {
      val framed = putStream(50, 20)
      runThrough(wrappedTable(0.2, 1L, 99L), framed) shouldBe runThrough(wrappedTable(0.2, 1L, 99L), framed)
    }
  }
