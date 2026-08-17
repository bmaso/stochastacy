package stochastacy.aws.examples.ordertracking

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

class OrderTrackingBehaviorSpec extends AnyWordSpec with should.Matchers:

  private val config   = OrderTrackingConfig.phase1Default
  private val behavior = OrderTrackingBehavior(config)

  // A populated table whose average item size is exactly 768 bytes.
  private val populated = TableSummaryState.initial(itemCount = 100L, averageItemBytes = 768L)
  private val emptyTable = TableSummaryState.empty

  private val N   = 200000
  private val tol = 0.01

  /** Fraction of `N` draws (against `state`) whose outcome satisfies `p`, over one threaded rng. */
  private def fractionWhere(state: TableSummaryState, request: DynamoDbRequest)(p: OperationOutcome => Boolean): Double =
    val rng = RandomSource.KISS.create(42L)
    val hits = (0 until N).count(_ => p(behavior.outcomeFor(request, state, rng)))
    hits.toDouble / N

  "OrderTrackingBehavior — request mapping" should {
    "map a put to a new-item Put carrying the request bytes" in {
      behavior.outcomeFor(PutItemRequest(500L), populated, RandomSource.KISS.create(1L)) shouldBe
        OperationOutcome.Put(writtenItemBytes = 500L, previousItemBytes = None)
    }
    "map each request type to its matching outcome case" in {
      val rng = RandomSource.KISS.create(1L)
      behavior.outcomeFor(GetItemRequest,        populated, rng) shouldBe a[OperationOutcome.Get]
      behavior.outcomeFor(PutItemRequest(500L),  populated, rng) shouldBe a[OperationOutcome.Put]
      behavior.outcomeFor(UpdateItemRequest(600L), populated, rng) shouldBe a[OperationOutcome.Update]
      behavior.outcomeFor(DeleteItemRequest,     populated, rng) shouldBe a[OperationOutcome.Delete]
    }
  }

  "OrderTrackingBehavior — draw rates on a populated table" should {
    "hit gets at ~getHitProbability" in {
      fractionWhere(populated, GetItemRequest) {
        case OperationOutcome.Get(Some(_)) => true; case _ => false
      } shouldBe config.getHitProbability +- tol
    }
    "find an existing item to update at ~updateExistingProbability" in {
      fractionWhere(populated, UpdateItemRequest(600L)) {
        case OperationOutcome.Update(_, Some(_)) => true; case _ => false
      } shouldBe config.updateExistingProbability +- tol
    }
    "find an existing item to delete at ~deleteExistingProbability" in {
      fractionWhere(populated, DeleteItemRequest) {
        case OperationOutcome.Delete(Some(_)) => true; case _ => false
      } shouldBe config.deleteExistingProbability +- tol
    }
    "sample get-hit bytes within ±25% of the average item size" in {
      val rng = RandomSource.KISS.create(7L)
      val hitBytes =
        (0 until N).flatMap { _ =>
          behavior.outcomeFor(GetItemRequest, populated, rng) match
            case OperationOutcome.Get(Some(b)) => Some(b)
            case _                             => None
        }
      hitBytes should not be empty
      all(hitBytes) should (be >= 576L and be <= 960L) // 768 × [0.75, 1.25]
    }
  }

  "OrderTrackingBehavior — on an empty table" should {
    "always miss a get (and draw no randomness for it)" in {
      fractionWhere(emptyTable, GetItemRequest) {
        case OperationOutcome.Get(None) => true; case _ => false
      } shouldBe 1.0
    }
    "treat every update as an upsert (no previous item)" in {
      fractionWhere(emptyTable, UpdateItemRequest(600L)) {
        case OperationOutcome.Update(600L, None) => true; case _ => false
      } shouldBe 1.0
    }
    "treat every delete as a no-op (no item removed)" in {
      fractionWhere(emptyTable, DeleteItemRequest) {
        case OperationOutcome.Delete(None) => true; case _ => false
      } shouldBe 1.0
    }
  }
