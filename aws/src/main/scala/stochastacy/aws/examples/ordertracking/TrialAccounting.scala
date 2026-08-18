package stochastacy.aws.examples.ordertracking

import stochastacy.aws.dynamodb.{DynamoDbConsumption, ReadCapacityConsumed, StorageBytesDelta, WriteCapacityConsumed}
import stochastacy.core.component.Timed
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}

/**
 * Folds a table's consumption stream into a trial's summary totals and per-tick time series, in a single
 * pass so the two always reconcile.
 *
 * Storage is integrated over ticks: `currentBytes` is **seeded with the table's initial storage** (so the
 * pre-loaded items are billed — the correction over the legacy demo, which started from zero) and moved by
 * each `StorageBytesDelta`; on each tick boundary the storage then held is accrued as byte-ticks. The
 * final flush window (the `Tick(N+1)` that closes the last real window) opens a bucket that is never
 * closed, so it is discarded — yielding exactly one point per simulated tick `1..N`.
 */
object TrialAccounting:

  def account(
    consumption:         Seq[TimedElement[Timed[DynamoDbConsumption]]],
    initialStorageBytes: Long,
    rates:               Rates
  ): (TrialSummary, Vector[TrialTimeSeriesPoint]) =
    var currentBytes = initialStorageBytes
    var totalRcu     = BigDecimal(0)
    var totalWcu     = BigDecimal(0)
    var byteTicks    = BigInt(0)

    // cumulative-through-this-tick, for the time series' running cost
    var cumRcu       = BigDecimal(0)
    var cumWcu       = BigDecimal(0)
    var cumByteTicks = BigInt(0)

    var bucketOpen = false
    var bucketTick = 0L
    var bucketRcu  = BigDecimal(0)
    var bucketWcu  = BigDecimal(0)
    val points     = Vector.newBuilder[TrialTimeSeriesPoint]

    def finalizeBucket(): Unit =
      if bucketOpen then
        cumRcu       += bucketRcu
        cumWcu       += bucketWcu
        byteTicks    += BigInt(currentBytes)
        cumByteTicks += BigInt(currentBytes)
        points += TrialTimeSeriesPoint(
          tick                    = bucketTick,
          readCapacityUnits       = bucketRcu,
          writeCapacityUnits      = bucketWcu,
          storageBytes            = currentBytes,
          cumulativeEstimatedCost = OnDemandPricing.cost(cumRcu, cumWcu, cumByteTicks, rates)
        )

    consumption.foreach {
      case tick: TimedControlEvent.Tick =>
        finalizeBucket()
        bucketTick = tick.eventTime.ticks
        bucketRcu  = BigDecimal(0)
        bucketWcu  = BigDecimal(0)
        bucketOpen = true

      case TimedControlEvent.EndOfTime =>
        () // discard the unclosed flush-window bucket

      case timed: Timed[DynamoDbConsumption] @unchecked =>
        timed.event match
          case ReadCapacityConsumed(u, _) => bucketRcu += u; totalRcu += u
          case WriteCapacityConsumed(u)   => bucketWcu += u; totalWcu += u
          case StorageBytesDelta(d)       => currentBytes += d
    }

    val summary = TrialSummary(
      totalReadCapacityUnits  = totalRcu,
      totalWriteCapacityUnits = totalWcu,
      totalStorageByteTicks   = byteTicks,
      finalStorageBytes       = currentBytes,
      totalEstimatedCost      = OnDemandPricing.cost(totalRcu, totalWcu, byteTicks, rates)
    )
    (summary, points.result())
