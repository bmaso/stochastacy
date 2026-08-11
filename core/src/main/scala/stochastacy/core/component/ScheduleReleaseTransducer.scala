package stochastacy.core.component

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.stream.{Attributes, FanOutShape2, Graph, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import stochastacy.sim.*

/** The schedule-and-release transducer — the generic machinery that turns a
 *  [[RequestResponseSampler]] into a running component.
 *
 *  It consumes a framed `TimedElement[Req]` stream and produces two framed output streams —
 *  responses and consumption facts — each carrying every control event. For each request it runs
 *  the sampler, advances state, stamps each scheduled output's absolute `(eventTime, intraTick)`
 *  from its delay, and buffers it. Buffered outputs are **released in time order at tick
 *  boundaries**: on `Tick(t)` everything with `eventTime < t` (the just-closed window) is emitted,
 *  then `Tick(t)` is passed through.
 *
 *  Its **materialized value** is a `Future[ComponentResult[S]]`, completed at `EndOfTime` with the
 *  final state and a summary of any still-pending (post-horizon) outputs. Post-horizon residue is
 *  summarized, not emitted — the streams end cleanly at `EndOfTime`.
 *
 *  Timing model (mirrors the intra-tick `rawOffset` rule): for a request at
 *  `(reqEventTime, reqIntraTick)` and an output `delay` (fractional ticks),
 *  `rawOffset = reqIntraTick + delay`, `eventTime = reqEventTime + floor(rawOffset)`,
 *  `intraTick = rawOffset - floor(rawOffset)`. */
object ScheduleReleaseTransducer:

  def componentOf[S, Req <: TimedEvent, Resp, Cons](
    sampler: RequestResponseSampler[S, Req, Resp, Cons],
    rng:     UniformRandomProvider
  ): Graph[
    FanOutShape2[TimedElement[Req], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]],
    Future[ComponentResult[S]]
  ] =
    new Stage(sampler, rng)

  private final class Stage[S, Req <: TimedEvent, Resp, Cons](
    sampler: RequestResponseSampler[S, Req, Resp, Cons],
    rng:     UniformRandomProvider
  ) extends GraphStageWithMaterializedValue[
        FanOutShape2[TimedElement[Req], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]],
        Future[ComponentResult[S]]
      ]:

    private val in      = Inlet[TimedElement[Req]]("ScheduleReleaseTransducer.in")
    private val respOut = Outlet[TimedElement[Timed[Resp]]]("ScheduleReleaseTransducer.resp")
    private val consOut = Outlet[TimedElement[Timed[Cons]]]("ScheduleReleaseTransducer.cons")
    override val shape: FanOutShape2[TimedElement[Req], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]] =
      FanOutShape2(in, respOut, consOut)

    // A buffered output routed to a plane (Left = response, Right = consumption), keyed by time.
    private final case class Pending(eventTime: Long, intraTick: Double, seq: Long, item: Either[Timed[Resp], Timed[Cons]])
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

        private def stamp(reqEventTime: SimTime, reqIntraTick: Double, delay: Delay): (Long, Double) =
          val raw   = reqIntraTick + delay
          val floor = math.floor(raw)
          (reqEventTime.ticks + floor.toLong, raw - floor)

        private def runSampler(req: Req): Unit =
          val Emission(ns, resp, conss) = sampler.sample(req, state, rng)
          state = ns
          val (rt, ri) = stamp(req.eventTime, req.intraTick, resp.delay)
          pending.enqueue(Pending(rt, ri, seq, Left(Timed(resp.event, SimTime.of(rt), ri, req.usecase)))); seq += 1
          conss.foreach { cs =>
            val (ct, ci) = stamp(req.eventTime, req.intraTick, cs.delay)
            pending.enqueue(Pending(ct, ci, seq, Right(Timed(cs.event, SimTime.of(ct), ci, req.usecase)))); seq += 1
          }

        /** Drain outputs with `eventTime < t`, split per plane preserving time order. */
        private def drainBelow(t: Long): (List[TimedElement[Timed[Resp]]], List[TimedElement[Timed[Cons]]]) =
          val respBuf = mutable.ListBuffer.empty[TimedElement[Timed[Resp]]]
          val consBuf = mutable.ListBuffer.empty[TimedElement[Timed[Cons]]]
          while pending.nonEmpty && pending.head.eventTime < t do
            pending.dequeue().item match
              case Left(r)  => respBuf += r
              case Right(c) => consBuf += c
          (respBuf.toList, consBuf.toList)

        private def summarizeResidue(): ResidueSummary =
          var r = 0L
          var c = 0L
          pending.foreach(p => p.item match { case Left(_) => r += 1; case Right(_) => c += 1 })
          ResidueSummary(r, c)

        /** Pull the input only when both outlets can accept output (conservative fan-out demand). */
        private def maybePull(): Unit =
          if !isClosed(in) && isAvailable(respOut) && isAvailable(consOut) && !hasBeenPulled(in) then pull(in)

        setHandler(in, new InHandler:
          override def onPush(): Unit =
            grab(in) match
              case c: TimedControlEvent =>
                c match
                  case TimedControlEvent.Tick(t) =>
                    val (rs, cs) = drainBelow(t.ticks)
                    emitMultiple(respOut, rs :+ c)
                    emitMultiple(consOut, cs :+ c)
                  case TimedControlEvent.EndOfTime =>
                    resultPromise.trySuccess(ComponentResult(state, summarizeResidue()))
                    var remaining = 2
                    val done: () => Unit = () =>
                      remaining -= 1
                      if remaining == 0 then completeStage()
                    emit(respOut, c, done)
                    emit(consOut, c, done)
              case other =>
                runSampler(other.asInstanceOf[Req])
                maybePull()

          override def onUpstreamFinish(): Unit =
            // Defensive: framing always delivers EndOfTime first (which completes the promise and
            // the stage). Only act if the stream ended without one.
            if !resultPromise.isCompleted then
              resultPromise.trySuccess(ComponentResult(state, summarizeResidue()))
              completeStage()
        )

        setHandler(respOut, new OutHandler { override def onPull(): Unit = maybePull() })
        setHandler(consOut, new OutHandler { override def onPull(): Unit = maybePull() })

        override def preStart(): Unit = maybePull()

      (logic, resultPromise.future)
