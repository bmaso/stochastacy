package stochastacy.core.component

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import stochastacy.sim.*

/** The schedule-and-release transducer — the generic machinery that turns a
 *  [[RequestResponseSampler]] into a running component.
 *
 *  It consumes a framed `TimedElement[Req]` stream and produces two framed output streams —
 *  responses and consumption facts — each carrying every control event. For each request it
 *  runs the sampler, advances state, stamps each scheduled output's absolute
 *  `(eventTime, intraTick)` from its delay, and buffers it. Buffered outputs are **released in
 *  time order at tick boundaries**: on `Tick(t)` everything with `eventTime < t` is emitted
 *  (that is, the just-closed window's outputs), then `Tick(t)` is passed through. On
 *  `EndOfTime` all remaining pending outputs are flushed, then `EndOfTime` is passed through.
 *
 *  Timing model (mirrors the intra-tick `rawOffset` rule): for a request at
 *  `(reqEventTime, reqIntraTick)` and an output `delay` (fractional ticks),
 *  `rawOffset = reqIntraTick + delay`, `eventTime = reqEventTime + floor(rawOffset)`,
 *  `intraTick = rawOffset - floor(rawOffset)`.
 *
 *  Slice-1 horizon policy: `EndOfTime` flushes *all* remaining pending outputs onto the
 *  streams (nothing is dropped). Routing post-horizon residue and final state to a
 *  materialized `TrialResult` is a slice-3 concern; when that lands, this becomes a
 *  `GraphStageWithMaterializedValue`. The scheduling logic here carries over unchanged. */
object ScheduleReleaseTransducer:

  def componentOf[S, Req <: TimedEvent, Resp, Cons](
    sampler: RequestResponseSampler[S, Req, Resp, Cons],
    rng:     UniformRandomProvider
  ): Graph[FanOutShape2[TimedElement[Req], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]], NotUsed] =

    // Internal plane-tagged element of the mixed stream. `Timed[Resp]` and `Timed[Cons]` are
    // erasure-identical, so we tag the plane explicitly rather than filter by type downstream.
    sealed trait Mix
    final case class MResp(t: Timed[Resp])       extends Mix
    final case class MCons(t: Timed[Cons])       extends Mix
    final case class MControl(c: TimedControlEvent) extends Mix

    final case class Pending(eventTime: Long, intraTick: Double, seq: Long, mix: Mix)
    // PriorityQueue is a max-heap; reverse so `head`/`dequeue` yield the earliest output.
    given Ordering[Pending] =
      Ordering.by[Pending, (Long, Double, Long)](p => (p.eventTime, p.intraTick, p.seq)).reverse

    val mixedFlow: Flow[TimedElement[Req], Mix, NotUsed] =
      Flow[TimedElement[Req]].statefulMapConcat[Mix] { () =>
        var state: S     = sampler.initialState
        var seq:   Long  = 0L
        val pending      = mutable.PriorityQueue.empty[Pending]

        def stamp(reqEventTime: SimTime, reqIntraTick: Double, delay: Delay): (Long, Double) =
          val raw   = reqIntraTick + delay
          val floor = math.floor(raw)
          (reqEventTime.ticks + floor.toLong, raw - floor)

        def push(mix: Mix, eventTime: Long, intraTick: Double): Unit =
          pending.enqueue(Pending(eventTime, intraTick, seq, mix))
          seq += 1

        def drainBelow(t: Long): List[Mix] =
          val out = mutable.ListBuffer.empty[Mix]
          while pending.nonEmpty && pending.head.eventTime < t do out += pending.dequeue().mix
          out.toList

        def drainAll(): List[Mix] =
          val out = mutable.ListBuffer.empty[Mix]
          while pending.nonEmpty do out += pending.dequeue().mix
          out.toList

        element =>
          element match
            case c: TimedControlEvent =>
              c match
                case TimedControlEvent.Tick(t)   => drainBelow(t.ticks) :+ MControl(c)
                case TimedControlEvent.EndOfTime => drainAll()          :+ MControl(c)
            case other =>
              val req                       = other.asInstanceOf[Req]
              val Emission(ns, resp, conss) = sampler.sample(req, state, rng)
              state = ns
              val (rt, ri) = stamp(req.eventTime, req.intraTick, resp.delay)
              push(MResp(Timed(resp.event, SimTime.of(rt), ri, req.usecase)), rt, ri)
              conss.foreach { cs =>
                val (ct, ci) = stamp(req.eventTime, req.intraTick, cs.delay)
                push(MCons(Timed(cs.event, SimTime.of(ct), ci, req.usecase)), ct, ci)
              }
              Nil
      }

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val mixed = b.add(mixedFlow)
      val bcast = b.add(Broadcast[Mix](2))
      val respOut = b.add(Flow[Mix].collect[TimedElement[Timed[Resp]]] {
        case MResp(t)    => t
        case MControl(c) => c
      })
      val consOut = b.add(Flow[Mix].collect[TimedElement[Timed[Cons]]] {
        case MCons(t)    => t
        case MControl(c) => c
      })

      mixed.out ~> bcast.in
      bcast.out(0) ~> respOut.in
      bcast.out(1) ~> consOut.in

      new FanOutShape2(mixed.in, respOut.out, consOut.out)
    }
