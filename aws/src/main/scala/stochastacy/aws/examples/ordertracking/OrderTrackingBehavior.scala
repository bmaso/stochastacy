package stochastacy.aws.examples.ordertracking

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

/**
 * The Order-Tracking domain behavior on the v2 [[TableBehavior]] interface — the stochastic decisions
 * the generic table injects. A faithful port of the legacy `OrderTracking` `UseCaseSampler`, minus the
 * `LogicalPartitionAccess` footprint (Phase-1 has no hot-partition / throttling model, so it never
 * mattered). Query / Scan are absent from the Phase-1 workload and so from this behavior.
 *
 *   - a get finds its item with `getHitProbability` (a miss on an empty table), returning bytes jittered
 *     `±25%` around the current average item size;
 *   - a put always writes a new item;
 *   - an update / delete targets an existing item with its configured probability (an update miss is an
 *     upsert; a delete miss is a no-op).
 */
final class OrderTrackingBehavior(config: OrderTrackingConfig) extends TableBehavior:

  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider): OperationOutcome =
    request match
      case GetItemRequest =>
        val bytes =
          if state.itemCount <= 0L || rng.nextDouble() > config.getHitProbability then None
          else Some(sampleBytes(state.averageItemBytes.getOrElse(config.initialAverageItemBytes), rng))
        OperationOutcome.Get(bytes)

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

  /** Jitter an item size uniformly by ±25% around `mean` (at least one byte) — the legacy `sampleBytes`. */
  private def sampleBytes(mean: Long, rng: UniformRandomProvider): Long =
    val scale = BigDecimal(0.75 + (rng.nextDouble() * 0.5))
    math.max(1L, (BigDecimal(mean) * scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)
