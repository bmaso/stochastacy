package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.scaladsl.Flow
import org.apache.commons.statistics.distribution.ContinuousDistribution
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.collection.mutable

/**
 * Cross-region replication coordinator. Subscribes to a single merged stream of
 * `OriginTaggedReplicationEvent`s (one tagged per region's outbound replication output),
 * applies a per-link stochastic lag, and emits one `ReplicationOutputEvent` per (origin,
 * destination, write) triple at the appropriate apply tick.
 *
 * Tick handling:
 *   - On each `Tick`, emit the tick first (boundary marker), then drain any queued events
 *     whose `applyTick <=` the new tick. Drained events are restamped with the current
 *     tick's `SimTime` so they appear at the destination's local clock.
 *   - This matches the timed-event protocol: events for tick T appear after the Tick(T)
 *     boundary marker.
 *
 * Lag sampling:
 *   - Per directional link, the configured `ContinuousDistribution` is converted to a
 *     `Sampler` lazily on first use, sharing the model's `UniformRandomProvider`.
 *   - Sampled doubles are floored to non-negative integer ticks: `max(0, floor(sample))`.
 */
private[table] object ReplicationCoordinator:

  /** A validated replication event tagged with its source region. Inputs to the coordinator. */
  final case class OriginTaggedReplicationEvent(
                                                 sourceRegion: String,
                                                 sample: AdmittedRequestSample
                                               ) extends TimedEvent:
    override val eventTime: SimTime = sample.eventTime
    override val usecase: Any = sample.usecase

  /** Outputs from the coordinator. Demultiplexed downstream into per-destination streams plus a transfer-event stream. */
  sealed trait ReplicationOutputEvent extends TimedEvent

  final case class ReplicatedWriteForRegion(
                                             destinationRegion: String,
                                             sample: AdmittedRequestSample
                                           ) extends ReplicationOutputEvent:
    override val eventTime: SimTime = sample.eventTime
    override val usecase: Any = sample.usecase

  final case class TransferEventOutput(event: CrossRegionTransferEvent) extends ReplicationOutputEvent:
    override val eventTime: SimTime = event.eventTime
    override val usecase: Any = event.usecase

  // Internal queue entry for a pending replicated effect.
  private final case class PendingReplication(
                                               sourceRegion: String,
                                               destinationRegion: String,
                                               applyTick: Long,
                                               originSample: AdmittedRequestSample,
                                               transferBytes: Long
                                             )

  private def restampSample(sample: AdmittedRequestSample, applyEventTime: SimTime): AdmittedRequestSample =
    sample match
      case s: AdmittedPutItemSample => s.copy(req = s.req.copy(eventTime = applyEventTime))
      case s: AdmittedUpdateItemSample => s.copy(req = s.req.copy(eventTime = applyEventTime))
      case s: AdmittedDeleteItemSample => s.copy(req = s.req.copy(eventTime = applyEventTime))
      case other => other

  private def replicatedBytesFor(sample: AdmittedRequestSample): Long =
    sample match
      case s: AdmittedPutItemSample => s.sample.writtenItemBytes
      case s: AdmittedUpdateItemSample => s.sample.writtenItemBytes
      case s: AdmittedDeleteItemSample => s.sample.deletedItemBytes.getOrElse(0L)
      case _ => 0L

  private def isReplicatableWrite(sample: AdmittedRequestSample): Boolean =
    sample match
      case _: AdmittedPutItemSample | _: AdmittedUpdateItemSample | _: AdmittedDeleteItemSample => true
      case _ => false

  /**
   * Build the coordinator's core flow. The flow is region-set-aware: when an event arrives
   * from region X, the coordinator fans it out to all peers `Y != X`, sampling lag per link.
   */
  def flowOf(
              regions: Seq[String],
              model: ReplicationModel
            ): Flow[TimedElement[OriginTaggedReplicationEvent], TimedElement[ReplicationOutputEvent], org.apache.pekko.NotUsed] =

    require(regions.nonEmpty, "regions must be non-empty")
    require(regions.distinct.size == regions.size, "regions must not contain duplicates")

    Flow[TimedElement[OriginTaggedReplicationEvent]]
      .statefulMapConcat[TimedElement[ReplicationOutputEvent]] { () =>
        var currentTick: Option[Long] = None

        // Per-destination lag queue. Events appended in arrival order.
        val lagQueues: mutable.Map[String, mutable.Queue[PendingReplication]] =
          mutable.Map.from(regions.map(r => r -> mutable.Queue.empty[PendingReplication]))

        // Per-link sampler cache. Constructed lazily on first use.
        val samplerCache: mutable.Map[(String, String), ContinuousDistribution.Sampler] = mutable.Map.empty

        def samplerFor(source: String, dest: String): ContinuousDistribution.Sampler =
          samplerCache.getOrElseUpdate((source, dest), model.distributionFor(source, dest).createSampler(model.rng))

        def sampleLagTicks(source: String, dest: String): Long =
          val raw = samplerFor(source, dest).sample()
          math.max(0L, math.floor(raw).toLong)

        def drainTo(currentT: Long, applyEventTime: SimTime): Vector[ReplicationOutputEvent] =
          val drained = Vector.newBuilder[ReplicationOutputEvent]
          for (_, queue) <- lagQueues do
            while queue.nonEmpty && queue.head.applyTick <= currentT do
              val pending = queue.dequeue()
              val restamped = restampSample(pending.originSample, applyEventTime)
              drained += ReplicatedWriteForRegion(pending.destinationRegion, restamped)
              drained += TransferEventOutput(
                CrossRegionTransferEvent(
                  eventTime = applyEventTime,
                  usecase = pending.originSample.usecase,
                  sourceRegion = pending.sourceRegion,
                  destinationRegion = pending.destinationRegion,
                  sourceService = "DynamoDB",
                  bytes = pending.transferBytes
                )
              )
          drained.result()

        {
          case t: TimedControlEvent.Tick =>
            val newTick = t.eventTime.ticks
            val advanced = currentTick.exists(_ < newTick) || currentTick.isEmpty
            if advanced then currentTick = Some(newTick)
            // Emit the tick first (boundary marker), then drained events restamped to the
            // current tick's SimTime.
            val drained: Vector[TimedElement[ReplicationOutputEvent]] =
              if advanced then drainTo(newTick, t.eventTime) else Vector.empty
            t +: drained

          case t: TimedControlEvent =>
            List(t)

          case OriginTaggedReplicationEvent(srcRegion, sample) if isReplicatableWrite(sample) =>
            val tickNow = currentTick.getOrElse(sample.eventTime.ticks)
            val tickNowEventTime = currentTick.map(SimTime.of).getOrElse(sample.eventTime)
            val bytes = replicatedBytesFor(sample)
            val immediates = Vector.newBuilder[ReplicationOutputEvent]
            for destRegion <- regions if destRegion != srcRegion do
              val lag = sampleLagTicks(srcRegion, destRegion)
              val applyTick = tickNow + lag
              if applyTick <= tickNow then
                // Zero-lag: apply immediately at the current tick's eventTime.
                val restamped = restampSample(sample, tickNowEventTime)
                immediates += ReplicatedWriteForRegion(destRegion, restamped)
                immediates += TransferEventOutput(
                  CrossRegionTransferEvent(
                    eventTime = tickNowEventTime,
                    usecase = sample.usecase,
                    sourceRegion = srcRegion,
                    destinationRegion = destRegion,
                    sourceService = "DynamoDB",
                    bytes = bytes
                  )
                )
              else
                lagQueues(destRegion).enqueue(
                  PendingReplication(
                    sourceRegion = srcRegion,
                    destinationRegion = destRegion,
                    applyTick = applyTick,
                    originSample = sample,
                    transferBytes = bytes
                  )
                )
            immediates.result()

          case _: OriginTaggedReplicationEvent =>
            // Non-write samples (reads) do not replicate.
            Nil
        }
      }
