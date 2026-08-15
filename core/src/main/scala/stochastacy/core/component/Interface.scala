package stochastacy.core.component

import org.apache.commons.rng.UniformRandomProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{FanOutShape2, Graph}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Sink}
import stochastacy.sim.stream.MergeTimedEventGraph
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}

/** The outcome a gate produces for one request: forward it, or reject it with a response of the same
 *  type the downstream would have produced. Covariant so `Admit(req)` and `Reject(resp)` both unify to
 *  `InterfaceOutcome[Req, Resp]`. */
sealed trait InterfaceOutcome[+Req, +Resp]
final case class Admit[+Req](request: Req)     extends InterfaceOutcome[Req, Nothing]
final case class Reject[+Resp](response: Resp) extends InterfaceOutcome[Nothing, Resp]

/** A gate: decides admit-or-reject per request on a request/response edge. It is a [[ComponentSampler]]
 *  whose forward output is an [[InterfaceOutcome]] and whose consumption is fixed to `Nothing` — gates
 *  surface their effects through the response stream (a rejection *is* a response), not a metric plane,
 *  so wrapping stays shape-preserving and the engine acquires no forced observation type.
 *
 *  Latency is the `Scheduled` delay a gate puts on its outcome: on an admit it shifts when the
 *  downstream receives the request; on a reject it shifts when the rejection response emerges. */
trait InterfaceSampler[S, Req, Resp]
    extends ComponentSampler[S, Req, InterfaceOutcome[Req, Resp], Nothing]

/** Wraps a downstream component with a gate, producing a **transparent, shape-preserving** decorator:
 *  the wrapped component presents the same `Req → Resp` interface (and the same materialized value) as
 *  the downstream it wraps, so wrapped components can themselves be wrapped — gates stack. */
object Interface:

  def wrap[S, Req, Resp, Cons, Mat](
    downstream: Graph[
      FanOutShape2[TimedElement[Timed[Req]], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]],
      Mat
    ],
    gate: InterfaceSampler[S, Req, Resp],
    rng:  UniformRandomProvider
  ): Graph[
    FanOutShape2[TimedElement[Timed[Req]], TimedElement[Timed[Resp]], TimedElement[Timed[Cons]]],
    Mat
  ] =
    val gateComponent = ScheduleReleaseTransducer.componentOf(gate, rng)

    // Split the gate's outcome stream into the admitted requests (forwarded to the downstream) and the
    // rejection responses (short-circuited); every control event is carried on both branches so each is
    // an independently well-framed timed-event stream.
    val admitFlow: Flow[TimedElement[Timed[InterfaceOutcome[Req, Resp]]], TimedElement[Timed[Req]], NotUsed] =
      Flow[TimedElement[Timed[InterfaceOutcome[Req, Resp]]]].collect {
        case t: Timed[InterfaceOutcome[Req, Resp]] @unchecked if t.event.isInstanceOf[Admit[?]] =>
          val req = t.event.asInstanceOf[Admit[Req]].request
          (Timed(req, t.eventTime, t.intraTick, t.usecase): TimedElement[Timed[Req]])
        case c: TimedControlEvent => c
      }
    val rejectFlow: Flow[TimedElement[Timed[InterfaceOutcome[Req, Resp]]], TimedElement[Timed[Resp]], NotUsed] =
      Flow[TimedElement[Timed[InterfaceOutcome[Req, Resp]]]].collect {
        case t: Timed[InterfaceOutcome[Req, Resp]] @unchecked if t.event.isInstanceOf[Reject[?]] =>
          val resp = t.event.asInstanceOf[Reject[Resp]].response
          (Timed(resp, t.eventTime, t.intraTick, t.usecase): TimedElement[Timed[Resp]])
        case c: TimedControlEvent => c
      }
    // The tick-aligned rejoin emits the erased `TimedEvent`; recover the concrete response element type.
    val castBackFlow: Flow[TimedEvent, TimedElement[Timed[Resp]], NotUsed] =
      Flow[TimedEvent].collect {
        case t: Timed[Resp] @unchecked => (t: TimedElement[Timed[Resp]])
        case c: TimedControlEvent      => c
      }

    GraphDSL.createGraph(downstream, gateComponent)((dsMat, _gateMat) => dsMat) { implicit b => (ds, g) =>
      import GraphDSL.Implicits.*
      val bcast    = b.add(Broadcast[TimedElement[Timed[InterfaceOutcome[Req, Resp]]]](2))
      val rejoin   = b.add(MergeTimedEventGraph.graphOf())
      val admit    = b.add(admitFlow)
      val reject   = b.add(rejectFlow)
      val castBack = b.add(castBackFlow)

      g.out0 ~> bcast.in
      bcast.out(0) ~> admit.in;  admit.out ~> ds.in            // admitted → downstream
      bcast.out(1) ~> reject.in; reject.out ~> rejoin.in1      // rejected → rejoin
      ds.out0 ~> rejoin.in0                                    // downstream responses → rejoin
      g.out1 ~> b.add(Sink.ignore)                             // gate consumption is `Nothing`
      rejoin.out ~> castBack.in

      new FanOutShape2(g.in, castBack.out, ds.out1)            // shape + Mat preserved from downstream
    }
