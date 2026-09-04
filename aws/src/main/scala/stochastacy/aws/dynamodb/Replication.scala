package stochastacy.aws.dynamodb

import stochastacy.core.sampler.StatelessSampler

/**
 * Cross-region replication types for a multi-region Global Table (phase 11). A [[DynamoDbTable]] is a
 * loopback component: an admitted local write is published on its **tap** (loop-out) plane as a
 * [[ReplicationWrite]]; a [[ReplicationCoordinator]] tags it with its source region, delays it per link, and
 * routes it to each peer region's **feedback** (loop-in) input, where the table's `onFeedback` re-applies it
 * (billing rWCU). The same `ReplicationWrite` payload serves as both the tap and the feedback event.
 */

/** A write to replicate / a replicated write to apply — the table's `Tap` and `Fb` payload. */
final case class ReplicationWrite(inner: PutItemRequest | UpdateItemRequest | DeleteItemRequest.type)

/** A tap tagged with the region it originated in — the coordinator's input element. */
final case class TaggedTap(sourceRegion: String, write: ReplicationWrite):
  require(sourceRegion.nonEmpty, "sourceRegion must be non-empty")

/** Per-directional-link replication lag, in **fractional ticks**, drawn per replicated write. Distances are a
 *  property of the `(source, destination)` link (network geography), so lags are keyed by the directed pair;
 *  `default` applies to any link without a specific sampler. */
final case class ReplicationModel(
  perLink: Map[(String, String), StatelessSampler[Double]] = Map.empty,
  default: Option[StatelessSampler[Double]]                = None
):
  def samplerFor(sourceRegion: String, destRegion: String): StatelessSampler[Double] =
    perLink.get((sourceRegion, destRegion)).orElse(default).getOrElse(
      throw new IllegalArgumentException(s"ReplicationModel: no lag sampler for ($sourceRegion -> $destRegion) and no default"))

/** Bytes transferred across a region link by one replicated write — priced downstream per source region. */
final case class CrossRegionTransferEvent(sourceRegion: String, destRegion: String, bytes: Long)

/** The end-to-end replication latency of one applied replicated write, in ticks (link lag in this slice;
 *  link lag + rWCU-backlog wait once depletion is modelled). Reported per source→dest link. */
final case class ReplicationLatencySample(sourceRegion: String, destRegion: String, latencyTicks: Long)

/** The depth of a source→dest replication stream's pending queue at a tick boundary — the backlog indicator. */
final case class PendingReplicationSample(sourceRegion: String, destRegion: String, pendingCount: Long)

/** The coordinator's output plane: replicated writes to route back to region feedback inputs, plus the
 *  transfer and replication-metric events to collect downstream. */
enum ReplicationOutput:
  case ReplicatedWriteFor(destRegion: String, write: ReplicationWrite)
  case Transfer(event: CrossRegionTransferEvent)
  case Latency(sample: ReplicationLatencySample)
  case Pending(sample: PendingReplicationSample)
