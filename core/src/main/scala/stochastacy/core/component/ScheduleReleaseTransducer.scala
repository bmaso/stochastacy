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
                    val (os, cs) = drainBelow(t.ticks)
                    state = sampler.onTick(t.ticks, state) // advance state for the opening tick
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
