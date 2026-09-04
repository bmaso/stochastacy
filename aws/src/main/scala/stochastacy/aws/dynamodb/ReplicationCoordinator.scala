package stochastacy.aws.dynamodb

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import stochastacy.core.component.Timed
import stochastacy.sim.{CoordinatedTimingUsecase, SimTime, TimedControlEvent, TimedElement, ticks}

/**
 * The cross-region replication coordinator: a tick-framed `Flow` from a merged, source-tagged stream of tapped
 * local writes ([[TaggedTap]]) to the replication output plane — replicated writes to route back to peer
 * regions' feedback inputs, plus transfer + latency + pending-count events. It holds one **pending queue per
 * (source → dest) stream** and, after a per-link lag, releases each write to its destination.
 *
 * A write enqueued at tick `t` becomes **eligible** (past its link lag) at `t + max(1, ⌊lag⌋)` (the minimum
 * 1-tick lag keeps the region↔coordinator cycle deadlock-free: no region's tick depends on another's *same*
 * tick). The `Latency` reported at release is the **measured** end-to-end latency `releaseTick − enqueueTick`
 * (= link lag with no backlog; link lag + backlog wait under rWCU depletion).
 *
 * **rWCU throttling (Slice 3).** Each destination optionally carries an inbound **rWCU ceiling**
 * (`rwcuCeilings(dest)`). With no ceiling, every eligible write releases at once (Slice-2 behaviour). With a
 * ceiling `b`, the destination's inbound streams share one budget of `b` rWCU per tick, drained **fair-share,
 * work-conserving**: a round-robin admits one eligible head per source stream per pass (preserving per-stream
 * FIFO) until the budget is spent or no head fits, so an unused share redistributes to the other streams. The
 * ceiling governs the **base-table** rWCU only (`ThroughputMath.writeCapacityUnits(bytes)`, the same figure the
 * destination bills as base rWCU); GSI rWCU rides outside it, as a replica's GSIs carry their own replicated
 * capacity in AWS. Under depletion the heavier source stream develops the deeper, slower-draining queue, so its
 * `PendingReplicationCount` and `ReplicationLatency` diverge above the lighter stream's, both draining on recovery.
 *
 * **Pending is an in-flight count sampled at window close.** A write authored at tick `t` (its tap arrives on
 * the wire *after* `Tick(t)`) and applied at `t + latency` is genuinely in flight throughout ticks
 * `t … applyTick-1`, so the depth is sampled when the window closes — the moment the next `Tick` (or
 * `EndOfTime`) arrives, labeled with the closing tick — after that tick's enqueues but before the next tick's
 * drain. Draining still happens at each `Tick` (releases stamped with that tick).
 */
object ReplicationCoordinator:

  private final case class Pending(
    sourceRegion: String,
    destRegion:   String,
    enqueueTick:  Long,
    applyTick:    Long,
    write:        ReplicationWrite,
    bytes:        Long,
    usecase:      Any
  )

  private def bytesFor(write: ReplicationWrite): Long = write.inner match
    case PutItemRequest(b)    => b
    case UpdateItemRequest(b) => b
    case DeleteItemRequest    => 0L

  /** A write's **base-table** rWCU — the figure the ceiling is drained against, equal to the base rWCU the
   *  destination bills in `onFeedback` (GSI rWCU is billed separately and rides outside the ceiling). */
  private def rwcuOf(bytes: Long): Long = ThroughputMath.writeCapacityUnits(bytes).toLong

  def flow(
    regions:      Vector[String],
    model:        ReplicationModel,
    rwcuCeilings: Map[String, Option[Long]],
    rng:          UniformRandomProvider
  ): Flow[TimedElement[Timed[TaggedTap]], TimedElement[Timed[ReplicationOutput]], NotUsed] =
    val links: Vector[(String, String)] =
      (for src <- regions; dst <- regions if src != dst yield (src, dst)).sorted
    // The inbound source streams feeding each destination, in deterministic (sorted) order.
    val byDest: Map[String, Vector[(String, String)]] = links.groupBy(_._2)

    Flow[TimedElement[Timed[TaggedTap]]].statefulMapConcat { () =>
      var currentTick: Long = 0L
      var windowOpen: Boolean = false
      val queues: mutable.Map[(String, String), mutable.Queue[Pending]] =
        mutable.Map.from(links.map(_ -> mutable.Queue.empty[Pending]))

      // The in-flight depth of every link, stamped at `tick` — sampled when a window closes (so the tick's
      // own enqueues are counted). Emitted for every link so each series is continuous.
      def pendingAt(tick: Long): Vector[TimedElement[Timed[ReplicationOutput]]] =
        links.map { case (src, dst) =>
          Timed(ReplicationOutput.Pending(PendingReplicationSample(src, dst, queues((src, dst)).size.toLong)),
            SimTime.of(tick), 0.0, CoordinatedTimingUsecase)
        }

      // Release one write: the replicated write to its destination's feedback, its transfer bytes, and its
      // measured end-to-end latency (release tick − enqueue tick), all stamped at the release tick.
      def release(p: Pending, into: mutable.Builder[TimedElement[Timed[ReplicationOutput]], ?]): Unit =
        into += Timed(ReplicationOutput.ReplicatedWriteFor(p.destRegion, p.write), SimTime.of(currentTick), 0.0, p.usecase)
        into += Timed(ReplicationOutput.Transfer(CrossRegionTransferEvent(p.sourceRegion, p.destRegion, p.bytes)), SimTime.of(currentTick), 0.0, p.usecase)
        into += Timed(ReplicationOutput.Latency(ReplicationLatencySample(p.sourceRegion, p.destRegion, currentTick - p.enqueueTick)), SimTime.of(currentTick), 0.0, p.usecase)

      def eligible(q: mutable.Queue[Pending]): Boolean = q.nonEmpty && q.head.applyTick <= currentTick

      // Drain a destination's inbound streams for this tick. No ceiling ⇒ every eligible write releases;
      // a ceiling ⇒ fair-share round-robin capped at the per-tick rWCU budget.
      def drainDest(dst: String, into: mutable.Builder[TimedElement[Timed[ReplicationOutput]], ?]): Unit =
        val streams = byDest.getOrElse(dst, Vector.empty).map(queues)
        rwcuCeilings.getOrElse(dst, None) match
          case None =>
            for q <- streams do while eligible(q) do release(q.dequeue(), into)
          case Some(budget) =>
            var remaining = budget
            var progressed = true
            while remaining > 0L && progressed do
              progressed = false
              for q <- streams do
                if eligible(q) then
                  val cost = rwcuOf(q.head.bytes)
                  if cost <= remaining then
                    release(q.dequeue(), into); remaining -= cost; progressed = true

      {
        case tick: TimedControlEvent.Tick =>
          val emit = Vector.newBuilder[TimedElement[Timed[ReplicationOutput]]]
          // Close the window that this Tick ends: sample its final in-flight depth before advancing/draining.
          if windowOpen then emit ++= pendingAt(currentTick)
          currentTick = tick.eventTime.ticks
          emit += tick
          for dst <- regions do drainDest(dst, emit)
          windowOpen = true
          emit.result()

        case TimedControlEvent.EndOfTime =>
          // Close the final open window, then terminate; any residue still queued past the horizon is dropped.
          val tail = if windowOpen then pendingAt(currentTick) else Vector.empty
          windowOpen = false
          tail :+ TimedControlEvent.EndOfTime

        case timed: Timed[TaggedTap] @unchecked =>
          val src   = timed.event.sourceRegion
          val write = timed.event.write
          for dst <- regions if dst != src do
            val raw      = model.samplerFor(src, dst).sample(currentTick, rng, ())._1
            val lagTicks = math.max(1L, math.floor(raw).toLong)
            queues((src, dst)).enqueue(Pending(src, dst, currentTick, currentTick + lagTicks, write, bytesFor(write), timed.usecase))
          Nil
      }
    }
