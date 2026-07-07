package stochastacy.aws.boundary

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, Graph, Inlet, Outlet}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.collection.mutable

/** Reusable Pekko graph stage modelling a system / interprocess boundary
 *  (network link, cross-AZ / cross-region hop, VPC endpoint, ...).
 *
 *  Slices so far
 *  =============
 *
 *   - S1 skeleton: 5-port shape, identity pass-through, protocol invariants.
 *   - S2 transport latency: each business crossing is delayed by a sampled
 *     latency, applied per direction via the same intra-tick math as
 *     `TableStorageStage` (`rawOffset = intraTick + latencyMs /
 *     (tickDurationSeconds * 1000)`; integer part advances `eventTime`,
 *     fraction becomes the new `intraTick`).  Elements shifted into a later
 *     tick are parked in per-direction `delayBuckets` and drained when that
 *     tick's window opens on the outlet (the window rule).  Control events
 *     (`Tick`, `EndOfTime`) are never delayed.
 *
 *  The two directions forward independently — no cross-direction coupling yet
 *  (that arrives with the drop → timeout cascade in a later slice).  The
 *  consumption outlet still carries no business events; it emits a single
 *  `EndOfTime` once both inputs finish.
 *
 *  Later slices add: `measure` / `timeoutResponse` on the seam, loss, the
 *  drop → timeout cascade, consumption-event metering, and budget dimensions /
 *  throughput limiting.
 *
 *  Bounded state
 *  =============
 *
 *  Per direction: one emit queue (bounded by per-tick volume) and one
 *  `delayBuckets` map whose standing population is bounded by the arrivals
 *  within the latency horizon.  Latency buckets always drain (windows always
 *  advance), so — unlike the budget queues of later slices — they are
 *  self-draining and not hard-capped here.
 */
object SystemBoundaryStage:

  /** Samples a transport latency in milliseconds for one crossing. */
  type LatencyMillisSampler = UniformRandomProvider => Double

  /** @param tickDurationSeconds seconds of wall-clock per tick; converts a
   *                             latency in ms to a fraction of a tick.
   *  @param ingressLatency      request-direction latency (client → service);
   *                             `None` = no delay.
   *  @param egressLatency       response-direction latency (service → client);
   *                             `None` = no delay. */
  final case class Config(
    tickDurationSeconds: Double                       = 1.0,
    ingressLatency:      Option[LatencyMillisSampler] = None,
    egressLatency:       Option[LatencyMillisSampler] = None
  ):
    require(tickDurationSeconds > 0.0,
      s"tickDurationSeconds must be positive, got $tickDurationSeconds")

  def componentOf[Req <: TimedEvent, Resp <: TimedEvent, Cons <: TimedEvent](
    protocol: BoundaryProtocol[Req, Resp],
    config:   Config,
    rng:      UniformRandomProvider
  ): Graph[SystemBoundaryShape[Req, Resp, Cons], NotUsed] =
    new SystemBoundaryStageImpl[Req, Resp, Cons](protocol, config, rng)

// ── GraphStage implementation ─────────────────────────────────────────────────

private final class SystemBoundaryStageImpl[Req <: TimedEvent, Resp <: TimedEvent, Cons <: TimedEvent](
  protocol: BoundaryProtocol[Req, Resp],
  config:   SystemBoundaryStage.Config,
  rng:      UniformRandomProvider
) extends GraphStage[SystemBoundaryShape[Req, Resp, Cons]]:

  val requestIn:      Inlet[TimedElement[Req]]   = Inlet("SystemBoundary.requestIn")
  val requestOut:     Outlet[TimedElement[Req]]  = Outlet("SystemBoundary.requestOut")
  val responseIn:     Inlet[TimedElement[Resp]]  = Inlet("SystemBoundary.responseIn")
  val responseOut:    Outlet[TimedElement[Resp]] = Outlet("SystemBoundary.responseOut")
  val consumptionOut: Outlet[TimedElement[Cons]] = Outlet("SystemBoundary.consumptionOut")

  override val shape: SystemBoundaryShape[Req, Resp, Cons] =
    new SystemBoundaryShape(requestIn, requestOut, responseIn, responseOut, consumptionOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):

      /** One flow direction: latency-aware forwarding from `in` to `out`.
       *
       *  `latency` samples a per-crossing delay in ms; `restamp` rebuilds a
       *  business element with new timing.  Control events pass straight
       *  through; business elements shifted into a later tick are parked in
       *  `delayBuckets` and drained when that tick is forwarded. */
      final class Direction[E <: TimedEvent](
        in:      Inlet[TimedElement[E]],
        out:     Outlet[TimedElement[E]],
        latency: Option[SystemBoundaryStage.LatencyMillisSampler],
        restamp: (E, SimTime, Double) => E
      ):
        private val emitQueue:    mutable.Queue[TimedElement[E]]                 = mutable.Queue.empty
        private val delayBuckets: mutable.Map[Long, mutable.Queue[TimedElement[E]]] = mutable.Map.empty
        private var lastForwardedTick: Long    = Long.MinValue
        var done: Boolean = false

        def start(): Unit = pull(in)

        private def onElement(elem: TimedElement[E]): Unit =
          elem match
            case tick: TimedControlEvent.Tick =>
              val t = tick.eventTime.ticks
              emitQueue.enqueue(tick)
              lastForwardedTick = t
              delayBuckets.remove(t).foreach(_.foreach(emitQueue.enqueue))

            case TimedControlEvent.EndOfTime =>
              // Undrained parked elements target windows beyond the horizon that
              // will never open; drop them (cf. SdkClientStage end-of-stream).
              delayBuckets.clear()
              emitQueue.enqueue(TimedControlEvent.EndOfTime)

            case _ =>
              val business = elem.asInstanceOf[E]   // not a control event ⇒ E
              latency match
                case None =>
                  emitQueue.enqueue(business)
                case Some(sampler) =>
                  val latencyMs    = sampler(rng)
                  val rawOffset    = business.intraTick + latencyMs / (config.tickDurationSeconds * 1000.0)
                  val deltaTicks   = rawOffset.toLong
                  val newIntraTick = rawOffset - deltaTicks
                  val newTick      = business.eventTime.ticks + deltaTicks
                  val restamped    = restamp(business, SimTime.of(newTick), newIntraTick)
                  if newTick <= lastForwardedTick then
                    emitQueue.enqueue(restamped)                       // window already open
                  else
                    delayBuckets.getOrElseUpdate(newTick, mutable.Queue.empty).enqueue(restamped)

        private def emit(): Unit =
          if isAvailable(out) && emitQueue.nonEmpty then
            push(out, emitQueue.dequeue())

        private def maybePullIn(): Unit =
          if emitQueue.isEmpty && !isClosed(in) && !hasBeenPulled(in) then
            pull(in)

        private def checkCompletion(): Unit =
          if done && emitQueue.isEmpty && !isClosed(out) then
            complete(out)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            onElement(grab(in))
            emit()
            maybePullIn()

          override def onUpstreamFinish(): Unit =
            done = true
            checkCompletion()
            checkConsTermination()
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            emit()
            maybePullIn()
            checkCompletion()
        )
      end Direction

      private val requestDir  =
        new Direction[Req](requestIn, requestOut, config.ingressLatency, protocol.withRequestTiming)
      private val responseDir =
        new Direction[Resp](responseIn, responseOut, config.egressLatency, protocol.withResponseTiming)

      private var consEmitted: Boolean = false

      override def preStart(): Unit =
        requestDir.start()
        responseDir.start()

      // ── consumption outlet ──────────────────────────────────────────────
      // Slice 1/2: no business events; emit a single EndOfTime once both inputs
      // have finished, then complete.
      setHandler(consumptionOut, new OutHandler:
        override def onPull(): Unit =
          checkConsTermination()
      )

      private def checkConsTermination(): Unit =
        if requestDir.done && responseDir.done && !consEmitted && isAvailable(consumptionOut) then
          val endOfTime: TimedElement[Cons] = TimedControlEvent.EndOfTime
          push(consumptionOut, endOfTime)
          consEmitted = true
          complete(consumptionOut)
