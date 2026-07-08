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
 *     latency (same intra-tick math as `TableStorageStage`); elements shifted
 *     into a later tick are parked in per-direction `delayBuckets` and drained
 *     when that tick's window opens.
 *   - S3a loss + drop → timeout cascade: each crossing may be dropped
 *     (`Bernoulli(loss)`, checked before latency).  A dropped request (ingress)
 *     is not forwarded; a retryable `timeoutResponse` is injected onto
 *     `responseOut` at the request's window.  A dropped response (egress) is
 *     replaced by a timeout built from its originating request.  Ingress-drop
 *     timeouts cross to the response outlet under the window rule
 *     (emit-now-if-open-else-park), never future-dated.  `responseOut` holds
 *     its terminal `EndOfTime` until the request side has also ended, so a
 *     late ingress drop can still inject its timeout.
 *
 *  The consumption outlet still carries no business events; it emits a single
 *  `EndOfTime` once both inputs finish (metering arrives in slice 3b).
 *
 *  Bounded state
 *  =============
 *
 *  Per direction: an emit queue (bounded by per-tick volume) and latency
 *  `delayBuckets` (self-draining).  The response direction additionally holds
 *  an injected-timeout backlog, hard-capped at `maxPendingTimeouts`
 *  (tail-dropped on overflow — that request's retry never fires), tracked
 *  separately from latency buckets so real responses are never evicted.
 */
object SystemBoundaryStage:

  /** Samples a transport latency in milliseconds for one crossing. */
  type LatencyMillisSampler = UniformRandomProvider => Double

  /** @param tickDurationSeconds    seconds of wall-clock per tick.
   *  @param ingressLatency         request-direction latency; `None` = none.
   *  @param egressLatency          response-direction latency; `None` = none.
   *  @param ingressLossProbability request-side drop probability (never reaches
   *                               the service — no capacity consumed).
   *  @param egressLossProbability  response-side drop probability (service did
   *                               the work, response lost — retry duplicates it).
   *  @param maxPendingTimeouts     hard cap on the injected-timeout backlog. */
  final case class Config(
    tickDurationSeconds:    Double                       = 1.0,
    ingressLatency:         Option[LatencyMillisSampler] = None,
    egressLatency:          Option[LatencyMillisSampler] = None,
    ingressLossProbability: Double                       = 0.0,
    egressLossProbability:  Double                       = 0.0,
    maxPendingTimeouts:     Int                          = 100000
  ):
    require(tickDurationSeconds > 0.0,
      s"tickDurationSeconds must be positive, got $tickDurationSeconds")
    require(ingressLossProbability >= 0.0 && ingressLossProbability <= 1.0,
      s"ingressLossProbability must be in [0,1], got $ingressLossProbability")
    require(egressLossProbability >= 0.0 && egressLossProbability <= 1.0,
      s"egressLossProbability must be in [0,1], got $egressLossProbability")
    require(maxPendingTimeouts > 0,
      s"maxPendingTimeouts must be positive, got $maxPendingTimeouts")

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

      /** One flow direction: loss-and-latency-aware forwarding from `in` to `out`.
       *
       *  On a business element: `Bernoulli(lossProbability)` may drop it (before
       *  latency) via `dropHandler`; otherwise transport latency shifts it,
       *  parking it in `delayBuckets` if it lands in a later tick.  The response
       *  direction also accepts injected timeouts via `inject`, and holds its
       *  terminal `EndOfTime` until `releaseGuard` is satisfied. */
      final class Direction[E <: TimedEvent](
        in:              Inlet[TimedElement[E]],
        out:             Outlet[TimedElement[E]],
        latency:         Option[SystemBoundaryStage.LatencyMillisSampler],
        restamp:         (E, SimTime, Double) => E,
        lossProbability: Double
      ):
        private val emitQueue:       mutable.Queue[TimedElement[E]]                 = mutable.Queue.empty
        private val delayBuckets:    mutable.Map[Long, mutable.Queue[TimedElement[E]]] = mutable.Map.empty
        private val injectedBuckets: mutable.Map[Long, mutable.Queue[E]]           = mutable.Map.empty
        private var injectedCount:   Int  = 0
        private var lastForwardedTick: Long = Long.MinValue

        /** Set once this inlet's terminal `EndOfTime` element has been observed. */
        var endOfTimeSeen: Boolean = false
        /** Set once this outlet's terminal `EndOfTime` has been enqueued. */
        var endOfTimeForwarded: Boolean = false

        /** Gate for forwarding the terminal `EndOfTime` — lets the response side
         *  wait until the request side has ended before finalizing. */
        var releaseGuard: () => Boolean = () => true
        /** Called after this inlet's `EndOfTime` element is observed. */
        var onEndOfTimeSeen: () => Unit = () => ()

        /** Handles a dropped business element `(element, its window)`.  Wired by
         *  the enclosing logic — ingress crosses to the response outlet, egress
         *  replaces inline. */
        var dropHandler: (E, Long) => Unit = (_, _) => ()

        def start(): Unit = pull(in)

        /** Inject a synthetic timeout targeting window `targetWindow`.  Window
         *  rule: if the target window is already open, emit now (restamped to the
         *  current window); otherwise park until it opens.  Never future-dated.
         *  Dropped if the terminal `EndOfTime` has already been forwarded, or the
         *  backlog is at cap. */
        def inject(timeout: E, targetWindow: Long): Unit =
          if endOfTimeForwarded then ()
          else if targetWindow <= lastForwardedTick then
            emitQueue.enqueue(restamp(timeout, SimTime.of(lastForwardedTick), timeout.intraTick))
            emit()
          else if injectedCount >= config.maxPendingTimeouts then ()   // tail-drop
          else
            injectedCount += 1
            injectedBuckets.getOrElseUpdate(targetWindow, mutable.Queue.empty).enqueue(timeout)

        /** Forward the terminal `EndOfTime` if it has been seen and the release
         *  guard is satisfied.  Drops any still-parked latency/injected elements
         *  (their target windows will never open). */
        def forwardEndOfTimeIfReady(): Unit =
          if endOfTimeSeen && !endOfTimeForwarded && releaseGuard() then
            delayBuckets.clear()
            injectedBuckets.clear()
            injectedCount = 0
            emitQueue.enqueue(TimedControlEvent.EndOfTime)
            endOfTimeForwarded = true
            emit()
            checkCompletion()
            checkConsTermination()

        private def onElement(elem: TimedElement[E]): Unit =
          elem match
            case tick: TimedControlEvent.Tick =>
              val t = tick.eventTime.ticks
              emitQueue.enqueue(tick)
              lastForwardedTick = t
              delayBuckets.remove(t).foreach(_.foreach(emitQueue.enqueue))
              injectedBuckets.remove(t).foreach { q =>
                injectedCount -= q.size
                q.foreach(emitQueue.enqueue)
              }

            case TimedControlEvent.EndOfTime =>
              endOfTimeSeen = true
              forwardEndOfTimeIfReady()
              onEndOfTimeSeen()

            case _ =>
              val business = elem.asInstanceOf[E]   // not a control event ⇒ E
              if lossProbability > 0.0 && rng.nextDouble() < lossProbability then
                dropHandler(business, business.eventTime.ticks)
              else
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
                      emitQueue.enqueue(restamped)
                    else
                      delayBuckets.getOrElseUpdate(newTick, mutable.Queue.empty).enqueue(restamped)

        private def emit(): Unit =
          if isAvailable(out) && emitQueue.nonEmpty then
            push(out, emitQueue.dequeue())

        private def maybePullIn(): Unit =
          if emitQueue.isEmpty && !isClosed(in) && !hasBeenPulled(in) then
            pull(in)

        def checkCompletion(): Unit =
          if endOfTimeForwarded && emitQueue.isEmpty && !isClosed(out) then
            complete(out)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            onElement(grab(in))
            emit()
            maybePullIn()

          override def onUpstreamFinish(): Unit =
            // Terminal EndOfTime is delivered as an element; onUpstreamFinish is
            // the follow-up close.  Completion is driven by endOfTimeForwarded.
            checkCompletion()
            checkConsTermination()
        )

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            emit()
            maybePullIn()
            checkCompletion()
            checkConsTermination()
        )
      end Direction

      private val requestDir  =
        new Direction[Req](requestIn, requestOut, config.ingressLatency,
          protocol.withRequestTiming, config.ingressLossProbability)
      private val responseDir =
        new Direction[Resp](responseIn, responseOut, config.egressLatency,
          protocol.withResponseTiming, config.egressLossProbability)

      // The response outlet holds its terminal EndOfTime until the request side
      // has ended — a late ingress drop may still need to inject a timeout.
      responseDir.releaseGuard = () => requestDir.endOfTimeSeen
      requestDir.onEndOfTimeSeen = () =>
        responseDir.forwardEndOfTimeIfReady()
        checkConsTermination()

      // Ingress drop: request never reaches the service; inject a timeout onto
      // the response outlet at the request's window (cross-direction window rule).
      requestDir.dropHandler = (req, window) =>
        val timeout = protocol.timeoutResponse(req, SimTime.of(window), rng.nextDouble(), BoundaryDropDirection.Ingress)
        responseDir.inject(timeout, window)

      // Egress drop: service did the work but the response was lost; replace it
      // with a timeout built from its originating request, in the same window.
      responseDir.dropHandler = (resp, window) =>
        protocol.originalRequestOf(resp).foreach { req =>
          val timeout = protocol.timeoutResponse(req, SimTime.of(window), resp.intraTick, BoundaryDropDirection.Egress)
          responseDir.inject(timeout, window)
        }

      private var consEmitted: Boolean = false

      override def preStart(): Unit =
        requestDir.start()
        responseDir.start()

      // ── consumption outlet ──────────────────────────────────────────────
      // Slices 1–3a: no business events; emit a single EndOfTime once both
      // flow directions have forwarded their terminal EndOfTime.
      setHandler(consumptionOut, new OutHandler:
        override def onPull(): Unit =
          checkConsTermination()
      )

      private def checkConsTermination(): Unit =
        if requestDir.endOfTimeForwarded && responseDir.endOfTimeForwarded
           && !consEmitted && isAvailable(consumptionOut) then
          val endOfTime: TimedElement[Cons] = TimedControlEvent.EndOfTime
          push(consumptionOut, endOfTime)
          consEmitted = true
          complete(consumptionOut)
