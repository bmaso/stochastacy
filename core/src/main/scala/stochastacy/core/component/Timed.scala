package stochastacy.core.component

import stochastacy.sim.{SimTime, TimedEvent}

/** Generic wire envelope for a timeless domain payload.
 *
 *  Components describe *what happened* as plain, timeless payloads (a response, a
 *  consumption fact). The schedule-and-release transducer decides *when it is observed*
 *  — stamping `eventTime`/`intraTick` from the payload's scheduled delay — and wraps the
 *  payload in this envelope so it can travel on a timed-event stream. `usecase` is
 *  propagated from the request that triggered the emission.
 *
 *  This is deliberately the wire form for *outputs*. Inputs (requests) remain self-timed
 *  `TimedEvent`s; whether requests also become `Timed[_]` payloads for cross-component
 *  uniformity is a slice-5 (composition) decision.
 */
final case class Timed[E](
  event:                  E,
  eventTime:              SimTime,
  override val intraTick: Double,
  usecase:                Any
) extends TimedEvent
