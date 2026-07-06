package stochastacy.aws.dynamodb.client

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FanInShape2, Graph, Inlet, Outlet}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

import scala.collection.mutable

/** Reusable Pekko graph stage modelling an AWS SDK client's retry-and-backoff
 *  behavior.
 *
 *  Topology and role
 *  =================
 *
 *    ─── primary requests ────────►│ in0                        │
 *                                  │      SdkClientStage    out │──► combined requests
 *    ─── responses (feedback) ────►│ in1                        │       (primary + injected retries)
 *
 *  The stage is a FanIn (2 → 1).  The primary inlet carries the base workload's
 *  request stream unmodified; the response inlet carries the DynamoDB service's
 *  response stream — retryable failures observed here trigger new retry requests
 *  injected into the combined output.
 *
 *  Retry semantics
 *  ===============
 *
 *  On each response received on `in1`:
 *   1. If `strategy.retryable(resp)` is true, `resp.clientAttempt + 1 < maxAttempts`,
 *      and a Bernoulli(retryProportion) trial succeeds, a retry is scheduled.
 *   2. The retry's bucket offset (how many ticks after the failure it lands) is
 *      drawn from `BackoffDistribution.bucketWeights` via inverse-CDF sampling.
 *   3. The retry is reconstructed from `resp.originalRequest` — same case class,
 *      same domain parameters — with updated `eventTime`, `intraTick`, and an
 *      incremented `clientAttempt`.
 *   4. The retry lands in an internal `delayBuckets` map keyed by target tick,
 *      and is drained onto `out` when the primary stream advances to that tick.
 *
 *  Tick alignment
 *  ==============
 *
 *  To keep retries in the right batch on the wire, the stage stalls the primary
 *  inlet at each tick boundary: it won't forward `Tick(T+1)` from `in0` until
 *  `Tick(T)` has arrived on `in1`.  This guarantees every retry decision for
 *  tick T is made before tick T+1 is announced downstream — so
 *  `delayBuckets(T+1)` is fully populated before `Tick(T+1)` is forwarded and
 *  the bucket is drained.
 *
 *  Bounded state
 *  =============
 *
 *   - `delayBuckets` is keyed by future-tick number.  Bounded by
 *     `ceil(maxBackoff / tickDuration)` entries (~20 for the standard 20s cap
 *     at 1s ticks).
 *   - `emitQueue` holds items ready for the outlet.  Bounded by the number of
 *     elements per tick, drained as the outlet demands.
 *   - No per-in-flight-request state; the SDK stage relies on `originalRequest`
 *     carried on the response instead of buffering.
 *
 *  Retry-shape approximation
 *  =========================
 *
 *  Retries always land at least one tick after the failure (bucket offset is
 *  clamped to `>= 1`).  This is a small simplification of real SDK behaviour
 *  where sub-tick backoffs could keep a retry inside the same tick as the
 *  failure — it avoids in-tick reordering complexity while introducing at most
 *  one tick of extra latency per attempt.
 *
 *  Termination
 *  ===========
 *
 *  When `in0` completes, `delayBuckets` are dropped (retries whose target tick
 *  is beyond the workload horizon do not fire — realistic since the client
 *  would be shutting down).  The stage completes `out` after the emit queue
 *  drains. */
object SdkClientStage:

  type Shape = FanInShape2[
    TimedElement[DynamoDBRequest],
    TimedElement[DynamoDBResponse],
    TimedElement[DynamoDBRequest]
  ]

  def componentOf(
    strategy:            SdkRetryStrategy,
    tickDurationSeconds: Double,
    rng:                 UniformRandomProvider
  ): Graph[Shape, NotUsed] =
    require(tickDurationSeconds > 0.0,
      s"tickDurationSeconds must be positive, got $tickDurationSeconds")
    new SdkClientStageImpl(strategy, tickDurationSeconds, rng)

  /** Reconstructs a retry request from a template, with updated timing and
   *  attempt number.  Preserves the request's case class, usecase, flowId, and
   *  all domain parameters (itemBytes, target, perItemBytes, etc.).
   *
   *  Package-private so `SdkClientStageSpec` can exercise it in isolation. */
  private[client] def rebuildRetry(
    template:      DynamoDBRequest,
    eventTime:     SimTime,
    intraTick:     Double,
    clientAttempt: Int
  ): DynamoDBRequest =
    template match
      case r: GetItemRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: PutItemRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: UpdateItemRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: DeleteItemRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: QueryRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: ScanRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: PartiQLQueryRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: TransactWriteItemsRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)
      case r: TransactGetItemsRequest =>
        r.copy(eventTime = eventTime, intraTick = intraTick, clientAttempt = clientAttempt)

  /** Inverse-CDF sample of a bucket index from the pre-computed weight vector. */
  private[client] def sampleBucket(weights: Vector[Double], u: Double): Int =
    var cum = 0.0
    var idx = 0
    while idx < weights.length - 1 && cum + weights(idx) < u do
      cum += weights(idx)
      idx += 1
    idx

// ── GraphStage implementation ─────────────────────────────────────────────────

private final class SdkClientStageImpl(
  strategy:            SdkRetryStrategy,
  tickDurationSeconds: Double,
  rng:                 UniformRandomProvider
) extends GraphStage[SdkClientStage.Shape]:

  val in0: Inlet[TimedElement[DynamoDBRequest]]   = Inlet("SdkClient.primaryIn")
  val in1: Inlet[TimedElement[DynamoDBResponse]]  = Inlet("SdkClient.responseIn")
  val out: Outlet[TimedElement[DynamoDBRequest]]  = Outlet("SdkClient.combinedOut")

  override val shape: SdkClientStage.Shape = new FanInShape2(in0, in1, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):

      /** Everything ready to push on `out`, in the correct order. */
      private val emitQueue: mutable.Queue[TimedElement[DynamoDBRequest]] =
        mutable.Queue.empty

      /** Retries scheduled for future ticks, keyed by target tick number.  Bounded
       *  by ceil(maxBackoff / tickDuration) distinct keys. */
      private val delayBuckets: mutable.Map[Long, mutable.Queue[DynamoDBRequest]] =
        mutable.Map.empty

      /** Highest tick T for which we've forwarded Tick(T) on `out` but not yet
       *  observed Tick(T) on `in1`.  -1L when idle. */
      private var pendingTick: Long = -1L

      /** Buffered next-tick element from in0 that we can't forward yet because
       *  we're waiting for the previous tick's response. */
      private var stalledIn0Tick: Option[TimedElement[DynamoDBRequest]] = None

      /** Last tick forwarded on `out` — the current out-side window.  Retries may
       *  only be emitted into the window matching their eventTime (timed-event
       *  protocol); this tracks which window is open. */
      private var lastForwardedTick: Long = 0L

      /** Set once EndOfTime from the primary stream has been enqueued for `out`.
       *  EndOfTime must be the absolute last element of the combined stream, so
       *  no retry may be emitted (or scheduled) after it.  Late responses still
       *  drain from in1, but generate no further retries. */
      private var noMoreRetries: Boolean = false

      // ── preStart ───────────────────────────────────────────────────────────

      override def preStart(): Unit =
        pull(in0)
        pull(in1)

      // ── in0 (primary requests) ─────────────────────────────────────────────

      setHandler(in0, new InHandler:
        override def onPush(): Unit =
          val elem = grab(in0)
          elem match
            case tick: TimedControlEvent.Tick =>
              if pendingTick >= 0L then
                // Stall — must observe Tick(pendingTick) on in1 first.
                stalledIn0Tick = Some(tick)
              else
                forwardTick(tick.eventTime.ticks, tick)
                pull(in0)

            case TimedControlEvent.EndOfTime =>
              // EndOfTime must be the absolute last element on `out`.  From this
              // point on, late responses on in1 generate no further retries, and
              // any parked retries will never fire (their windows never open).
              noMoreRetries = true
              delayBuckets.clear()
              emitQueue.enqueue(elem)
              pull(in0)

            case _ =>
              emitQueue.enqueue(elem)
              pull(in0)
          tryEmit()

        override def onUpstreamFinish(): Unit =
          // Primary stream done.  Drop pending delayBuckets — retries whose
          // target tick was past the workload horizon do not fire.
          delayBuckets.clear()
          stalledIn0Tick = None
          checkCompletion()
      )

      // ── in1 (response feedback) ────────────────────────────────────────────

      setHandler(in1, new InHandler:
        override def onPush(): Unit =
          val elem = grab(in1)
          elem match
            case tick: TimedControlEvent.Tick =>
              val t = tick.eventTime.ticks
              if pendingTick == t then pendingTick = -1L
              // If a next-tick element was stalled awaiting this response, unstall.
              stalledIn0Tick match
                case Some(bufferedTick: TimedControlEvent.Tick) =>
                  forwardTick(bufferedTick.eventTime.ticks, bufferedTick)
                  stalledIn0Tick = None
                  pull(in0)
                case _ => ()

            case resp: DynamoDBResponse =>
              if !noMoreRetries
                 && strategy.retryable(resp)
                 && resp.clientAttempt + 1 < strategy.maxAttempts
                 && rng.nextDouble() < strategy.retryProportion
              then
                resp.originalRequest.foreach { orig =>
                  val nextAttempt  = resp.clientAttempt + 1
                  val weights      = BackoffDistribution.bucketWeights(
                    strategy, nextAttempt, tickDurationSeconds
                  )
                  val bucketOffset = math.max(SdkClientStage.sampleBucket(weights, rng.nextDouble()), 1)
                  val failureTick  = resp.eventTime.ticks
                  val targetTick   = failureTick + bucketOffset

                  // Timed-event-protocol rule: a request with eventTime = E may
                  // only be emitted while the out-side window is E — i.e. after
                  // Tick(E) has been forwarded and before Tick(E+1) has.
                  //
                  //  - targetTick <= lastForwardedTick: the target window is open
                  //    now (or already past).  Emit immediately; if the window has
                  //    moved past the target, slide the retry forward into the
                  //    current window (a real client would fire it now anyway).
                  //  - targetTick > lastForwardedTick: the target window hasn't
                  //    opened yet.  Park in delayBuckets; forwardTick(targetTick)
                  //    drains the bucket the moment the window opens.
                  //
                  // Emitting future-dated events (the previous direct-emit
                  // behaviour) violated the protocol and wedged tick-aligned
                  // stages inside DynamoDbTable, hanging the graph at end of
                  // stream.
                  if targetTick <= lastForwardedTick then
                    val emitTick = lastForwardedTick
                    val retry = SdkClientStage.rebuildRetry(
                      template      = orig,
                      eventTime     = SimTime.of(emitTick),
                      intraTick     = rng.nextDouble(),
                      clientAttempt = nextAttempt
                    )
                    emitQueue.enqueue(retry)
                  else
                    val retry = SdkClientStage.rebuildRetry(
                      template      = orig,
                      eventTime     = SimTime.of(targetTick),
                      intraTick     = rng.nextDouble(),
                      clientAttempt = nextAttempt
                    )
                    delayBuckets.getOrElseUpdate(targetTick, mutable.Queue.empty).enqueue(retry)
                }

            case _ => ()

          if !isClosed(in1) then pull(in1)
          tryEmit()

        override def onUpstreamFinish(): Unit =
          // No more responses can arrive; no more retries will be generated.
          checkCompletion()
      )

      // ── out (combined requests) ────────────────────────────────────────────

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          tryEmit()
      )

      // ── Helpers ────────────────────────────────────────────────────────────

      /** Forward Tick(t) on the emit queue — opening out-side window t — then
       *  drain delayBuckets(t): parked retries whose target window just opened.
       *
       *  Retry emission is split across two moments to honour the timed-event
       *  protocol (every element emitted inside the window matching its
       *  eventTime):
       *
       *   - Retries generated while their target window is ALREADY open
       *     (targetTick <= lastForwardedTick — the common case for lag-1
       *     backoff, where the throttle response arrives inside the target
       *     window) are emitted directly at generation time in the in1 handler.
       *   - Retries targeting a FUTURE window (multi-tick backoff) are parked
       *     in delayBuckets and drained here, the moment Tick(targetTick) is
       *     forwarded.  Their generating responses arrived one or more windows
       *     earlier, so the bucket is always fully populated by the time this
       *     drain runs. */
      private def forwardTick(t: Long, tick: TimedElement[DynamoDBRequest]): Unit =
        emitQueue.enqueue(tick)
        lastForwardedTick = t
        delayBuckets.remove(t).foreach { retries =>
          retries.foreach(r => emitQueue.enqueue(r))
        }
        pendingTick = t

      private def tryEmit(): Unit =
        if isAvailable(out) && emitQueue.nonEmpty then
          push(out, emitQueue.dequeue())
        checkCompletion()

      private def checkCompletion(): Unit =
        if !isClosed(out)
           && isClosed(in0)
           && emitQueue.isEmpty
           && stalledIn0Tick.isEmpty
        then
          complete(out)
