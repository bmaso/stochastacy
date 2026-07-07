package stochastacy.aws.boundary

import stochastacy.sim.{SimTime, TimedEvent}

/**
 * The element-type seam for [[SystemBoundaryStage]].  The stage is generic over
 * the two flow-direction payload types; everything it must do to those payloads
 * factors through this protocol, so one stage models any AWS service's boundary
 * given a per-service instance.
 *
 * The interface grows per slice — it carries only what the current slices use:
 *
 *  - Slice 2 (transport latency): `withRequestTiming` / `withResponseTiming`
 *    restamp a delayed element's `eventTime` / `intraTick`.
 *  - Slice 3 will add `measure` (byte / item sizing) and `timeoutResponse`
 *    (synthesize a retryable timeout for a dropped request).
 *
 * The two restamp methods are distinctly named rather than one overloaded
 * `withTiming`, because `Req` and `Resp` erase to the same JVM signature.
 */
trait BoundaryProtocol[Req <: TimedEvent, Resp <: TimedEvent]:

  /** Rebuild `req` with new timing, preserving its concrete type and all other
   *  fields.  Used when transport latency shifts a request into a later tick. */
  def withRequestTiming(req: Req, eventTime: SimTime, intraTick: Double): Req

  /** Rebuild `resp` with new timing, preserving its concrete type and all other
   *  fields.  Used when transport latency shifts a response into a later tick. */
  def withResponseTiming(resp: Resp, eventTime: SimTime, intraTick: Double): Resp
