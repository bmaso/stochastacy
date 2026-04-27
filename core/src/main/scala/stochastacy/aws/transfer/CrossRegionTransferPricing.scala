package stochastacy.aws.transfer

/**
 * Pricing rates for cross-region data transfer. AWS bills inter-region transfer at the
 * source region's outbound per-GB rate (real rates differ ~3x between low-rate regions like
 * `us-east-1` and high-rate regions like `ap-southeast-2`). Slice 10 uses flat per-source-region
 * rates; tiered pricing is a deferred follow-on.
 *
 * The `defaultPricePerGiB` is applied when an event's source region is not present in the
 * explicit map. Set it to `BigDecimal(0)` to silently price unknown regions at zero, or omit
 * it (set to `None`) to fail fast.
 */
final case class CrossRegionTransferPricingRates(
                                                  pricePerGiBBySourceRegion: Map[String, BigDecimal] = Map.empty,
                                                  defaultPricePerGiB: Option[BigDecimal] = None
                                                ):
  require(
    pricePerGiBBySourceRegion.values.forall(_ >= 0),
    "pricePerGiBBySourceRegion values must be non-negative"
  )
  require(
    defaultPricePerGiB.forall(_ >= 0),
    "defaultPricePerGiB must be non-negative when defined"
  )

  private[transfer] def rateFor(sourceRegion: String): BigDecimal =
    pricePerGiBBySourceRegion.get(sourceRegion).orElse(defaultPricePerGiB).getOrElse(
      throw new IllegalArgumentException(
        s"No transfer rate configured for source region '$sourceRegion' and no defaultPricePerGiB set"
      )
    )

final case class CrossRegionTransferCostBreakdown(
                                                   totalCost: BigDecimal,
                                                   costByDirectionalPair: Map[(String, String), BigDecimal]
                                                 )

object CrossRegionTransferCostBreakdown:
  private val BytesPerGiB = BigDecimal(1024).pow(3)

  def price(
             totals: CrossRegionTransferUsageTotals,
             rates: CrossRegionTransferPricingRates
           ): CrossRegionTransferCostBreakdown =
    val costByPair: Map[(String, String), BigDecimal] =
      totals.byDirectionalPair.map { case (pair @ (sourceRegion, _), pairTotals) =>
        val giB = BigDecimal(pairTotals.totalBytes) / BytesPerGiB
        val cost = giB * rates.rateFor(sourceRegion)
        pair -> cost
      }

    val totalCost = costByPair.values.foldLeft(BigDecimal(0))(_ + _)

    CrossRegionTransferCostBreakdown(
      totalCost = totalCost,
      costByDirectionalPair = costByPair
    )
