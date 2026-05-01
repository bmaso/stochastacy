package stochastacy.aws.transfer

/**
 * Aggregate totals of cross-region transfer bytes, structurally parallel to
 * `DynamoDbUsageTotals`. Tracks an overall total, plus breakdowns by directional
 * (sourceRegion, destinationRegion) pair and by `sourceService`.
 *
 * Consumers fold a stream of `CrossRegionTransferEvent` through `accumulate` (typically via
 * `Flow[...].scan(CrossRegionTransferUsageTotals())(CrossRegionTransferUsageTotals.accumulate)`)
 * to produce running totals.
 */
final case class CrossRegionTransferTotals(totalBytes: Long = 0L):
  require(totalBytes >= 0L, s"totalBytes must be non-negative, got $totalBytes")

final case class CrossRegionTransferUsageTotals(
                                                 overall: CrossRegionTransferTotals = CrossRegionTransferTotals(),
                                                 byDirectionalPair: Map[(String, String), CrossRegionTransferTotals] = Map.empty,
                                                 byService: Map[String, CrossRegionTransferTotals] = Map.empty
                                               )

object CrossRegionTransferUsageTotals:

  def accumulate(
                  acc: CrossRegionTransferUsageTotals,
                  evt: CrossRegionTransferEvent
                ): CrossRegionTransferUsageTotals =
    val pairKey = (evt.sourceRegion, evt.destinationRegion)
    val pairTotals = acc.byDirectionalPair.getOrElse(pairKey, CrossRegionTransferTotals())
    val serviceTotals = acc.byService.getOrElse(evt.sourceService, CrossRegionTransferTotals())

    acc.copy(
      overall = CrossRegionTransferTotals(acc.overall.totalBytes + evt.bytes),
      byDirectionalPair =
        acc.byDirectionalPair.updated(pairKey, CrossRegionTransferTotals(pairTotals.totalBytes + evt.bytes)),
      byService =
        acc.byService.updated(evt.sourceService, CrossRegionTransferTotals(serviceTotals.totalBytes + evt.bytes))
    )
