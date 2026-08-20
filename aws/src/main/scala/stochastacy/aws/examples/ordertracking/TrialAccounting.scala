package stochastacy.aws.examples.ordertracking

import scala.collection.mutable

import stochastacy.aws.dynamodb.{DynamoDbConsumption, DynamoDbTarget, ReadCapacityConsumed, StorageBytesDelta, WriteCapacityConsumed}
import stochastacy.core.component.Timed
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}

/**
 * Folds a table's consumption stream into a trial's summary totals and per-tick time series, in a single
 * pass so the two always reconcile. Capacity is summed **overall** (base + every index) and also broken
 * out **per GSI** (the legacy demo reports per-GSI RCU/WCU; LSI maintenance folds into the overall only).
 *
 * Storage is integrated over ticks: `currentBytes` is **seeded with all targets' initial storage** (base
 * plus each index's projected initial contents — so the pre-loaded items are billed, the correction over
 * the legacy demo which started from zero) and moved by each `StorageBytesDelta`; on each tick boundary
 * the storage then held is accrued as byte-ticks. The final flush window (the `Tick(N+1)` that closes the
 * last real window) opens a bucket that is never closed, so it is discarded — yielding exactly one point
 * per simulated tick `1..N`.
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
    val gsiRcuTotal  = mutable.Map.empty[String, BigDecimal]
    val gsiWcuTotal  = mutable.Map.empty[String, BigDecimal]

    // cumulative-through-this-tick, for the time series' running cost
    var cumRcu       = BigDecimal(0)
    var cumWcu       = BigDecimal(0)
    var cumByteTicks = BigInt(0)

    var bucketOpen   = false
    var bucketTick   = 0L
    var bucketRcu    = BigDecimal(0)
    var bucketWcu    = BigDecimal(0)
    var bucketGsiRcu = mutable.Map.empty[String, BigDecimal]
    var bucketGsiWcu = mutable.Map.empty[String, BigDecimal]
    val points       = Vector.newBuilder[TrialTimeSeriesPoint]

    def bump(m: mutable.Map[String, BigDecimal], key: String, v: BigDecimal): Unit =
      m.update(key, m.getOrElse(key, BigDecimal(0)) + v)

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
          cumulativeEstimatedCost = OnDemandPricing.cost(cumRcu, cumWcu, cumByteTicks, rates),
          gsiReadCapacityUnits    = bucketGsiRcu.toMap,
          gsiWriteCapacityUnits   = bucketGsiWcu.toMap
        )

    consumption.foreach {
      case tick: TimedControlEvent.Tick =>
        finalizeBucket()
        bucketTick   = tick.eventTime.ticks
        bucketRcu    = BigDecimal(0)
        bucketWcu    = BigDecimal(0)
        bucketGsiRcu = mutable.Map.empty
        bucketGsiWcu = mutable.Map.empty
        bucketOpen   = true

      case TimedControlEvent.EndOfTime =>
        () // discard the unclosed flush-window bucket

      case timed: Timed[DynamoDbConsumption] @unchecked =>
        // Overall totals include every target; GSIs are additionally broken out.
        timed.event match
          case ReadCapacityConsumed(u, _, target) =>
            bucketRcu += u; totalRcu += u
            target match { case DynamoDbTarget.Gsi(n) => bump(bucketGsiRcu, n, u); bump(gsiRcuTotal, n, u); case _ => () }
          case WriteCapacityConsumed(u, target) =>
            bucketWcu += u; totalWcu += u
            target match { case DynamoDbTarget.Gsi(n) => bump(bucketGsiWcu, n, u); bump(gsiWcuTotal, n, u); case _ => () }
          case StorageBytesDelta(d, _) =>
            currentBytes += d
    }

    val summary = TrialSummary(
      totalReadCapacityUnits     = totalRcu,
      totalWriteCapacityUnits    = totalWcu,
      totalStorageByteTicks      = byteTicks,
      finalStorageBytes          = currentBytes,
      totalEstimatedCost         = OnDemandPricing.cost(totalRcu, totalWcu, byteTicks, rates),
      gsiTotalReadCapacityUnits  = gsiRcuTotal.toMap,
      gsiTotalWriteCapacityUnits = gsiWcuTotal.toMap
    )
    (summary, points.result())
