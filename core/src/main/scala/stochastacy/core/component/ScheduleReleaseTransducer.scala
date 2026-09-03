package stochastacy.core.component

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{Attributes, FanOutShape2, Graph, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import stochastacy.sim.*

/** The schedule-and-release transducer — the generic machinery that turns a [[ComponentSampler]]
 *  into a running component.
 *
 *  It consumes a framed `TimedElement[Timed[In]]` stream and produces two framed output streams —
 *  forward outputs and consumption facts — each carrying every control event. For each input it
 *  unwraps the `Timed[In]` envelope, runs the sampler on the payload, advances state, stamps each
 *  scheduled output's absolute `(eventTime, intraTick)` from its delay, and buffers it. Buffered
 *  outputs are **released in time order at tick boundaries**: on `Tick(t)` everything with
 *  `eventTime < t` (the just-closed window) is emitted, then `Tick(t)` is passed through.
 *
 *  Its **materialized value** is a `Future[ComponentResult[S]]`, completed at `EndOfTime` with the
 *  final state and a summary of any still-pending (post-horizon) outputs. Post-horizon residue is
 *  summarized, not emitted — the streams end cleanly at `EndOfTime`.
 *
 *  Timing model (mirrors the intra-tick `rawOffset` rule): for an input at
 *  `(inEventTime, inIntraTick)` and an output `delay` (fractional ticks),
 *  `rawOffset = inIntraTick + delay`, `eventTime = inEventTime + floor(rawOffset)`,
 *  `intraTick = rawOffset - floor(rawOffset)`. */
object ScheduleReleaseTransducer:

  def componentOf[S, In, Out, Cons](
    sampler: ComponentSampler[S, In, Out, Cons],
    rng:     UniformRandomProvider
  ): Graph[
    FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
    Future[ComponentResult[S]]
  ] =
    new Stage(sampler, rng)

  /** Run a **loopback-capable** sampler as a running component: like [[componentOf]] but with a feedback
   *  inlet and a tap outlet (see [[LoopbackShape]]). `in` is the sole tick clock; `tapOut` forwards `Tick(t)`
   *  eagerly so the component can sit in a delayed self-loop without deadlock; `fbIn` events are dispatched to
   *  the sampler's `onFeedback`. A window `t-1` closes (its fwd/cons outputs release) only once **both** `in`
   *  and `fbIn` have reached `Tick(t)`, so a fed-back effect for that window is applied before its outputs. */
  def loopbackComponentOf[S, In, Fb, Out, Cons, Tap](
    sampler: LoopbackComponentSampler[S, In, Fb, Out, Cons, Tap],
    rng:     UniformRandomProvider
  ): Graph[LoopbackShape[In, Fb, Out, Cons, Tap], Future[ComponentResult[S]]] =
    new LoopbackStage(sampler, rng)

  private final class Stage[S, In, Out, Cons](
    sampler: ComponentSampler[S, In, Out, Cons],
    rng:     UniformRandomProvider
  ) extends GraphStageWithMaterializedValue[
        FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
        Future[ComponentResult[S]]
      ]:

    private val in      = Inlet[TimedElement[Timed[In]]]("ScheduleReleaseTransducer.in")
    private val fwdOut   = Outlet[TimedElement[Timed[Out]]]("ScheduleReleaseTransducer.out")
    private val consOut = Outlet[TimedElement[Timed[Cons]]]("ScheduleReleaseTransducer.cons")
    override val shape: FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]] =
      FanOutShape2(in, fwdOut, consOut)

    // A buffered output routed to a plane (Left = forward output, Right = consumption), keyed by time.
    private final case class Pending(eventTime: Long, intraTick: Double, seq: Long, item: Either[Timed[Out], Timed[Cons]])
    // PriorityQueue is a max-heap; reverse so `head`/`dequeue` yield the earliest output.
    private given Ordering[Pending] =
      Ordering.by[Pending, (Long, Double, Long)](p => (p.eventTime, p.intraTick, p.seq)).reverse

    override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes
    ): (GraphStageLogic, Future[ComponentResult[S]]) =
      val resultPromise = Promise[ComponentResult[S]]()

      val logic = new GraphStageLogic(shape):
        private var state: S   = sampler.initialState
        private var seq:   Long = 0L
        private val pending    = mutable.PriorityQueue.empty[Pending]

        private def stamp(inEventTime: SimTime, inIntraTick: Double, delay: Delay): (Long, Double) =
          val raw   = inIntraTick + delay
          val floor = math.floor(raw)
          (inEventTime.ticks + floor.toLong, raw - floor)

        private def runSampler(timedIn: Timed[In]): Unit =
          val Emission(ns, out, conss) = sampler.sample(timedIn.event, state, rng)
          state = ns
          val (rt, ri) = stamp(timedIn.eventTime, timedIn.intraTick, out.delay)
          pending.enqueue(Pending(rt, ri, seq, Left(Timed(out.event, SimTime.of(rt), ri, timedIn.usecase)))); seq += 1
          conss.foreach { cs =>
            val (ct, ci) = stamp(timedIn.eventTime, timedIn.intraTick, cs.delay)
            pending.enqueue(Pending(ct, ci, seq, Right(Timed(cs.event, SimTime.of(ct), ci, timedIn.usecase)))); seq += 1
          }

        /** Drain outputs with `eventTime < t`, split per plane preserving time order. */
        private def drainBelow(t: Long): (List[TimedElement[Timed[Out]]], List[TimedElement[Timed[Cons]]]) =
          val fwdBuf  = mutable.ListBuffer.empty[TimedElement[Timed[Out]]]
          val consBuf = mutable.ListBuffer.empty[TimedElement[Timed[Cons]]]
          while pending.nonEmpty && pending.head.eventTime < t do
            pending.dequeue().item match
              case Left(o)  => fwdBuf += o
              case Right(c) => consBuf += c
          (fwdBuf.toList, consBuf.toList)

        private def summarizeResidue(): ResidueSummary =
          var o = 0L
          var c = 0L
          pending.foreach(p => p.item match { case Left(_) => o += 1; case Right(_) => c += 1 })
          ResidueSummary(o, c)

        /** Pull the input only when both outlets can accept output (conservative fan-out demand). */
        private def maybePull(): Unit =
          if !isClosed(in) && isAvailable(fwdOut) && isAvailable(consOut) && !hasBeenPulled(in) then pull(in)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            grab(in) match
              case c: TimedControlEvent =>
                c match
                  case TimedControlEvent.Tick(t) =>
                    val (os, cs) = drainBelow(t.ticks)     // close the just-ended window
                    val te = sampler.onTick(t.ticks, state) // advance state for the opening tick, with any boundary facts
                    state = te.newState
                    // Stamp each boundary fact at (t, 0) + its delay and buffer it — eventTime == t, so it is not
                    // in this drain; it is released at the next boundary, ordered first in tick t's own window.
                    te.consumption.foreach { csch =>
                      val (ct, ci) = stamp(SimTime.of(t.ticks), 0.0, csch.delay)
                      pending.enqueue(Pending(ct, ci, seq, Right(Timed(csch.event, SimTime.of(ct), ci, TickBoundaryUsecase))))
                      seq += 1
                    }
                    emitMultiple(fwdOut, os :+ c)
                    emitMultiple(consOut, cs :+ c)
                  case TimedControlEvent.EndOfTime =>
                    resultPromise.trySuccess(ComponentResult(state, summarizeResidue()))
                    var remaining = 2
                    val done: () => Unit = () =>
                      remaining -= 1
                      if remaining == 0 then completeStage()
                    emit(fwdOut, c, done)
                    emit(consOut, c, done)
              case other =>
                runSampler(other.asInstanceOf[Timed[In]])
                maybePull()

          override def onUpstreamFinish(): Unit =
            // Defensive: framing always delivers EndOfTime first (which completes the promise and
            // the stage). Only act if the stream ended without one.
            if !resultPromise.isCompleted then
              resultPromise.trySuccess(ComponentResult(state, summarizeResidue()))
              completeStage()
        )

        setHandler(fwdOut, new OutHandler { override def onPull(): Unit = maybePull() })
        setHandler(consOut, new OutHandler { override def onPull(): Unit = maybePull() })

        override def preStart(): Unit = maybePull()

      (logic, resultPromise.future)

  // --- the loopback stage: two inlets (in, fbIn), three outlets (fwd, cons, tap) ---

  private final class LoopbackStage[S, In, Fb, Out, Cons, Tap](
    sampler: LoopbackComponentSampler[S, In, Fb, Out, Cons, Tap],
    rng:     UniformRandomProvider
  ) extends GraphStageWithMaterializedValue[LoopbackShape[In, Fb, Out, Cons, Tap], Future[ComponentResult[S]]]:

    private val in      = Inlet[TimedElement[Timed[In]]]("Loopback.in")
    private val fbIn    = Inlet[TimedElement[Timed[Fb]]]("Loopback.fbIn")
    private val fwdOut  = Outlet[TimedElement[Timed[Out]]]("Loopback.fwd")
    private val consOut = Outlet[TimedElement[Timed[Cons]]]("Loopback.cons")
    private val tapOut  = Outlet[TimedElement[Timed[Tap]]]("Loopback.tap")
    override val shape: LoopbackShape[In, Fb, Out, Cons, Tap] = new LoopbackShape(in, fbIn, fwdOut, consOut, tapOut)

    private final case class P[X](eventTime: Long, intraTick: Double, seq: Long, item: Timed[X])
    private def ord[X]: Ordering[P[X]] = Ordering.by[P[X], (Long, Double, Long)](p => (p.eventTime, p.intraTick, p.seq)).reverse

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[ComponentResult[S]]) =
      val resultPromise = Promise[ComponentResult[S]]()
      val logic = new GraphStageLogic(shape):
        private var state:  S    = sampler.initialState
        private var seq:    Long  = 0L
        private val fwdQ  = mutable.PriorityQueue.empty[P[Out]](ord)
        private val consQ = mutable.PriorityQueue.empty[P[Cons]](ord)
        private val tapQ  = mutable.PriorityQueue.empty[P[Tap]](ord)
        private var inTick: Option[Long] = None // a Tick held on `in`, awaiting fbIn to reach it
        private var fbTick: Option[Long] = None

        private def stamp(inEventTime: SimTime, inIntraTick: Double, delay: Delay): (Long, Double) =
          val raw = inIntraTick + delay; val fl = math.floor(raw)
          (inEventTime.ticks + fl.toLong, raw - fl)

        private def drain[X](q: mutable.PriorityQueue[P[X]], t: Long): List[TimedElement[Timed[X]]] =
          val buf = mutable.ListBuffer.empty[TimedElement[Timed[X]]]
          while q.nonEmpty && q.head.eventTime < t do buf += q.dequeue().item
          buf.toList

        private def runSample(ti: Timed[In]): Unit =
          val e = sampler.sample(ti.event, state, rng)
          state = e.newState
          val (rt, ri) = stamp(ti.eventTime, ti.intraTick, e.output.delay)
          fwdQ.enqueue(P(rt, ri, seq, Timed(e.output.event, SimTime.of(rt), ri, ti.usecase))); seq += 1
          e.consumption.foreach { c => val (ct, ci) = stamp(ti.eventTime, ti.intraTick, c.delay); consQ.enqueue(P(ct, ci, seq, Timed(c.event, SimTime.of(ct), ci, ti.usecase))); seq += 1 }
          e.taps.foreach       { p => val (pt, pi) = stamp(ti.eventTime, ti.intraTick, p.delay); tapQ.enqueue(P(pt, pi, seq, Timed(p.event, SimTime.of(pt), pi, ti.usecase))); seq += 1 }

        private def runFeedback(tf: Timed[Fb]): Unit =
          val te = sampler.onFeedback(tf.event, state, rng)
          state = te.newState
          te.consumption.foreach { c => val (ct, ci) = stamp(tf.eventTime, tf.intraTick, c.delay); consQ.enqueue(P(ct, ci, seq, Timed(c.event, SimTime.of(ct), ci, tf.usecase))); seq += 1 }

        /** Pull whichever inlet is idle and not currently holding a Tick (a held Tick waits for its match). */
        private def pump(): Unit =
          if !hasBeenPulled(in)   && inTick.isEmpty && !isClosed(in)   then pull(in)
          if !hasBeenPulled(fbIn) && fbTick.isEmpty && !isClosed(fbIn) then pull(fbIn)

        /** Close window `t-1` once both inlets have reached `Tick(t)`: run `onTick`, then release the fwd/cons
         *  outputs of the just-closed window and pass `Tick(t)` on both planes. */
        private def tryFire(): Unit =
          (inTick, fbTick) match
            case (Some(t), Some(t2)) if t == t2 =>
              val fwd = drain(fwdQ, t)
              val te  = sampler.onTick(t, state); state = te.newState
              te.consumption.foreach { c => val (ct, ci) = stamp(SimTime.of(t), 0.0, c.delay); consQ.enqueue(P(ct, ci, seq, Timed(c.event, SimTime.of(ct), ci, TickBoundaryUsecase))); seq += 1 }
              val cons = drain(consQ, t)
              val tick = TimedControlEvent.Tick(SimTime.of(t))
              inTick = None; fbTick = None
              emitMultiple(fwdOut,  fwd  :+ tick)
              emitMultiple(consOut, cons :+ tick)
              pump()
            case _ => ()

        private def finish(): Unit =
          if !resultPromise.isCompleted then
            resultPromise.trySuccess(ComponentResult(state, ResidueSummary(fwdQ.size.toLong, consQ.size.toLong, tapQ.size.toLong)))
            val eot = TimedControlEvent.EndOfTime
            var remaining = 3
            val done: () => Unit = () => { remaining -= 1; if remaining == 0 then completeStage() }
            emit(fwdOut, eot, done); emit(consOut, eot, done); emit(tapOut, eot, done)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            grab(in) match
              case t: TimedControlEvent.Tick =>
                val tk = t.eventTime.ticks
                emitMultiple(tapOut, drain(tapQ, tk) :+ t) // eager: release the tap window and forward the tick
                inTick = Some(tk); tryFire()
              case TimedControlEvent.EndOfTime => finish()
              case other                       => runSample(other.asInstanceOf[Timed[In]]); pump()
          override def onUpstreamFinish(): Unit = finish()
        )

        setHandler(fbIn, new InHandler:
          override def onPush(): Unit =
            grab(fbIn) match
              case t: TimedControlEvent.Tick   => fbTick = Some(t.eventTime.ticks); tryFire()
              case TimedControlEvent.EndOfTime => () // the primary inlet drives completion
              case other                       => runFeedback(other.asInstanceOf[Timed[Fb]]); pump()
          override def onUpstreamFinish(): Unit = () // ignore: `in` drives completion
        )

        setHandler(fwdOut,  new OutHandler { override def onPull(): Unit = pump() })
        setHandler(consOut, new OutHandler { override def onPull(): Unit = pump() })
        setHandler(tapOut,  new OutHandler { override def onPull(): Unit = pump() })

        override def preStart(): Unit = pump()

      (logic, resultPromise.future)
