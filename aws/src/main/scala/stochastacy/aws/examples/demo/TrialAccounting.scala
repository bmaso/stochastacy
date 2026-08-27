package stochastacy.aws.examples.demo

import scala.collection.mutable

import stochastacy.aws.dynamodb.{BillingMode, DynamoDbConsumption, DynamoDbTarget, ReadCapacityConsumed, ReconfigurationSchedule, RequestThrottled, StorageBytesDelta, WriteCapacityConsumed}
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
 *
 * **Billing mode.** Under on-demand, cost is the consumed capacity at its unit prices; under provisioned,
 * cost is the **reserved** capacity (base + each GSI) integrated per tick and priced as capacity-hours,
 * independent of consumption. Consumed capacity is still summed and reported either way. The mode is fixed
 * for the run here (Slice 1); attributing each tick to the mode in force generalizes to a mid-run switch.
 */
object TrialAccounting:

  /** Fold a fully-materialized consumption sequence — a thin convenience over [[TrialAccountingState]]
   *  (the streaming trial runner drives the same state incrementally via `Sink.fold`). */
  def account(
    consumption:         Seq[TimedElement[Timed[DynamoDbConsumption]]],
    initialStorageBytes: Long,
    rates:               Rates,
    billingMode:         BillingMode             = BillingMode.OnDemand,
    gsiNames:            Seq[String]             = Nil,
    schedule:            ReconfigurationSchedule = ReconfigurationSchedule.empty
  ): (TrialSummary, Vector[TrialTimeSeriesPoint]) =
    val state = new TrialAccountingState(initialStorageBytes, rates, billingMode, gsiNames, schedule)
    consumption.foreach(state.update)
    state.result()

/**
 * The mutable accumulator behind [[TrialAccounting]]: the exact single-pass fold, exposed as an
 * `update(element)` / `result()` pair so it can be driven **incrementally** off the live consumption
 * stream (via `Sink.fold`) without ever materializing the raw facts. One instance per trial; `update` is
 * called in stream order, `result()` once at completion.
 */
final class TrialAccountingState(
  initialStorageBytes: Long,
  rates:               Rates,
  billingMode:         BillingMode             = BillingMode.OnDemand,
  gsiNames:            Seq[String]             = Nil,
  schedule:            ReconfigurationSchedule = ReconfigurationSchedule.empty
):
  // Reserved capacity per tick under provisioned billing (base + every GSI); `None` under on-demand — for
  // the mode in force at `tick` (a mid-run reconfiguration switches which mode a tick is billed by).
  private def provisionedPerTick(tick: Long): Option[(BigInt, BigInt)] =
    schedule.billingModeAt(tick, billingMode) match
      case p: BillingMode.Provisioned => Some((BigInt(p.totalReadCapacity(gsiNames)), BigInt(p.totalWriteCapacity(gsiNames))))
      case BillingMode.OnDemand       => None

  private var currentBytes = initialStorageBytes
  private var totalRcu     = BigDecimal(0)
  private var totalWcu     = BigDecimal(0)
  private var byteTicks    = BigInt(0)
  private val gsiRcuTotal  = mutable.Map.empty[String, BigDecimal]
  private val gsiWcuTotal  = mutable.Map.empty[String, BigDecimal]

  // Billable accumulators, attributed to the mode in force each tick: consumed capacity during on-demand
  // ticks, reserved capacity-ticks during provisioned ticks.
  private var onDemandRcu   = BigDecimal(0)
  private var onDemandWcu   = BigDecimal(0)
  private var provRcuTicks  = BigInt(0)
  private var provWcuTicks  = BigInt(0)
  private var throttledReqs = 0L

  // cumulative-through-this-tick, for the time series' running cost
  private var cumOnDemandRcu  = BigDecimal(0)
  private var cumOnDemandWcu  = BigDecimal(0)
  private var cumProvRcuTicks = BigInt(0)
  private var cumProvWcuTicks = BigInt(0)
  private var cumByteTicks    = BigInt(0)

  private var bucketOpen   = false
  private var bucketTick   = 0L
  private var bucketRcu    = BigDecimal(0)
  private var bucketWcu    = BigDecimal(0)
  private var bucketGsiRcu = mutable.Map.empty[String, BigDecimal]
  private var bucketGsiWcu = mutable.Map.empty[String, BigDecimal]
  private val points       = Vector.newBuilder[TrialTimeSeriesPoint]

  private def bump(m: mutable.Map[String, BigDecimal], key: String, v: BigDecimal): Unit =
    m.update(key, m.getOrElse(key, BigDecimal(0)) + v)

  private def finalizeBucket(): Unit =
    if bucketOpen then
      // Attribute this tick to its billing mode: provisioned → accrue reserved capacity-ticks; on-demand →
      // accrue the consumed capacity that gets billed.
      provisionedPerTick(bucketTick) match
        case Some((r, w)) =>
          provRcuTicks += r; cumProvRcuTicks += r
          provWcuTicks += w; cumProvWcuTicks += w
        case None =>
          onDemandRcu    += bucketRcu; cumOnDemandRcu += bucketRcu
          onDemandWcu    += bucketWcu; cumOnDemandWcu += bucketWcu
      byteTicks    += BigInt(currentBytes)
      cumByteTicks += BigInt(currentBytes)
      points += TrialTimeSeriesPoint(
        tick                    = bucketTick,
        readCapacityUnits       = bucketRcu,
        writeCapacityUnits      = bucketWcu,
        storageBytes            = currentBytes,
        cumulativeEstimatedCost = Pricing.consumptionCost(cumOnDemandRcu, cumOnDemandWcu, rates)
                                    + Pricing.provisionedCost(cumProvRcuTicks, cumProvWcuTicks, rates)
                                    + Pricing.storageCost(cumByteTicks, rates),
        gsiReadCapacityUnits    = bucketGsiRcu.toMap,
        gsiWriteCapacityUnits   = bucketGsiWcu.toMap
      )

  def update(element: TimedElement[Timed[DynamoDbConsumption]]): Unit =
    element match
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
          case RequestThrottled(_) =>
            throttledReqs += 1L

  def result(): (TrialSummary, Vector[TrialTimeSeriesPoint]) =
    val summary = TrialSummary(
      totalReadCapacityUnits     = totalRcu,
      totalWriteCapacityUnits    = totalWcu,
      totalStorageByteTicks      = byteTicks,
      finalStorageBytes          = currentBytes,
      totalEstimatedCost         = Pricing.consumptionCost(onDemandRcu, onDemandWcu, rates)
                                     + Pricing.provisionedCost(provRcuTicks, provWcuTicks, rates)
                                     + Pricing.storageCost(byteTicks, rates),
      gsiTotalReadCapacityUnits  = gsiRcuTotal.toMap,
      gsiTotalWriteCapacityUnits = gsiWcuTotal.toMap,
      totalProvisionedReadCapacityUnitTicks  = provRcuTicks,
      totalProvisionedWriteCapacityUnitTicks = provWcuTicks,
      totalThrottledRequests                 = throttledReqs
    )
    (summary, points.result())
