package stochastacy.core.component.circuit

/** A node's input port: `In` dispatches to the sampler's `sample`, `Fb` to its `onFeedback`. */
private[stochastacy] enum Port:
  case In, Fb

/** Where a routed item comes from: the circuit's external input, or one output plane of one node. */
private[stochastacy] enum RouteSource:
  case CircuitInput
  case NodePlane(node: Int, plane: CircuitPlane)

/** Where a routed item goes: into a node's port (via the calendar), or out of the circuit on one of its outlets. */
private[stochastacy] enum RouteTarget:
  case NodePort(node: Int, port: Port)
  case ForwardOutlet
  case ConsumptionOutlet

/** One edge of the wiring. `transform` maps the source item to the target's item — returning `None` filters it out
 *  (e.g. `{ case Admit(r) => r }` lifted). Routes add no delay: the emitter's `Scheduled` delay is the latency. A
 *  `wiretap` route copies a node's `Out` / `Taps` emissions onto the consumption outlet; its copies do not count as
 *  routing the emission for the unrouted diagnostics. */
private[stochastacy] final case class Route(
  target:    RouteTarget,
  transform: Any => Option[Any] = Route.accept,
  wiretap:   Boolean            = false
)

private[stochastacy] object Route:
  val accept: Any => Option[Any] = Some(_)

/**
 * The **erased wiring** of a circuit: its nodes (in declaration order — node `i` is `nodes(i)`) and its routes,
 * keyed by source. An item emitted on a source is offered to that source's routes in declaration order; every
 * route whose transform accepts it delivers a copy (fan-out). This is internal plumbing: the typed [[CircuitBuilder]]
 * produces plans, and only structural well-formedness is checked here.
 *
 * Permitted route shapes: the circuit input goes only into node ports; a node's `Out` / `Taps` plane goes into node
 * ports or the forward outlet, or — as a wiretap — onto the consumption outlet; a node's `Consumption` plane goes only
 * to the consumption outlet (feedback driven by consumption is out of scope).
 *
 * `maxEventsPerWindow` caps the dispatches in one tick window — a zero-delay cycle that never terminates fails the
 * stage instead of hanging.
 */
private[stochastacy] final case class CircuitPlan(
  nodes:              Vector[ErasedNode],
  routes:             Map[RouteSource, Vector[Route]],
  maxEventsPerWindow: Long = CircuitPlan.DefaultMaxEventsPerWindow
):
  require(nodes.nonEmpty, "a circuit needs at least one node")
  require(maxEventsPerWindow > 0L, s"maxEventsPerWindow must be positive, got $maxEventsPerWindow")

  routes.foreach { (source, rs) =>
    source match
      case RouteSource.NodePlane(n, _) =>
        require(nodes.indices.contains(n), s"route source node #$n is out of range (${nodes.size} nodes)")
      case RouteSource.CircuitInput => ()
    rs.foreach { r =>
      r.target match
        case RouteTarget.NodePort(n, _) =>
          require(nodes.indices.contains(n), s"route target node #$n is out of range (${nodes.size} nodes)")
        case _ => ()
      val permitted = (source, r.target) match
        case (RouteSource.CircuitInput, RouteTarget.NodePort(_, _))                              => !r.wiretap
        case (RouteSource.CircuitInput, _)                                                       => false
        case (RouteSource.NodePlane(_, CircuitPlane.Consumption), RouteTarget.ConsumptionOutlet) => !r.wiretap
        case (RouteSource.NodePlane(_, CircuitPlane.Consumption), _)                             => false
        case (RouteSource.NodePlane(_, _), RouteTarget.ConsumptionOutlet)                        => r.wiretap
        case (RouteSource.NodePlane(_, _), _)                                                    => !r.wiretap
      require(permitted, s"route $source → ${r.target}${if r.wiretap then " (wiretap)" else ""} is not a permitted circuit route")
    }
  }

private[stochastacy] object CircuitPlan:
  val DefaultMaxEventsPerWindow: Long = 10_000_000L
