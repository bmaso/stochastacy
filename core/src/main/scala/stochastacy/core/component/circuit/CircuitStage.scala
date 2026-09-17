package stochastacy.core.component.circuit

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

import org.apache.pekko.stream.{AbruptStageTerminationException, Attributes, FanOutShape2, Graph, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}

import stochastacy.core.component.{Scheduled, TickBoundaryUsecase, Timed}
import stochastacy.sim.*

/**
 * The **circuit stage**: one Pekko stage hosting several sampler nodes and a wiring among them — cycles allowed — run
 * by an internal discrete-event **calendar**. Outside it is an ordinary component (`FanOutShape2`: timed input →
 * forward outputs + consumption facts), exactly like `ScheduleReleaseTransducer.componentOf`. Inside, a routed item
 * re-enters the calendar at `trigger time + delay` for *any* delay, including zero, so a feedback loop closes exactly
 * within a tick instead of waiting for a tick boundary.
 *
 * **How a run proceeds.** External inputs arriving in a tick window are placed on the calendar at their own conceptual
 * time. When the next `Tick(t)` arrives, the window closes:
 *
 *  1. **Dispatch** every calendar event earlier than tick `t`, earliest first by `(tick, intraTick, seq)`. Each event
 *     calls its node's `sample` (port `In`) or `onFeedback` (port `Fb`) with the event's time as `at`. Every emission is
 *     stamped `at.plus(delay)` and **routed**: into a node port (back onto the calendar — if it lands before `t`, it is
 *     dispatched in this same pass, which is how a loop closes within the tick) or into an outlet's pending queue.
 *  2. **Release** the outlet items earlier than `t`, in time order.
 *  3. **Tick** every node (`onTick(t)`, declaration order); boundary consumption facts are stamped at `(t, 0) + delay`,
 *     so they release in a later window.
 *  4. **Forward** `Tick(t)` on both outlets.
 *
 * This is the transducer's own per-tick order (release, then `onTick`, then the tick), so a one-node circuit fed a
 * time-ordered input stream is output-identical to `componentOf`. Unlike the transducer, a circuit processes a window's
 * external inputs in **conceptual-time order**, not arrival order — for an input stream that is not sorted within a
 * tick, a stateful node can legitimately differ.
 *
 * `seq` is a single global counter, incremented on every calendar or outlet enqueue, so ties at equal conceptual time
 * resolve in arrival / emission order — deterministically.
 *
 * **Failure** (the stage fails and the materialized future fails with the same exception): an emission with a negative
 * delay; more than `plan.maxEventsPerWindow` dispatches in one window (a runaway zero-delay cycle); an exception from a
 * node (reported with the node's name and port); an input stamped before the currently open tick.
 *
 * At `EndOfTime`, whatever is still on the calendar or pending on the outlets is post-horizon **residue**: counted in the
 * [[CircuitResult]], never dispatched or emitted.
 */
private[stochastacy] object CircuitStage:

  def componentOf[In, Out, Cons](plan: CircuitPlan): Graph[
    FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
    Future[CircuitResult]
  ] =
    new Stage[In, Out, Cons](plan)

  private val PlaneCount: Int = CircuitPlane.values.length

  /** A pending dispatch: deliver `payload` to `node`'s `port` at conceptual time `(tick, intraTick)`. */
  private final case class Event(tick: Long, intraTick: Double, seq: Long, payload: Any, node: Int, port: Port, usecase: Any)

  /** An outlet item awaiting release at its conceptual time. */
  private final case class Pending(tick: Long, intraTick: Double, seq: Long, item: Timed[Any])

  // PriorityQueue is a max-heap, so both orderings are reversed: the earliest (tick, intraTick, seq) compares greatest.
  private object EventOrder extends Ordering[Event]:
    def compare(a: Event, b: Event): Int =
      val byTick = java.lang.Long.compare(b.tick, a.tick)
      if byTick != 0 then byTick
      else
        val byIntra = java.lang.Double.compare(b.intraTick, a.intraTick)
        if byIntra != 0 then byIntra else java.lang.Long.compare(b.seq, a.seq)

  private object PendingOrder extends Ordering[Pending]:
    def compare(a: Pending, b: Pending): Int =
      val byTick = java.lang.Long.compare(b.tick, a.tick)
      if byTick != 0 then byTick
      else
        val byIntra = java.lang.Double.compare(b.intraTick, a.intraTick)
        if byIntra != 0 then byIntra else java.lang.Long.compare(b.seq, a.seq)

  private final class Stage[In, Out, Cons](plan: CircuitPlan)
      extends GraphStageWithMaterializedValue[
        FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
        Future[CircuitResult]
      ]:

    private val in      = Inlet[TimedElement[Timed[In]]]("Circuit.in")
    private val fwdOut  = Outlet[TimedElement[Timed[Out]]]("Circuit.out")
    private val consOut = Outlet[TimedElement[Timed[Cons]]]("Circuit.cons")
    override val shape: FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]] =
      FanOutShape2(in, fwdOut, consOut)

    // The wiring, resolved once into arrays for the dispatch loop: the input's routes, and each (node, plane)'s
    // routes at index `node * PlaneCount + plane.ordinal`. Read-only, so safe to share across materializations.
    private val inputRoutes: Array[Route] =
      plan.routes.getOrElse(RouteSource.CircuitInput, Vector.empty).toArray
    private val planeRoutes: Array[Array[Route]] =
      Array.tabulate(plan.nodes.size * PlaneCount) { i =>
        plan.routes.getOrElse(RouteSource.NodePlane(i / PlaneCount, CircuitPlane.fromOrdinal(i % PlaneCount)), Vector.empty).toArray
      }

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[CircuitResult]) =
      val resultPromise = Promise[CircuitResult]()

      val logic = new GraphStageLogic(shape):
        private val nodes: Vector[ErasedNode] = plan.nodes
        private val states: Array[Any]        = nodes.map(_.initialState).toArray[Any]
        private val calendar                  = mutable.PriorityQueue.empty[Event](EventOrder)
        private val fwdPending                = mutable.PriorityQueue.empty[Pending](PendingOrder)
        private val consPending               = mutable.PriorityQueue.empty[Pending](PendingOrder)
        private val unrouted                  = new Array[Long](nodes.size * PlaneCount)
        private var unroutedInputs: Long      = 0L
        private var seq: Long                 = 0L
        private var openTick: Long            = Long.MinValue // the window inputs currently belong to
        private var failed: Boolean           = false

        private def nextSeq(): Long =
          val s = seq
          seq += 1
          s

        private def fail(ex: Throwable): Unit =
          if !failed then
            failed = true
            resultPromise.tryFailure(ex)
            failStage(ex)

        private def result(): CircuitResult =
          CircuitResult(
            nodeStates = states.toVector,
            residue    = CircuitResidue(calendar.size.toLong, fwdPending.size.toLong, consPending.size.toLong),
            unrouted   = unrouted.indices.collect {
              case i if unrouted(i) > 0L =>
                UnroutedCount(i / PlaneCount, nodes(i / PlaneCount).name, CircuitPlane.fromOrdinal(i % PlaneCount), unrouted(i))
            }.toVector,
            unroutedInputs = unroutedInputs
          )

        /** Place an external input on the calendar (once per accepting route), at its own conceptual time. */
        private def acceptInput(timed: Timed[Any]): Unit =
          val tick = timed.eventTime.ticks
          if tick < openTick then
            fail(IllegalStateException(s"circuit input stamped at ($tick, ${timed.intraTick}) precedes the open tick $openTick"))
          else
            var accepted = false
            var i        = 0
            while i < inputRoutes.length do
              val r = inputRoutes(i)
              r.transform(timed.event) match
                case Some(v) =>
                  r.target match
                    case RouteTarget.NodePort(n, p) =>
                      accepted = true
                      calendar.enqueue(Event(tick, timed.intraTick, nextSeq(), v, n, p, timed.usecase))
                    case _ => () // plan validation permits the input to target node ports only
                case None => ()
              i += 1
            if !accepted then unroutedInputs += 1

        /** Stamp one emission at `at + delay` and deliver it along every accepting route of its (node, plane). */
        private def route(node: Int, plane: CircuitPlane, sch: Scheduled[Any], at: SimInstant, usecase: Any): Unit =
          if sch.delay < 0.0 then
            fail(IllegalStateException(
              s"circuit node '${nodes(node).name}' (#$node) scheduled a $plane output with negative delay ${sch.delay} " +
                s"at (${at.tick}, ${at.intraTick}); delays must be >= 0"))
          else
            val when      = at.plus(sch.delay)
            val routes    = planeRoutes(node * PlaneCount + plane.ordinal)
            var delivered = false
            var i         = 0
            while i < routes.length do
              val r = routes(i)
              r.transform(sch.event) match
                case Some(v) =>
                  if !r.wiretap then delivered = true // a wiretap copy observes the emission; it doesn't route it
                  r.target match
                    case RouteTarget.NodePort(m, p) =>
                      calendar.enqueue(Event(when.tick, when.intraTick, nextSeq(), v, m, p, usecase))
                    case RouteTarget.ForwardOutlet =>
                      fwdPending.enqueue(Pending(when.tick, when.intraTick, nextSeq(), Timed(v, SimTime.of(when.tick), when.intraTick, usecase)))
                    case RouteTarget.ConsumptionOutlet =>
                      consPending.enqueue(Pending(when.tick, when.intraTick, nextSeq(), Timed(v, SimTime.of(when.tick), when.intraTick, usecase)))
                case None => ()
              i += 1
            if !delivered then unrouted(node * PlaneCount + plane.ordinal) += 1

        /** Dispatch every calendar event earlier than tick `t`, earliest first — including events created by this
         *  pass that also land before `t`. */
        private def dispatchBelow(t: Long): Unit =
          var dispatched = 0L
          while !failed && calendar.nonEmpty && calendar.head.tick < t do
            val ev = calendar.dequeue()
            dispatched += 1
            if dispatched > plan.maxEventsPerWindow then
              fail(IllegalStateException(
                s"circuit dispatched more than ${plan.maxEventsPerWindow} events in the window closing at tick $t " +
                  s"(last: node '${nodes(ev.node).name}' (#${ev.node}) port ${ev.port} at (${ev.tick}, ${ev.intraTick})); " +
                  "a zero-delay cycle may not terminate — raise maxEventsPerWindow if the load is genuine"))
            else
              val node = nodes(ev.node)
              val at   = SimInstant(ev.tick, ev.intraTick)
              try
                val em =
                  if ev.port == Port.In then node.sample(ev.payload, at, states(ev.node))
                  else node.feedback(ev.payload, at, states(ev.node))
                states(ev.node) = em.newState
                // Plane order mirrors the transducer: forward output, then consumption, then taps.
                em.output.foreach(o => route(ev.node, CircuitPlane.Out, o, at, ev.usecase))
                em.consumption.foreach(c => route(ev.node, CircuitPlane.Consumption, c, at, ev.usecase))
                em.taps.foreach(p => route(ev.node, CircuitPlane.Taps, p, at, ev.usecase))
              catch
                case NonFatal(e) =>
                  fail(IllegalStateException(
                    s"circuit node '${node.name}' (#${ev.node}) failed on port ${ev.port} at (${ev.tick}, ${ev.intraTick}): ${e.getMessage}", e))

        /** Advance every node across the boundary into tick `t`, routing boundary consumption facts at `(t, 0) + delay`. */
        private def tickNodes(t: Long): Unit =
          val boundary = SimInstant(t, 0.0)
          var i        = 0
          while !failed && i < nodes.length do
            val idx  = i
            val node = nodes(idx)
            try
              val em = node.tick(t, states(idx))
              states(idx) = em.newState
              em.consumption.foreach(c => route(idx, CircuitPlane.Consumption, c, boundary, TickBoundaryUsecase))
            catch
              case NonFatal(e) =>
                fail(IllegalStateException(s"circuit node '${node.name}' (#$idx) failed in onTick($t): ${e.getMessage}", e))
            i += 1

        private def drainBelow(q: mutable.PriorityQueue[Pending], t: Long): List[TimedElement[Timed[Any]]] =
          val buf = mutable.ListBuffer.empty[TimedElement[Timed[Any]]]
          while q.nonEmpty && q.head.tick < t do buf += q.dequeue().item
          buf.toList

        /** Pull the input only when both outlets can accept output (conservative fan-out demand, as the transducer). */
        private def maybePull(): Unit =
          if !failed && !isClosed(in) && isAvailable(fwdOut) && isAvailable(consOut) && !hasBeenPulled(in) then pull(in)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            grab(in) match
              case tick: TimedControlEvent.Tick =>
                val t = tick.eventTime.ticks
                dispatchBelow(t)
                if !failed then
                  val fwd  = drainBelow(fwdPending, t)
                  val cons = drainBelow(consPending, t)
                  tickNodes(t)
                  if !failed then
                    openTick = t
                    emitMultiple(fwdOut, fwd.asInstanceOf[List[TimedElement[Timed[Out]]]] :+ tick)
                    emitMultiple(consOut, cons.asInstanceOf[List[TimedElement[Timed[Cons]]]] :+ tick)

              case TimedControlEvent.EndOfTime =>
                resultPromise.trySuccess(result())
                var remaining = 2
                val done: () => Unit = () =>
                  remaining -= 1
                  if remaining == 0 then completeStage()
                emit(fwdOut, TimedControlEvent.EndOfTime, done)
                emit(consOut, TimedControlEvent.EndOfTime, done)

              case other =>
                acceptInput(other.asInstanceOf[Timed[Any]])
                maybePull()

          override def onUpstreamFinish(): Unit =
            // Defensive: framing always delivers EndOfTime first (which completes the promise and the stage).
            if !resultPromise.isCompleted then
              resultPromise.trySuccess(result())
              completeStage()
        )

        setHandler(fwdOut, new OutHandler { override def onPull(): Unit = maybePull() })
        setHandler(consOut, new OutHandler { override def onPull(): Unit = maybePull() })

        override def preStart(): Unit = maybePull()

        override def postStop(): Unit =
          if !resultPromise.isCompleted then resultPromise.tryFailure(new AbruptStageTerminationException(this))

      (logic, resultPromise.future)
