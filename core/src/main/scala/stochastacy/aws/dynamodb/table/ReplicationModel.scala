package stochastacy.aws.dynamodb.table

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.statistics.distribution.ContinuousDistribution

/**
 * Configures cross-region replication behavior for a `DynamoDbGlobalTable`. The model carries
 * per-directional-link lag distributions (sampled per replicated write) and an RNG used to
 * drive sampling. Distributions are keyed by `(sourceRegion, destinationRegion)` because real
 * AWS replication paths are determined by network distance and link infrastructure, which is
 * a property of the directional link rather than of the items or use cases being replicated.
 *
 * If a link `(source, dest)` is not present in `perLinkLagDistribution`, `defaultLagDistribution`
 * applies. If neither is set for a given link, the simulator fails fast at construction time.
 *
 * Lag samples are continuous (Double) but ticks are integer; the coordinator floors samples to
 * `max(0, floor(sample))` ticks at apply time.
 */
final case class ReplicationModel(
                                   perLinkLagDistribution: Map[(String, String), ContinuousDistribution] = Map.empty,
                                   defaultLagDistribution: Option[ContinuousDistribution] = None,
                                   rng: UniformRandomProvider
                                 ):

  /**
   * Resolves the distribution to use for a given directional link, falling back to the default
   * if no per-link distribution is configured. Throws if neither is available.
   */
  private[table] def distributionFor(sourceRegion: String, destinationRegion: String): ContinuousDistribution =
    perLinkLagDistribution
      .get((sourceRegion, destinationRegion))
      .orElse(defaultLagDistribution)
      .getOrElse(
        throw new IllegalArgumentException(
          s"No lag distribution configured for replication link " +
            s"($sourceRegion -> $destinationRegion) and no defaultLagDistribution set"
        )
      )
