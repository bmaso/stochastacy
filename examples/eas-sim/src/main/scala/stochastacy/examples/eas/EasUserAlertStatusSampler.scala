package stochastacy.examples.eas

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.aws.dynamodb.{PutItemRequest, UpdateItemRequest}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey

/**
 * UseCaseSampler for the `user-alert-status` DynamoDB table.
 *
 * Handles three use cases:
 *
 *   S1 — PutItem DELIVERED: Lambda fan-out writes one record per user as SNS push
 *        is sent. Always a new item. Distributed partition access across the user
 *        population — this is an aggregate WCU exhaustion story, not a hot partition.
 *
 *   S2 — UpdateItem OPENED: user taps through the push notification. Item already
 *        exists from S1. Distributed partition access.
 *
 *   S3 — UpdateItem ACKNOWLEDGED: user reads and dismisses the alert. Item already
 *        exists from S2. Distributed partition access. Handled by the same
 *        updateItem() method body as S2 — sample structure is identical.
 *
 * @param config sampler configuration with defaults from the finalized variable ranges
 * @param rng    caller-supplied RNG (single-threaded by the Pekko Streams contract)
 */
final class EasUserAlertStatusSampler(
  config: EasUserAlertStatusConfig,
  rng:    UniformRandomProvider
) extends UseCaseSampler[TableState]:

  /** S1 — PutItem DELIVERED (Lambda fan-out, new item per user). */
  override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
    EasUasPutItemSample(
      writtenItemBytes       = sampleItemBytes(),
      previousItemBytes      = None,   // always a new item — first time this user sees this alert
      logicalPartitionAccess = SingleLogicalPartitionKey(nextUserId())
    )

  /**
   * S2 — UpdateItem OPENED / S3 — UpdateItem ACKNOWLEDGED.
   *
   * Both use-cases share this implementation. The request's `usecase` field
   * distinguishes them at the workload level, but the stochastic sample structure
   * is identical: item exists from a prior write, size drawn from the same
   * uniform distribution.
   */
  override def updateItem(request: UpdateItemRequest, ctx: SamplerContext[TableState]): UpdateItemSample =
    EasUasUpdateItemSample(
      writtenItemBytes       = sampleItemBytes(),
      previousItemBytes      = Some(sampleItemBytes()),  // item exists from S1 (S2) or S2 (S3)
      logicalPartitionAccess = SingleLogicalPartitionKey(nextUserId())
    )

  /** Draw a random userId key uniformly from the user population. */
  private def nextUserId(): String =
    s"user-${rng.nextLong(config.userPopulation)}"

  /** Draw a random item size uniformly from [itemMinBytes, itemMaxBytes]. */
  private def sampleItemBytes(): Long =
    val range = (config.itemMaxBytes - config.itemMinBytes + 1).toInt
    config.itemMinBytes + rng.nextInt(range)


// ── Private sample types ────────────────────────────────────────────────────

private final case class EasUasPutItemSample(
  override val writtenItemBytes:       Long,
  override val previousItemBytes:      Option[Long],
  override val logicalPartitionAccess: LogicalPartitionAccess
) extends PutItemSample

private final case class EasUasUpdateItemSample(
  override val writtenItemBytes:       Long,
  override val previousItemBytes:      Option[Long],
  override val logicalPartitionAccess: LogicalPartitionAccess
) extends UpdateItemSample
