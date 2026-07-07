package stochastacy.aws.boundary

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, Graph, Inlet, Outlet}
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}

import scala.collection.mutable

/** Reusable Pekko graph stage modelling a system / interprocess boundary
 *  (network link, cross-AZ / cross-region hop, VPC endpoint, ...).
 *
 *  Slice 1 — skeleton
 *  ==================
 *
 *  This slice establishes the 5-port shape and an **identity pass-through**:
 *  the request direction (`requestIn → requestOut`) and the response direction
 *  (`responseIn → responseOut`) are forwarded unchanged, preserving tick
 *  ordering and the `EndOfTime` terminal sentinel.  The two directions forward
 *  independently — there is no cross-direction coupling yet (that arrives with
 *  the drop → timeout cascade in a later slice).
 *
 *  The consumption outlet carries no business events in this slice; it emits a
 *  single `EndOfTime` once both inputs have finished (a valid, empty timed
 *  stream).  Its tick framing and metering events are added in a later slice.
 *
 *  Later slices add: the `BoundaryProtocol` seam and transport latency (S2);
 *  loss, the drop → timeout cascade, and consumption-event metering (S3);
 *  budget dimensions and throughput limiting (S4).
 *
 *  Bounded state
 *  =============
 *
 *  One emit queue per flow outlet, each gated to at most one buffered element
 *  (the corresponding inlet is re-pulled only once its queue has drained), so
 *  state is bounded by construction.
 */
object SystemBoundaryStage:

  def componentOf[Req <: TimedEvent, Resp <: TimedEvent, Cons <: TimedEvent]()
    : Graph[SystemBoundaryShape[Req, Resp, Cons], NotUsed] =
    new SystemBoundaryStageImpl[Req, Resp, Cons]

// ── GraphStage implementation ─────────────────────────────────────────────────

private final class SystemBoundaryStageImpl[Req <: TimedEvent, Resp <: TimedEvent, Cons <: TimedEvent]
  extends GraphStage[SystemBoundaryShape[Req, Resp, Cons]]:

  val requestIn:      Inlet[TimedElement[Req]]   = Inlet("SystemBoundary.requestIn")
  val requestOut:     Outlet[TimedElement[Req]]  = Outlet("SystemBoundary.requestOut")
  val responseIn:     Inlet[TimedElement[Resp]]  = Inlet("SystemBoundary.responseIn")
  val responseOut:    Outlet[TimedElement[Resp]] = Outlet("SystemBoundary.responseOut")
  val consumptionOut: Outlet[TimedElement[Cons]] = Outlet("SystemBoundary.consumptionOut")

  override val shape: SystemBoundaryShape[Req, Resp, Cons] =
    new SystemBoundaryShape(requestIn, requestOut, responseIn, responseOut, consumptionOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):

      /** At most one buffered element each (pull-after-drain → bounded). */
      private val reqQueue:  mutable.Queue[TimedElement[Req]]  = mutable.Queue.empty
      private val respQueue: mutable.Queue[TimedElement[Resp]] = mutable.Queue.empty

      private var requestInDone:  Boolean = false
      private var responseInDone: Boolean = false
      private var consEmitted:    Boolean = false

      override def preStart(): Unit =
        pull(requestIn)
        pull(responseIn)

      // ── request direction ───────────────────────────────────────────────
      setHandler(requestIn, new InHandler:
        override def onPush(): Unit =
          reqQueue.enqueue(grab(requestIn))
          emitReq()
          maybePullRequestIn()

        override def onUpstreamFinish(): Unit =
          requestInDone = true
          checkReqCompletion()
          checkConsTermination()
      )

      setHandler(requestOut, new OutHandler:
        override def onPull(): Unit =
          emitReq()
          maybePullRequestIn()
          checkReqCompletion()
      )

      private def emitReq(): Unit =
        if isAvailable(requestOut) && reqQueue.nonEmpty then
          push(requestOut, reqQueue.dequeue())

      private def maybePullRequestIn(): Unit =
        if reqQueue.isEmpty && !isClosed(requestIn) && !hasBeenPulled(requestIn) then
          pull(requestIn)

      private def checkReqCompletion(): Unit =
        if requestInDone && reqQueue.isEmpty && !isClosed(requestOut) then
          complete(requestOut)

      // ── response direction ──────────────────────────────────────────────
      setHandler(responseIn, new InHandler:
        override def onPush(): Unit =
          respQueue.enqueue(grab(responseIn))
          emitResp()
          maybePullResponseIn()

        override def onUpstreamFinish(): Unit =
          responseInDone = true
          checkRespCompletion()
          checkConsTermination()
      )

      setHandler(responseOut, new OutHandler:
        override def onPull(): Unit =
          emitResp()
          maybePullResponseIn()
          checkRespCompletion()
      )

      private def emitResp(): Unit =
        if isAvailable(responseOut) && respQueue.nonEmpty then
          push(responseOut, respQueue.dequeue())

      private def maybePullResponseIn(): Unit =
        if respQueue.isEmpty && !isClosed(responseIn) && !hasBeenPulled(responseIn) then
          pull(responseIn)

      private def checkRespCompletion(): Unit =
        if responseInDone && respQueue.isEmpty && !isClosed(responseOut) then
          complete(responseOut)

      // ── consumption outlet ──────────────────────────────────────────────
      // Slice 1: no business events; emit a single EndOfTime once both inputs
      // have finished, then complete.
      setHandler(consumptionOut, new OutHandler:
        override def onPull(): Unit =
          checkConsTermination()
      )

      private def checkConsTermination(): Unit =
        if requestInDone && responseInDone && !consEmitted && isAvailable(consumptionOut) then
          val endOfTime: TimedElement[Cons] = TimedControlEvent.EndOfTime
          push(consumptionOut, endOfTime)
          consEmitted = true
          complete(consumptionOut)
