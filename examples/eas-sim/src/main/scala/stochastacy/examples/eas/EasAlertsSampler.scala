package stochastacy.examples.eas

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.LogNormalDistribution
import stochastacy.aws.dynamodb.{GetItemRequest, PutItemRequest, QueryRequest}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey

/**
 * UseCaseSampler for the `alerts` DynamoDB table.
 *
 * Handles three use cases:
 *
 *   A1 — Query (by-region-index GSI): background poll and burst-time read.
 *        All A1 queries hit the same GSI partition key (the region), creating
 *        the hot partition that drives the IIR cliff behavior.
 *
 *   A2 — GetItem (base table): fetch full alert message after an A1 cache miss.
 *        All A2 requests fetch the same alertId, also a hot-partition read.
 *        Item size crosses the 4 KB RCU boundary stochastically.
 *
 *   A3 — PutItem (base table): EMA writes the alert (very low rate, new item).
 *
 * @param config sampler configuration with defaults from the finalized variable ranges
 * @param rng    caller-supplied RNG (single-threaded by the Pekko Streams contract)
 */
final class EasAlertsSampler(
  config: EasAlertsConfig,
  rng:    UniformRandomProvider
) extends UseCaseSampler[TableState]:

  private val logNormalSampler =
    LogNormalDistribution.of(config.fullItemLogNormalMu, config.fullItemLogNormalSigma)
      .createSampler(rng)

  /** A1 — Query by-region-index GSI. */
  override def query(request: QueryRequest, ctx: SamplerContext[TableState]): QuerySample =
    val scannedRange  = config.scannedItemsMax - config.scannedItemsMin + 1
    val scannedCount  = config.scannedItemsMin + rng.nextInt(scannedRange)
    val byteRange     = (config.projectedItemMaxBytes - config.projectedItemMinBytes + 1).toInt
    val perItemBytes  = config.projectedItemMinBytes + rng.nextInt(byteRange)
    val evaluatedBytes = scannedCount.toLong * perItemBytes

    QuerySample(
      evaluatedItemCount  = scannedCount.toLong,
      evaluatedBytes      = evaluatedBytes,
      returnedItemCount   = 1L,
      returnedBytes       = perItemBytes,
      // GSI projection fully covers the query — no base-table fetch needed.
      projectionSatisfaction = ProjectionSatisfaction.FullySatisfiedByIndex,
      // Fixed hot-partition key: all A1 queries hit the same GSI partition.
      logicalPartitionAccess = SingleLogicalPartitionKey(config.region)
    )

  /** A2 — GetItem full alert message. */
  override def getItem(request: GetItemRequest, ctx: SamplerContext[TableState]): GetItemSample =
    val itemBytes = math.max(1L, logNormalSampler.sample().toLong)
    GetItemSample(
      itemBytes              = Some(itemBytes),
      // Fixed hot-partition key: all A2 requests fetch the same alert.
      logicalPartitionAccess = SingleLogicalPartitionKey(config.alertId)
    )

  /** A3 — PutItem: EMA writes the alert (new item, very low rate). */
  override def putItem(request: PutItemRequest, ctx: SamplerContext[TableState]): PutItemSample =
    val writtenBytes = math.max(1L, logNormalSampler.sample().toLong)
    EasPutItemSample(
      writtenItemBytes       = writtenBytes,
      previousItemBytes      = None,   // new alert — no prior item at this key
      logicalPartitionAccess = SingleLogicalPartitionKey(config.alertId)
    )


// ── Private sample types ────────────────────────────────────────────────────

private final case class EasPutItemSample(
  override val writtenItemBytes:       Long,
  override val previousItemBytes:      Option[Long],
  override val logicalPartitionAccess: LogicalPartitionAccess
) extends PutItemSample
