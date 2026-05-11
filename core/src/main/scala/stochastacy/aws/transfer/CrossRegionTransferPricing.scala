package stochastacy.aws.transfer

/**
 * One band in a tiered transfer-pricing schedule.
 *
 * @param tierBytes   Bytes absorbed by this tier before the next tier applies.
 *                    `None` means this tier is unbounded and covers all remaining bytes.
 * @param pricePerGiB Per-GiB price charged for bytes that fall within this tier.
 */
final case class TransferPricingTier(tierBytes: Option[Long], pricePerGiB: BigDecimal):
  require(tierBytes.forall(_ > 0), "tierBytes must be positive when defined")
  require(pricePerGiB >= 0, "pricePerGiB must be non-negative")

/**
 * Pricing rates for cross-region data transfer. Supports tiered pricing (e.g. "first 10 TB at
 * $X/GiB, next 40 TB at $Y/GiB, rest at $Z/GiB") via `tiersBySourceRegion`. Real AWS rates
 * differ ~3x between low-rate regions like `us-east-1` and high-rate regions like
 * `ap-southeast-2`.
 *
 * The `defaultTiers` schedule applies when a source region is absent from the explicit map. If
 * no default is configured and a source region is missing, `price` throws `IllegalArgumentException`.
 *
 * For simple flat-rate configs, use `CrossRegionTransferPricingRates.flat(...)`.
 *
 * Validation: every tier schedule must end with an unbounded tier (`tierBytes = None`) so that
 * all bytes are priced regardless of volume.
 */
final case class CrossRegionTransferPricingRates(
                                                  tiersBySourceRegion: Map[String, Vector[TransferPricingTier]] = Map.empty,
                                                  defaultTiers: Option[Vector[TransferPricingTier]] = None
                                                ):
  require(
    tiersBySourceRegion.values.forall(validSchedule),
    "every tier schedule in tiersBySourceRegion must be non-empty and end with an unbounded tier (tierBytes = None)"
  )
  require(
    defaultTiers.forall(validSchedule),
    "defaultTiers must be non-empty and end with an unbounded tier (tierBytes = None)"
  )

  private def validSchedule(tiers: Vector[TransferPricingTier]): Boolean =
    tiers.nonEmpty && tiers.last.tierBytes.isEmpty

  private[transfer] def tiersFor(sourceRegion: String): Vector[TransferPricingTier] =
    tiersBySourceRegion.get(sourceRegion).orElse(defaultTiers).getOrElse(
      throw new IllegalArgumentException(
        s"No transfer rate configured for source region '$sourceRegion' and no defaultTiers set"
      )
    )

object CrossRegionTransferPricingRates:
  /** Builds a flat (single-tier) pricing config from a simple per-source-region $/GiB map. */
  def flat(
    pricePerGiBBySourceRegion: Map[String, BigDecimal] = Map.empty,
    defaultPricePerGiB: Option[BigDecimal] = None
  ): CrossRegionTransferPricingRates =
    CrossRegionTransferPricingRates(
      tiersBySourceRegion =
        pricePerGiBBySourceRegion.map { case (r, p) => r -> Vector(TransferPricingTier(None, p)) },
      defaultTiers =
        defaultPricePerGiB.map(p => Vector(TransferPricingTier(None, p)))
    )

final case class CrossRegionTransferCostBreakdown(
                                                   totalCost: BigDecimal,
                                                   costByDirectionalPair: Map[(String, String), BigDecimal]
                                                 )

object CrossRegionTransferCostBreakdown:
  private val BytesPerGiB = BigDecimal(1024).pow(3)

  private def priceBytes(bytes: Long, tiers: Vector[TransferPricingTier]): BigDecimal =
    var remaining = bytes
    var cost = BigDecimal(0)
    for tier <- tiers if remaining > 0 do
      val tierBytes = tier.tierBytes match
        case Some(cap) => math.min(remaining, cap)
        case None      => remaining
      cost += BigDecimal(tierBytes) / BytesPerGiB * tier.pricePerGiB
      remaining -= tierBytes
    cost

  def price(
             totals: CrossRegionTransferUsageTotals,
             rates: CrossRegionTransferPricingRates
           ): CrossRegionTransferCostBreakdown =
    // Aggregate bytes per source region across all destinations before applying tiers.
    // AWS data-transfer tiers are per source region (not per directional pair), so a source
    // sending to N destinations uses a single shared tier counter.
    val bytesPerSource: Map[String, Long] =
      totals.byDirectionalPair
        .groupBy { case ((src, _), _) => src }
        .view.mapValues(_.values.map(_.totalBytes).sum)
        .toMap

    val costPerSource: Map[String, BigDecimal] =
      bytesPerSource.map { case (src, totalBytes) =>
        src -> priceBytes(totalBytes, rates.tiersFor(src))
      }

    // Distribute per-source cost to directional pairs proportionally by byte fraction.
    val costByPair: Map[(String, String), BigDecimal] =
      totals.byDirectionalPair.map { case (pair @ (src, _), pairTotals) =>
        val srcTotal = bytesPerSource.getOrElse(src, 0L)
        val pairCost =
          if srcTotal == 0L then BigDecimal(0)
          else costPerSource(src) * BigDecimal(pairTotals.totalBytes) / BigDecimal(srcTotal)
        pair -> pairCost
      }

    // totalCost from costPerSource (not costByPair) to avoid rounding from proportional split.
    CrossRegionTransferCostBreakdown(
      totalCost = costPerSource.values.foldLeft(BigDecimal(0))(_ + _),
      costByDirectionalPair = costByPair
    )
