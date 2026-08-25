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
    val hits = (0 until N).count(_ => p(behavior.outcomeFor(request, state, rng, 1L)))
    hits.toDouble / N

  "OrderTrackingBehavior — request mapping" should {
    "map a put to a new-item Put carrying the request bytes" in {
      behavior.outcomeFor(PutItemRequest(500L), populated, RandomSource.KISS.create(1L), 1L) shouldBe
        OperationOutcome.Put(writtenItemBytes = 500L, previousItemBytes = None)
    }
    "map each request type to its matching outcome case" in {
      val rng = RandomSource.KISS.create(1L)
      behavior.outcomeFor(GetItemRequest,        populated, rng, 1L) shouldBe a[OperationOutcome.Get]
      behavior.outcomeFor(PutItemRequest(500L),  populated, rng, 1L) shouldBe a[OperationOutcome.Put]
      behavior.outcomeFor(UpdateItemRequest(600L), populated, rng, 1L) shouldBe a[OperationOutcome.Update]
      behavior.outcomeFor(DeleteItemRequest,     populated, rng, 1L) shouldBe a[OperationOutcome.Delete]
    }
  }

  "OrderTrackingBehavior — draw rates on a populated table" should {
    "hit gets at ~getHitProbability" in {
      fractionWhere(populated, GetItemRequest) {
        case OperationOutcome.Get(Some(_), _) => true; case _ => false
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
          behavior.outcomeFor(GetItemRequest, populated, rng, 1L) match
            case OperationOutcome.Get(Some(b), _) => Some(b)
            case _                                => None
        }
      hitBytes should not be empty
      all(hitBytes) should (be >= 576L and be <= 960L) // 768 × [0.75, 1.25]
    }
  }

  "OrderTrackingBehavior — on an empty table" should {
    "always miss a get (and draw no randomness for it)" in {
      fractionWhere(emptyTable, GetItemRequest) {
        case OperationOutcome.Get(None, _) => true; case _ => false
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

  "OrderTrackingBehavior — reads (improved model)" should {
    val strong   = ReadConsistency.StronglyConsistent
    val eventual = ReadConsistency.EventuallyConsistent
    val rng      = RandomSource.KISS.create(3L)

    "make a scan evaluate the whole target it is handed (count + projected total bytes)" in {
      behavior.outcomeFor(ScanRequest(DynamoDbTarget.Table, strong), populated, rng, 1L) match
        case OperationOutcome.Scan(target, consistency, shape) =>
          target      shouldBe DynamoDbTarget.Table
          consistency shouldBe strong
          shape.evaluatedItemCount shouldBe 100L
          shape.evaluatedBytes     shouldBe populated.totalItemBytes // 100 x 768 = 76800
          shape.returnedItemCount  should be <= shape.evaluatedItemCount
        case other => fail(s"expected a Scan, got $other")
    }

    "make a query evaluate a bounded page, sized by the target's average, echoing its target/consistency" in {
      val gsi = DynamoDbTarget.Gsi("customerId-status")
      (0 until 1000).foreach { _ =>
        behavior.outcomeFor(QueryRequest(gsi, eventual), populated, rng, 1L) match
          case OperationOutcome.Query(target, consistency, shape) =>
            target      shouldBe gsi
            consistency shouldBe eventual
            shape.evaluatedItemCount should (be >= 1L and be <= 100L)   // >= 1, capped at the population
            shape.evaluatedBytes     shouldBe shape.evaluatedItemCount * 768L
            shape.returnedItemCount  should be <= shape.evaluatedItemCount
          case other => fail(s"expected a Query, got $other")
      }
    }

    "yield a zero shape when the target is empty" in {
      behavior.outcomeFor(ScanRequest(DynamoDbTarget.Table, strong), emptyTable, rng, 1L) match
        case OperationOutcome.Scan(_, _, shape) => shape shouldBe TableMechanics.ReadShape(0L, 0L, 0L, 0L)
        case other => fail(s"expected a Scan, got $other")
      behavior.outcomeFor(QueryRequest(DynamoDbTarget.Table, strong), emptyTable, rng, 1L) match
        case OperationOutcome.Query(_, _, shape) => shape shouldBe TableMechanics.ReadShape(0L, 0L, 0L, 0L)
        case other => fail(s"expected a Query, got $other")
    }
  }
