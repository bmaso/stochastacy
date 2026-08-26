package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Emission
import stochastacy.core.sampler.LogNormalSampler

class ThrottlingSpec extends AnyWordSpec with should.Matchers:

  // A minimal behavior: every PutItem inserts a fresh 1 KB item (1 WCU on the base, plus per-index maintenance).
  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)

  private def sampler(billing: BillingMode, gsis: Vector[GlobalSecondaryIndex] = Vector.empty): DynamoDbTable.DynamoDbTableSampler =
    new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior, latency = latency,
      globalSecondaryIndexes = gsis, billingMode = billing
    ))

  private val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

  "A provisioned table's per-tick throttle" should {

    "admit up to the ceiling, then throttle further writes in the same tick" in {
      val s = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 3)) // 3 WCU/tick, 1 KB = 1 WCU
      var st = s.initialState

      def put(): Emission[TableState, DynamoDbResponse, DynamoDbConsumption] =
        val e = s.sample(PutItemRequest(1024L), st, rng); st = e.newState; e

      (1 to 3).foreach { _ =>
        val e = put()
        e.output.event shouldBe a[PutItemResponse]
        e.consumption.map(_.event).exists(_.isInstanceOf[WriteCapacityConsumed]) shouldBe true
      }
      st.base.itemCount shouldBe 3L

      val throttled = put() // 4th write: 3 + 1 > 3
      throttled.output.event shouldBe ThrottledResponse
      throttled.consumption.map(_.event) shouldBe List(RequestThrottled(DynamoDbTarget.Table))
      st.base.itemCount shouldBe 3L // no state mutation on a throttle

      // the budget resets at the tick boundary → the next write admits again
      st = s.onTick(1L, st)
      put().output.event shouldBe a[PutItemResponse]
      st.base.itemCount shouldBe 4L
    }

    "throttle per target — a GSI over its own ceiling throttles the whole write, even with base headroom" in {
      val gsi = GlobalSecondaryIndex("g", IndexProjection.All)
      val s   = sampler(BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 100,
                          gsiWriteCapacityUnits = Map("g" -> 2L)), gsis = Vector(gsi)) // base huge, GSI "g" = 2 WCU/tick
      var st  = s.initialState
      def put() = { val e = s.sample(PutItemRequest(1024L), st, rng); st = e.newState; e }

      put().output.event shouldBe a[PutItemResponse] // GSI "g" consumed 1
      put().output.event shouldBe a[PutItemResponse] // GSI "g" consumed 2
      val throttled = put()                           // GSI "g" would hit 3 > 2 (base still fine)
      throttled.output.event shouldBe ThrottledResponse
      throttled.consumption.map(_.event) shouldBe List(RequestThrottled(DynamoDbTarget.Gsi("g")))
    }
  }

  "An on-demand table" should {
    "never throttle, however many writes arrive in a tick" in {
      val s = sampler(BillingMode.OnDemand)
      var st = s.initialState
      (1 to 20).foreach { _ =>
        val e = s.sample(PutItemRequest(1024L), st, rng); st = e.newState
        e.output.event shouldBe a[PutItemResponse]
      }
      st.base.itemCount shouldBe 20L
    }
  }
