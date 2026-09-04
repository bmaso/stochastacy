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
 * **Phase-11 Slice 2:** the queues drain purely by **link lag** — a write enqueued at tick `t` releases at
 * `t + max(1, ⌊lag⌋)` (the minimum 1-tick lag keeps the region↔coordinator cycle deadlock-free: no region's
 * tick depends on another's *same* tick). rWCU is ungated, so nothing backs up beyond its lag;
 * `ReplicationLatency` = link lag and `PendingReplicationCount` = writes still in flight. Slice 3 adds the
 * fair-share rWCU drain that makes both couple to depletion.
 *
 * **Pending is an in-flight count sampled at window close.** A write authored at tick `t` and applied at
 * `t + lag` is genuinely in flight throughout ticks `t … t+lag-1`; a tap for tick `t` arrives on the wire
 * *after* `Tick(t)` (within tick `t`'s window), so the depth is sampled when the window closes — the moment
 * the next `Tick` (or `EndOfTime`) arrives, labeled with the closing tick — after that tick's enqueues but
 * before the next tick's drain. Draining still happens at each `Tick` (releases stamped with that tick).
 */
object ReplicationCoordinator:

  private final case class Pending(
    sourceRegion: String,
    destRegion:   String,
    applyTick:    Long,
    write:        ReplicationWrite,
    bytes:        Long,
    latencyTicks: Long,
    usecase:      Any
  )

  private def bytesFor(write: ReplicationWrite): Long = write.inner match
    case PutItemRequest(b)    => b
    case UpdateItemRequest(b) => b
    case DeleteItemRequest    => 0L

  def flow(
    regions: Vector[String],
    model:   ReplicationModel,
    rng:     UniformRandomProvider
  ): Flow[TimedElement[Timed[TaggedTap]], TimedElement[Timed[ReplicationOutput]], NotUsed] =
    val links: Vector[(String, String)] =
      (for src <- regions; dst <- regions if src != dst yield (src, dst)).sorted

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

      {
        case tick: TimedControlEvent.Tick =>
          val emit = Vector.newBuilder[TimedElement[Timed[ReplicationOutput]]]
          // Close the window that this Tick ends: sample its final in-flight depth before advancing/draining.
          if windowOpen then emit ++= pendingAt(currentTick)
          currentTick = tick.eventTime.ticks
          emit += tick
          for link <- links do
            val q = queues(link)
            while q.nonEmpty && q.head.applyTick <= currentTick do
              val p = q.dequeue()
              emit += Timed(ReplicationOutput.ReplicatedWriteFor(p.destRegion, p.write), SimTime.of(currentTick), 0.0, p.usecase)
              emit += Timed(ReplicationOutput.Transfer(CrossRegionTransferEvent(p.sourceRegion, p.destRegion, p.bytes)), SimTime.of(currentTick), 0.0, p.usecase)
              emit += Timed(ReplicationOutput.Latency(ReplicationLatencySample(p.sourceRegion, p.destRegion, p.latencyTicks)), SimTime.of(currentTick), 0.0, p.usecase)
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
            queues((src, dst)).enqueue(Pending(src, dst, currentTick + lagTicks, write, bytesFor(write), lagTicks, timed.usecase))
          Nil
      }
    }
