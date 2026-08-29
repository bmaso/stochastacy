package stochastacy.aws.examples.ordertracking

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.{OperationOutcome, ReadShape}
import stochastacy.core.sampler.PoissonSampler

/**
 * The Order-Tracking domain behavior on the v2 [[TableBehavior]] interface — the stochastic decisions
 * the generic table injects (minus the legacy `LogicalPartitionAccess` footprint, which needed a
 * hot-partition / throttling model this scope omits).
 *
 *   - a get finds its item with `getHitProbability` (a miss on an empty table), returning bytes jittered
 *     `±25%` around the current average item size;
 *   - a put always writes a new item;
 *   - an update / delete targets an existing item with its configured probability (an update miss is an
 *     upsert; a delete miss is a no-op);
 *   - a **scan** evaluates the whole target it hits (its item count + projected total bytes), and a
 *     **query** evaluates a bounded "page" (a Poisson draw capped at the target's population) — reads
 *     consult the target's own maintained state, routed in by the sampler.
 */
final class OrderTrackingBehavior(config: OrderTrackingConfig) extends TableBehavior:

  // `tick` is unused — order-tracking's draws are tick-independent.
  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
    request match
      case GetItemRequest =>
        val bytes =
          if state.itemCount <= 0L || rng.nextDouble() > config.getHitProbability then None
          else Some(sampleBytes(state.averageItemBytes.getOrElse(config.initialAverageItemBytes), rng))
        OperationOutcome.Get(bytes, config.readConsistency)

      case PutItemRequest(itemBytes) =>
        OperationOutcome.Put(writtenItemBytes = itemBytes, previousItemBytes = None)

      case UpdateItemRequest(itemBytes) =>
        val previous =
          if state.itemCount > 0L && rng.nextDouble() <= config.updateExistingProbability then state.averageItemBytes
          else None
        OperationOutcome.Update(writtenItemBytes = itemBytes, previousItemBytes = previous)

      case DeleteItemRequest =>
        val deleted =
          if state.itemCount > 0L && rng.nextDouble() <= config.deleteExistingProbability then state.averageItemBytes
          else None
        OperationOutcome.Delete(deletedItemBytes = deleted)

      // Reads consult the *target's* state (routed in by the sampler): a scan evaluates the whole target,
      // a query evaluates a bounded page. RCU (in the mechanics) is charged on the evaluated bytes.
      case s: ScanRequest  => OperationOutcome.Scan(s.target, s.consistency, scanShape(state))
      case q: QueryRequest => OperationOutcome.Query(q.target, q.consistency, queryShape(state, rng))

      case other => throw new IllegalArgumentException(s"the order-tracking workload uses get/put/update/delete/query/scan, not $other")

  /** A scan evaluates the entire target — its item count and (projected) total bytes. */
  private def scanShape(state: TableSummaryState): ReadShape =
    readShape(state, evaluatedItemCount = state.itemCount)

  /** A query evaluates a bounded "page": a Poisson draw (min 1), capped at the target's population. */
  private def queryShape(state: TableSummaryState, rng: UniformRandomProvider): ReadShape =
    if state.itemCount <= 0L then ReadShape(0L, 0L, 0L, 0L)
    else
      val drawn = math.max(1, querySelectivity.sample(0L, rng, ())._1)
      readShape(state, evaluatedItemCount = math.min(state.itemCount, drawn.toLong))

  private val querySelectivity: PoissonSampler = PoissonSampler.constant(config.queryEvaluatedItemsMean)

  /** Build a read shape for `evaluatedItemCount` items of the target, sizing bytes by the target's
   *  (projected) average and returning a `returnedFraction` of them (returned counts are cosmetic). */
  private def readShape(state: TableSummaryState, evaluatedItemCount: Long): ReadShape =
    if evaluatedItemCount <= 0L then ReadShape(0L, 0L, 0L, 0L)
    else
      val avgBytes       = state.averageItemBytes.getOrElse(config.initialAverageItemBytes)
      // For a whole-target scan use the exact stored total; otherwise size by the average.
      val evaluatedBytes = if evaluatedItemCount == state.itemCount then state.totalItemBytes else evaluatedItemCount * avgBytes
      val returnedItems  = math.round(evaluatedItemCount * config.returnedFraction)
      ReadShape(evaluatedItemCount, evaluatedBytes, returnedItems, returnedItems * avgBytes)

  /** Jitter an item size uniformly by ±25% around `mean` (at least one byte) — the legacy `sampleBytes`. */
  private def sampleBytes(mean: Long, rng: UniformRandomProvider): Long =
    val scale = BigDecimal(0.75 + (rng.nextDouble() * 0.5))
    math.max(1L, (BigDecimal(mean) * scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)
