package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.{FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, Scheduled}
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.sim.SimInstant

/** The contextual table adapter: every response carries the context its request arrived with, and everything else —
 *  state, consumption, taps, latency, tick and feedback behavior — is exactly the wrapped table's. */
class ContextualTableSpec extends AnyWordSpec with should.Matchers:

  // Every PutItem inserts a fresh item of the requested size: 1 KB = 1 WCU.
  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val config = DynamoDbTable.Config(
    initialState = TableSummaryState.empty, behavior = putBehavior,
    latency      = LogNormalSampler.constant(math.log(0.01), 0.0),
    billingMode  = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)
  )

  "A contextual table" should {

    "behave exactly like the plain table, with each response wrapped in its request's context" in {
      val plain      = new DynamoDbTable.DynamoDbTableSampler(config)
      val contextual = DynamoDbTable.withContext[String](config)
      val plainRng   = RandomSource.KISS.create(5L)
      val ctxRng     = RandomSource.KISS.create(5L)
      var ps         = plain.initialState
      var cs         = contextual.initialState
      cs shouldBe ps

      // Five puts in tick 0 against a 3-WCU ceiling (two throttle), a boundary, then one more put.
      val requests = (1 to 5).map(i => PutItemRequest(1000L + i)) :+ PutItemRequest(1006L)
      requests.zipWithIndex.foreach { (req, i) =>
        if i == 5 then
          val pt = plain.onTick(1L, ps)
          val ct = contextual.onTick(1L, cs)
          ct shouldBe pt
          ps = pt.newState; cs = ct.newState
        val at = SimInstant(if i < 5 then 0L else 1L, 0.1 * i)
        val pe = plain.sample(req, at, ps, plainRng)
        val ce = contextual.sample(Contextual(s"request-$i", req), at, cs, ctxRng)
        withClue(s"request $i: ") {
          ce.output shouldBe Scheduled(Contextual(s"request-$i", pe.output.event), pe.output.delay)
          ce.newState shouldBe pe.newState
          ce.consumption shouldBe pe.consumption
          ce.taps shouldBe pe.taps
        }
        ps = pe.newState; cs = ce.newState
      }
      cs.base.itemCount shouldBe 4L // three admitted in tick 0, one after the boundary
    }

    "return the context on a throttle, alongside the throttled request" in {
      val contextual = DynamoDbTable.withContext[Int](config.copy(billingMode = BillingMode.Provisioned(100, 1)))
      val big        = PutItemRequest(10240L) // 10 WCU ≫ the 1-WCU ceiling
      val e          = contextual.sample(Contextual(42, big), SimInstant(0L, 0.0), contextual.initialState, RandomSource.KISS.create(1L))
      e.output.event shouldBe Contextual(42, ThrottledResponse(big))
      e.consumption.map(_.event) shouldBe List(RequestThrottled(DynamoDbTarget.Table))
    }

    "apply a replicated write through to the table, answering nothing" in {
      val plain      = new DynamoDbTable.DynamoDbTableSampler(config)
      val contextual = DynamoDbTable.withContext[Int](config)
      val write      = ReplicationWrite(OperationOutcome.Put(writtenItemBytes = 2048L, previousItemBytes = None))
      val pe         = plain.onFeedback(write, SimInstant(0L, 0.5), plain.initialState, RandomSource.KISS.create(1L))
      val ce         = contextual.onFeedback(write, SimInstant(0L, 0.5), contextual.initialState, RandomSource.KISS.create(1L))
      ce shouldBe FeedbackEmission(pe.newState, None, pe.consumption, pe.taps)
      ce.newState.base.itemCount shouldBe 1L
    }

    "fail loudly if the wrapped table answers a fed-back item, which has no request context" in {
      val answering = new LoopbackComponentSampler[TableState, DynamoDbRequest, ReplicationWrite, DynamoDbResponse, DynamoDbConsumption, ReplicationWrite]:
        private val table = new DynamoDbTable.DynamoDbTableSampler(config)
        def initialState: TableState = table.initialState
        def sample(in: DynamoDbRequest, at: SimInstant, state: TableState, rng: UniformRandomProvider)
            : LoopbackEmission[TableState, DynamoDbResponse, DynamoDbConsumption, ReplicationWrite] = table.sample(in, at, state, rng)
        def onFeedback(fb: ReplicationWrite, at: SimInstant, state: TableState, rng: UniformRandomProvider)
            : FeedbackEmission[TableState, DynamoDbResponse, DynamoDbConsumption, ReplicationWrite] =
          FeedbackEmission(state, output = Some(Scheduled(DeleteItemResponse(None), 0.0)))
      val contextual = new ContextualTableSampler[Int](answering)
      val write      = ReplicationWrite(OperationOutcome.Put(writtenItemBytes = 2048L, previousItemBytes = None))
      val ex = intercept[IllegalStateException] {
        contextual.onFeedback(write, SimInstant(0L, 0.5), contextual.initialState, RandomSource.KISS.create(1L))
      }
      ex.getMessage should include("no request context")
    }
  }
