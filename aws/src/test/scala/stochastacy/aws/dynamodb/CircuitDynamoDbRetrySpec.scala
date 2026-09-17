package stochastacy.aws.dynamodb

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.{FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, Scheduled, Timed}
import stochastacy.core.component.circuit.{Circuit, CircuitResult}
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimInstant, SimTime, TimedElement, ticks}

/**
 * A client retrying against a **provisioned DynamoDB table inside a circuit** — the loop backlog C6 made possible. The
 * table answers a throttle with `ThrottledResponse(request)`, and `DynamoDbTable.withContext` returns the client's own
 * `Attempt(requestNumber, attempt)` with every answer, so the client re-sends exactly the throttled request, caps its
 * attempts per request, and knows which logical request each success belongs to — all without per-request state.
 *
 * Ten 1-WCU puts arrive in tick 1 against a 3-WCU-per-tick ceiling (no burst). A throttled request is retried one tick
 * later, when the budget has reset, up to three attempts. Worked by hand:
 *
 *   tick 1: requests 1–3 admitted, 4–10 throttled → retried (attempt 2)
 *   tick 2: requests 4–6 admitted, 7–10 throttled → retried (attempt 3)
 *   tick 3: requests 7–9 admitted, 10 throttled on its third attempt → given up
 */
class CircuitDynamoDbRetrySpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitDynamoDbRetrySpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private final case class Attempt(requestNumber: Int, attempt: Int)

  private final case class ClientState(
    served:  Vector[Int],                         // request numbers, in completion order
    retried: Vector[Attempt],                     // the attempt each retry is sent as
    resent:  Vector[(Int, DynamoDbRequest)],      // what was re-sent, per request number
    gaveUp:  Vector[Int]
  )

  private val MaxAttempts  = 3
  private val RetryBackoff = 1.0 // one tick: the per-tick throttle budget has reset by then

  // Distinct payloads, each at most 1 KB so each costs exactly 1 WCU (write capacity rounds up per KB — 1025 bytes is 2 WCU).
  private def requestFor(n: Int): DynamoDbRequest = PutItemRequest(1000L + n)

  private final class RetryingClient extends LoopbackComponentSampler[
      ClientState, Int, Contextual[Attempt, DynamoDbResponse], Contextual[Attempt, DynamoDbRequest], Nothing, Nothing]:
    def initialState: ClientState = ClientState(Vector.empty, Vector.empty, Vector.empty, Vector.empty)

    def sample(n: Int, at: SimInstant, s: ClientState, rng: UniformRandomProvider)
        : LoopbackEmission[ClientState, Contextual[Attempt, DynamoDbRequest], Nothing, Nothing] =
      LoopbackEmission(s, Scheduled(Contextual(Attempt(n, 1), requestFor(n)), 0.0), Nil)

    def onFeedback(fb: Contextual[Attempt, DynamoDbResponse], at: SimInstant, s: ClientState, rng: UniformRandomProvider)
        : FeedbackEmission[ClientState, Contextual[Attempt, DynamoDbRequest], Nothing, Nothing] =
      fb match
        case Contextual(Attempt(n, a), ThrottledResponse(request)) if a < MaxAttempts =>
          val next = Attempt(n, a + 1)
          FeedbackEmission(s.copy(retried = s.retried :+ next, resent = s.resent :+ (n -> request)),
                           output = Some(Scheduled(Contextual(next, request), RetryBackoff)))
        case Contextual(Attempt(n, _), ThrottledResponse(_)) => FeedbackEmission(s.copy(gaveUp = s.gaveUp :+ n))
        case Contextual(Attempt(n, _), _)                    => FeedbackEmission(s.copy(served = s.served :+ n))

  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val tableConfig = DynamoDbTable.Config(
    initialState = TableSummaryState.empty, behavior = putBehavior,
    latency      = LogNormalSampler.constant(math.log(0.01), 0.0),
    billingMode  = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)
  )

  private type Cons = TimedElement[Timed[DynamoDbConsumption]]

  "A retrying client against a provisioned table in a circuit" should {

    "retry exactly the throttled requests, cap attempts per request, and consume capacity only when admitted" in {
      val (circuit, (client, table)) = Circuit.buildWith[Int, Nothing, DynamoDbConsumption] { b =>
        val client = b.node("client", new RetryingClient)
        val table  = b.node("table", DynamoDbTable.withContext[Attempt](tableConfig))
        b.input(client.in)
        b.connect(client.out, table.in)
        b.connect(table.out, client.fb)
        b.consumption(table.consumption)
        (client, table)
      }

      val arrivals = (1 to 10).iterator.map(n => Timed(n, SimTime.of(1L), 0.05 * n, "retry"))
      val input    = TickFraming.frame(arrivals, 5L).toVector

      val (resultF, consF) = RunnableGraph.fromGraph(
        GraphDSL.createGraph(Circuit.componentOf(circuit, RandomSource.KISS.create(1L)), Sink.seq[Cons])((_, _)) {
          implicit b => (c, consSink) =>
            import GraphDSL.Implicits.*
            b.add(Source(input)) ~> c.in
            c.out0 ~> b.add(Sink.ignore)
            c.out1 ~> consSink.in
            ClosedShape
        }
      ).run()
      val result: CircuitResult = Await.result(resultF, 30.seconds)
      val facts = Await.result(consF, 30.seconds).collect { case t: Timed[DynamoDbConsumption] @unchecked => t }

      val c = result.stateOf(client)
      c.served.sorted shouldBe (1 to 9).toVector
      c.gaveUp shouldBe Vector(10)
      c.retried.filter(_.attempt == 2).map(_.requestNumber) shouldBe (4 to 10).toVector
      c.retried.filter(_.attempt == 3).map(_.requestNumber) shouldBe (7 to 10).toVector
      // Every re-send is the very request the table throttled — which is the request originally sent under that number.
      c.resent.foreach { (n, request) => withClue(s"request $n: ")(request shouldBe requestFor(n)) }

      def perTick(pf: PartialFunction[DynamoDbConsumption, Unit]): Map[Long, Int] =
        facts.filter(t => pf.isDefinedAt(t.event)).groupBy(_.eventTime.ticks).view.mapValues(_.size).toMap

      perTick { case RequestThrottled(DynamoDbTarget.Table) => () } shouldBe Map(1L -> 7, 2L -> 4, 3L -> 1)
      perTick { case _: WriteCapacityConsumed => () } shouldBe Map(1L -> 3, 2L -> 3, 3L -> 3)
      result.stateOf(table).base.itemCount shouldBe 9L
      result.residue.total shouldBe 0L
    }
  }
