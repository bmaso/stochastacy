package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.BinomialDistribution
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Sink, Source}
import org.apache.pekko.stream.{Attributes, Graph, Inlet, Outlet, Shape}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse, ThrottledResponse}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

import scala.collection.{immutable, mutable}

/**
 * Custom Shape for `WorkloadGraph`. Exposes a single response inlet (fed by the simulator's
 * response outlet) and a single request outlet (fed into the simulator's request inlet).
 */
final class WorkloadGraphShape(
  val responseIn: Inlet[TimedElement[DynamoDBResponse]],
  val requestOut: Outlet[TimedElement[DynamoDBRequest]]
) extends Shape:

  override val inlets:  immutable.Seq[Inlet[?]]  = immutable.Seq(responseIn)
  override val outlets: immutable.Seq[Outlet[?]] = immutable.Seq(requestOut)

  override def deepCopy(): WorkloadGraphShape =
    new WorkloadGraphShape(responseIn.carbonCopy(), requestOut.carbonCopy())

/**
 * Factory that builds the workload source graph for a single `WorkloadDefinition`.
 *
 * ── Derived-flows path (resolvedDerived.nonEmpty) ─────────────────────────────
 *
 * Uses `WorkloadRequestBusStage`: a custom `GraphStage[WorkloadGraphShape]` that
 * combines the base `WorkloadRequestStream` iterator with inline
 * `FollowOnTransformerStage` logic — without any graph cycle.
 *
 * The stage drives itself like a pull-based coroutine:
 *
 *   1. While the base iterator has elements, emit them.  When the next base
 *      element is a `Tick(T)`, record it as the "pending tick" and stop
 *      emitting the *next* `Tick(T+1)` until `Tick(T)` has come back on
 *      `responseIn`.  This ensures:
 *
 *        * All responses for tick-T requests have been accumulated before the
 *          stage computes derived requests for that window (the table sends
 *          responses in order, so `Tick(T)` arrives after all tick-T
 *          request responses).
 *
 *        * Derived requests (eventTime = T + lagTicks) are emitted into the
 *          request stream before `Tick(T+lagTicks+1)`, preserving the
 *          timed-event protocol semantics.
 *
 *   2. Derived requests accumulate in an internal queue; they are drained
 *      outlet-first before the next tick advances.
 *
 *   3. The stage completes `requestOut` when:
 *        - the base iterator is exhausted,
 *        - the derived queue is empty, and
 *        - no in-flight DynamoDB requests remain.
 *
 * No graph cycle exists; the circular completion-propagation deadlock that
 * affects `Merge(eagerComplete=false)` topologies cannot occur.
 *
 * ── No-derived-flows path ─────────────────────────────────────────────────────
 *
 * A simple `GraphDSL` graph: base `WorkloadRequestStream` source → `requestOut`;
 * `responseIn` → `Sink.ignore`.
 */
object WorkloadGraph:

  def apply(
    workload:        WorkloadDefinition,
    allWorkloads:    Map[String, WorkloadDefinition],
    rng:             UniformRandomProvider,
    simulationTicks: Long
  ): Graph[WorkloadGraphShape, NotUsed] =

    val streamRng      = RandomSource.KISS.create(rng.nextLong())
    val transformerRng = RandomSource.KISS.create(rng.nextLong())

    val resolvedDerived = FollowOnTransformerStage.resolveFlows(workload, allWorkloads)

    if resolvedDerived.isEmpty then
      // Simple path: no derived flows; ignore all responses.
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*
        val responseFlow = b.add(Flow[TimedElement[DynamoDBResponse]])
        responseFlow.out ~> Sink.ignore
        val baseSource = b.add(
          Source.fromIterator(() => WorkloadRequestStream(workload, streamRng, simulationTicks))
        )
        WorkloadGraphShape(responseFlow.in, baseSource.out)
      }

    else
      new WorkloadRequestBusStage(
        workload, resolvedDerived, streamRng, transformerRng, simulationTicks
      )


// ── Custom GraphStage ────────────────────────────────────────────────────────

/**
 * A `GraphStage[WorkloadGraphShape]` that drives the base `WorkloadRequestStream`
 * iterator and, via inline `FollowOnTransformerStage` logic, generates and emits
 * derived requests — all without any graph cycle.
 *
 * See `WorkloadGraph` scaladoc for the full behavioural contract.
 */
private final class WorkloadRequestBusStage(
  workload:        WorkloadDefinition,
  resolvedDerived: Vector[ResolvedDerivedFlow],
  streamRng:       UniformRandomProvider,
  transformerRng:  UniformRandomProvider,
  simulationTicks: Long
) extends GraphStage[WorkloadGraphShape]:

  val responseIn: Inlet[TimedElement[DynamoDBResponse]] =
    Inlet("WorkloadBus.responseIn")
  val requestOut: Outlet[TimedElement[DynamoDBRequest]] =
    Outlet("WorkloadBus.requestOut")
  val shape = new WorkloadGraphShape(responseIn, requestOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):

      // ── Base stream ──────────────────────────────────────────────────────
      // BufferedIterator provides `.head` peek so we can inspect whether the
      // next element is a Tick before deciding to emit it.
      private val baseIter: BufferedIterator[TimedElement[DynamoDBRequest]] =
        WorkloadRequestStream(workload, streamRng, simulationTicks).buffered

      // ── Derived-request queue ────────────────────────────────────────────
      private val derivedQueue = mutable.Queue.empty[TimedElement[DynamoDBRequest]]

      // ── In-flight count ──────────────────────────────────────────────────
      // DynamoDBRequest elements pushed to requestOut whose response has not
      // yet arrived on responseIn.
      private var inFlight: Int = 0

      // ── Tick-alignment state ─────────────────────────────────────────────
      // After emitting Tick(T) on requestOut, set pendingTick = T.
      // Do NOT emit the next Tick(T+1) until Tick(T) has arrived on
      // responseIn — guaranteeing all tick-T responses are accumulated before
      // derived-request generation.  -1L = not waiting.
      private var pendingTick: Long = -1L

      // ── Outlet-waiting flag ──────────────────────────────────────────────
      private var outletWaiting: Boolean = false

      // ── Inline FollowOnTransformerStage state ────────────────────────────
      private val outcomeCounts =
        mutable.Map.empty[(String, OutcomeFilter), Int]
      private val delayQueues =
        mutable.Map.empty[Long, mutable.Queue[TimedElement[DynamoDBRequest]]]
      private var currentTick: Long = -1L   // last Tick received on responseIn

      // ── Helpers ──────────────────────────────────────────────────────────

      private def ensureResponsePulled(): Unit =
        if !hasBeenPulled(responseIn) && !isClosed(responseIn) then pull(responseIn)

      private def isWaitingForTickResponse: Boolean = pendingTick >= 0L

      /** Pushes the next available element (derived queue first, then base),
       *  or sets `outletWaiting` if no progress is possible right now. */
      private def tryEmitNext(): Unit =
        if derivedQueue.nonEmpty then
          val req = derivedQueue.dequeue()
          push(requestOut, req)
          req match { case _: DynamoDBRequest => inFlight += 1; case _ => }
          ensureResponsePulled()

        else if baseIter.hasNext then
          baseIter.head match

            case _: TimedControlEvent.Tick if isWaitingForTickResponse =>
              // Cannot emit the next Tick yet — stall until the pending tick
              // response comes back from the table.  Keep pulling responses so
              // the table does not stall on its response outlet.
              outletWaiting = true
              ensureResponsePulled()

            case tick: TimedControlEvent.Tick =>
              baseIter.next()
              pendingTick = tick.eventTime.ticks
              push(requestOut, tick)
              ensureResponsePulled()

            case _: DynamoDBRequest =>
              push(requestOut, baseIter.next())
              inFlight += 1
              ensureResponsePulled()

            case _: TimedControlEvent =>
              // EndOfTime and any other control events pass straight through.
              push(requestOut, baseIter.next())
              ensureResponsePulled()

        else
          // Base iterator exhausted.
          if !isWaitingForTickResponse && inFlight == 0 && derivedQueue.isEmpty then
            complete(requestOut)
          else
            // Still waiting for in-flight or pending-tick responses.  Keep
            // pulling so the table does not stall on its response outlet.
            outletWaiting = true
            ensureResponsePulled()

      // ── Inline transformer helpers ────────────────────────────────────────

      private def accumulateResponse(resp: DynamoDBResponse): Unit =
        inFlight -= 1
        resp.flowId.foreach { fid =>
          val filter: OutcomeFilter = resp match
            case _: ThrottledResponse => OutcomeFilter.Throttled
            case _                    => OutcomeFilter.Success
          for f <- resolvedDerived do
            if f.sourceFlowId == fid && f.outcome == filter then
              val key = (fid, filter)
              outcomeCounts(key) = outcomeCounts.getOrElse(key, 0) + 1
        }

      /** Called when `Tick(t)` arrives on `responseIn`.  Mirrors the
       *  tick-boundary logic in `FollowOnTransformerStage`: generates derived
       *  requests for the just-completed window and drains delay-queued batches. */
      private def advanceTick(t: Long): Unit =
        val prevTick = currentTick
        currentTick = t
        if prevTick >= 0L then
          for flow <- resolvedDerived do
            val emitTick = prevTick + flow.lagTicks
            val batch    = deriveRequests(flow, emitTick)
            if batch.nonEmpty then
              if emitTick <= t then derivedQueue.enqueueAll(batch)
              else
                val q = delayQueues.getOrElseUpdate(emitTick, mutable.Queue.empty)
                q ++= batch
        outcomeCounts.clear()
        drainQueues(t).foreach(derivedQueue.enqueue)

      private def deriveRequests(
        flow:     ResolvedDerivedFlow,
        emitTick: Long
      ): Vector[TimedElement[DynamoDBRequest]] =
        val n = outcomeCounts.getOrElse((flow.sourceFlowId, flow.outcome), 0)
        if n == 0 then Vector.empty
        else
          val count =
            if flow.proportion >= 1.0 then n
            else if flow.proportion <= 0.0 then 0
            else BinomialDistribution.of(n, flow.proportion).createSampler(transformerRng).sample()
          Vector.fill(count)(
            WorkloadRequestStream.buildRequest(emitTick, flow.usecase, flow.id, flow.shape, transformerRng, transformerRng.nextDouble())
          )

      private def drainQueues(upTo: Long): Vector[TimedElement[DynamoDBRequest]] =
        val ready = delayQueues.keys.filter(_ <= upTo).toVector.sorted
        ready.flatMap { t => delayQueues.remove(t).fold(Vector.empty)(_.toVector) }

      // ── Handlers ────────────────────────────────────────────────────────

      // Bootstrap demand on responseIn so the cyclic topology
      // (requestOut → table → responseIn) can start.  Without this initial pull,
      // the table's response outlet has no demand, so the table never pulls its
      // request inlet, so requestOut.onPull never fires.
      override def preStart(): Unit =
        pull(responseIn)

      setHandler(requestOut, new OutHandler:
        override def onPull(): Unit =
          outletWaiting = false
          tryEmitNext()
      )

      setHandler(responseIn, new InHandler:
        override def onPush(): Unit =
          grab(responseIn) match
            case tick: TimedControlEvent.Tick =>
              val t = tick.eventTime.ticks
              if pendingTick == t then pendingTick = -1L
              advanceTick(t)
            case resp: DynamoDBResponse =>
              accumulateResponse(resp)
            case _: TimedControlEvent =>
              ()

          if outletWaiting then
            outletWaiting = false
            tryEmitNext()

          // Always keep responseIn pulled while requestOut is open.  Even when
          // we have no immediate processing need, the cycle requires continuous
          // demand on responseIn so the table will continue pulling its request
          // inlet and firing onPull(requestOut).  Idempotent — ensureResponsePulled
          // is a no-op if responseIn is already pulled or closed.
          if !isClosed(requestOut) then ensureResponsePulled()

        override def onUpstreamFinish(): Unit =
          if !isClosed(requestOut) && derivedQueue.isEmpty && inFlight == 0 then
            complete(requestOut)

        override def onUpstreamFailure(ex: Throwable): Unit =
          fail(requestOut, ex)
      )
