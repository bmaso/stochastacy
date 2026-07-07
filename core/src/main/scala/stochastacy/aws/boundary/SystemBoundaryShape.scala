package stochastacy.aws.boundary

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.sim.{TimedElement, TimedEvent}

/**
 * Shape exposed by `SystemBoundaryStage.componentOf`.  A bidirectional boundary
 * (network link, cross-AZ / cross-region hop, VPC endpoint, ...): two flow
 * directions plus a dedicated metering outlet.
 *
 *  - `requestIn`  / `requestOut`  — the request direction (e.g. client → service)
 *  - `responseIn` / `responseOut` — the response direction (service → client)
 *  - `consumptionOut`             — metering / consumption events for whatever
 *                                   crosses the boundary
 *
 * Generic over three `TimedEvent` bounds: `Req` and `Resp` for the two flow
 * directions, `Cons` for the consumption outlet's element type.  Five ports
 * total (two inlets, three outlets); a custom `Shape` because 2-in / 3-out is
 * not a standard Pekko fan shape (cf. `DynamoDbGlobalTableShape`).
 */
final class SystemBoundaryShape[Req <: TimedEvent, Resp <: TimedEvent, Cons <: TimedEvent](
                                                                                            val requestIn:      Inlet[TimedElement[Req]],
                                                                                            val requestOut:     Outlet[TimedElement[Req]],
                                                                                            val responseIn:     Inlet[TimedElement[Resp]],
                                                                                            val responseOut:    Outlet[TimedElement[Resp]],
                                                                                            val consumptionOut: Outlet[TimedElement[Cons]]
                                                                                          ) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    Vector(requestIn, responseIn)

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    Vector(requestOut, responseOut, consumptionOut)

  override def deepCopy(): SystemBoundaryShape[Req, Resp, Cons] =
    new SystemBoundaryShape(
      requestIn.carbonCopy(),
      requestOut.carbonCopy(),
      responseIn.carbonCopy(),
      responseOut.carbonCopy(),
      consumptionOut.carbonCopy()
    )
