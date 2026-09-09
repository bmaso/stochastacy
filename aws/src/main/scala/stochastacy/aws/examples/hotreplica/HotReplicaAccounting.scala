package stochastacy.aws.examples.hotreplica

import scala.collection.mutable

import stochastacy.aws.dynamodb.*
import stochastacy.aws.examples.demo.{Pricing, Rates}
import stochastacy.core.component.Timed
import stochastacy.core.stats.Statistic
import stochastacy.sim.{TimedControlEvent, TimedElement, ticks}

/** One region's rolled-up accounting for a trial: consumed capacity (RCU / WCU / **rWCU**), integrated
 *  storage, throttles, and the region's cost — capacity + storage, **priced by the region's billing mode
 *  exactly as WCU is** (consumed under on-demand; reserved capacity-hours under provisioned, rWCU included).
 *  Cross-region **transfer** cost is added by the runner from the per-link bytes (egress is a source-region
 *  charge). */
final case class RegionSummary(
  regionName:         String,
  totalRcu:           BigDecimal,
  totalWcu:           BigDecimal,
  totalRwcu:          BigDecimal,
  finalStorageBytes:  Long,
  storageByteTicks:   BigInt,
  throttledRequests:  Long,
  capacityAndStorageCost: BigDecimal,
  writeCost:          BigDecimal, // WCU + rWCU portion, for the cost breakout
  rwcuCost:           BigDecimal
)

/** The incremental fold behind a region's consumption plane — driven by `Sink.fold`, so a region never holds
 *  its raw facts, only the running totals (bounded by ticks × metrics). */
final class RegionAccountingState(
  regionName:      String,
  billingMode:     BillingMode,
  rates:           Rates,
  simulationTicks: Long
):
  private var currentBytes = 0L
  private var totalRcu     = BigDecimal(0)
  private var totalWcu     = BigDecimal(0)
  private var totalRwcu    = BigDecimal(0)
  private var byteTicks    = BigInt(0)
  private var throttled    = 0L
  private var bucketOpen   = false

  def update(element: TimedElement[Timed[DynamoDbConsumption]]): Unit =
    element match
      case _: TimedControlEvent.Tick =>
        if bucketOpen then byteTicks += BigInt(currentBytes) // accrue the tick just ended
        bucketOpen = true
      case TimedControlEvent.EndOfTime =>
        bucketOpen = false // discard the unclosed flush window
      case timed: Timed[DynamoDbConsumption] @unchecked =>
        timed.event match
          case ReadCapacityConsumed(u, _, _)            => totalRcu  += u
          case WriteCapacityConsumed(u, _)              => totalWcu  += u
          case ReplicatedWriteCapacityConsumed(u, _)    => totalRwcu += u
          case StorageBytesDelta(d, _)                  => currentBytes += d
          case RequestThrottled(_)                      => throttled += 1L
          case _: ProvisionedCapacitySnapshot           => ()
          case _: TimeToLiveDeletedItemCount            => () // no TTL in the multi-region demo

  def result(): RegionSummary =
    // Cost by billing mode, mirroring WCU: on-demand bills consumed capacity; provisioned bills the reserved
    // capacity-hours (base + explicitly-provisioned GSIs, and the reserved rWCU when a ceiling is set),
    // consumption-independent. rWCU rides the WCU price/rate in both modes (AWS charges replicated writes at
    // the write rate).
    val (rcuCost, wcuOnlyCost, rwcuCost) = billingMode match
      case p: BillingMode.Provisioned =>
        val r = Pricing.provisionedCost(BigInt(p.totalReadCapacity) * simulationTicks, BigInt(0), rates)
        val w = Pricing.provisionedCost(BigInt(0), BigInt(p.totalWriteCapacity) * simulationTicks, rates)
        val rw = p.replicatedWriteCapacityUnits match
          case Some(ceiling) => Pricing.provisionedCost(BigInt(0), BigInt(ceiling) * simulationTicks, rates)
          case None          => Pricing.consumptionCost(BigDecimal(0), totalRwcu, rates) // no reservation → consumed
        (r, w, rw)
      case BillingMode.OnDemand =>
        (Pricing.consumptionCost(totalRcu, BigDecimal(0), rates),
         Pricing.consumptionCost(BigDecimal(0), totalWcu, rates),
         Pricing.consumptionCost(BigDecimal(0), totalRwcu, rates))
    val storage = Pricing.storageCost(byteTicks, rates)
    RegionSummary(
      regionName             = regionName,
      totalRcu               = totalRcu,
      totalWcu               = totalWcu,
      totalRwcu              = totalRwcu,
      finalStorageBytes      = currentBytes,
      storageByteTicks       = byteTicks,
      throttledRequests      = throttled,
      capacityAndStorageCost = rcuCost + wcuOnlyCost + rwcuCost + storage,
      writeCost              = wcuOnlyCost + rwcuCost,
      rwcuCost               = rwcuCost
    )

/** One `(source → dest)` replication link's rolled-up metrics for a trial: transferred bytes, measured
 *  latency (mean / p95 / max, in ticks), and in-flight backlog (mean / max), plus the per-tick pending and
 *  mean-latency series for the streaming JSONL. */
final case class LinkSummary(
  sourceRegion:   String,
  destRegion:     String,
  transferBytes:  Long,
  latencyMean:    Double,
  latencyP95:     Double,
  latencyMax:     Long,
  pendingMean:    Double,
  pendingMax:     Long,
  perTickPending: Vector[(Long, Long)],
  perTickLatency: Vector[(Long, Double)]
)

/** The incremental fold behind the single replication-metrics plane (`metricsOut`) — one accumulator per
 *  link, updated per transfer/latency/pending event. */
final class ReplicationMetricsState:
  private final class LinkAcc:
    var transferBytes = 0L
    var latency       = Statistic.empty
    var latencyMax    = 0L
    var pending       = Statistic.empty
    var pendingMax    = 0L
    val pendingSeries = mutable.ArrayBuffer.empty[(Long, Long)]
    val latencySumByTick   = mutable.LinkedHashMap.empty[Long, (Double, Long)] // tick -> (sumLatency, count)

  private val links = mutable.LinkedHashMap.empty[(String, String), LinkAcc]
  private def acc(src: String, dst: String): LinkAcc = links.getOrElseUpdate((src, dst), new LinkAcc)

  def update(element: TimedElement[Timed[DynamoDbConsumption]] | TimedElement[Timed[ReplicationOutput]]): Unit =
    element match
      case timed: Timed[ReplicationOutput] @unchecked =>
        timed.event match
          case ReplicationOutput.Transfer(CrossRegionTransferEvent(src, dst, bytes)) =>
            acc(src, dst).transferBytes += bytes
          case ReplicationOutput.Latency(ReplicationLatencySample(src, dst, l)) =>
            val a = acc(src, dst)
            a.latency = a.latency.observe(l.toDouble); a.latencyMax = math.max(a.latencyMax, l)
            val (s, c) = a.latencySumByTick.getOrElse(timed.eventTime.ticks, (0.0, 0L))
            a.latencySumByTick.update(timed.eventTime.ticks, (s + l.toDouble, c + 1L))
          case ReplicationOutput.Pending(PendingReplicationSample(src, dst, n)) =>
            val a = acc(src, dst)
            a.pending = a.pending.observe(n.toDouble); a.pendingMax = math.max(a.pendingMax, n)
            a.pendingSeries += ((timed.eventTime.ticks, n))
          case _: ReplicationOutput.ReplicatedWriteFor => () // routed to feedback, never reaches metricsOut
      case _ => () // control events

  def result(): Vector[LinkSummary] =
    links.toVector.sortBy(_._1).map { case ((src, dst), a) =>
      LinkSummary(
        sourceRegion   = src,
        destRegion     = dst,
        transferBytes  = a.transferBytes,
        latencyMean    = a.latency.mean,
        latencyP95     = a.latency.quantile(0.95),
        latencyMax     = a.latencyMax,
        pendingMean    = a.pending.mean,
        pendingMax     = a.pendingMax,
        perTickPending = a.pendingSeries.toVector,
        perTickLatency = a.latencySumByTick.toVector.map { case (t, (s, c)) => (t, if c == 0 then 0.0 else s / c) }
      )
    }
