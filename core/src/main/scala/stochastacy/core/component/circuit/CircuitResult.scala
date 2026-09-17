package stochastacy.core.component.circuit

/** The output plane of a circuit node an emission travels on: its forward output, its loop-out taps, or its
 *  consumption facts. */
enum CircuitPlane:
  case Out, Taps, Consumption

/** What was still pending when a circuit reached `EndOfTime` — scheduled past the simulation horizon and therefore
 *  never dispatched or emitted: node dispatches left on the calendar, and outlet items not yet released. */
final case class CircuitResidue(calendarEvents: Long, forwardOutputs: Long, consumptions: Long):
  def total: Long = calendarEvents + forwardOutputs + consumptions

/** Emissions on one node plane that no route accepted (no route on the plane, or every route's transform declined)
 *  — dropped, and counted here. Filtering is a legitimate use of routes, so a non-zero count is a diagnostic, not an
 *  error. A wiretap copy does not count as routing an emission. */
final case class UnroutedCount(node: Int, nodeName: String, plane: CircuitPlane, count: Long)

/**
 * The materialized result of running a circuit to `EndOfTime`: every node's final state (in node declaration
 * order), the post-horizon residue, and routing diagnostics — per-plane unrouted emission counts (non-zero entries
 * only) and the number of external inputs no route accepted.
 */
final case class CircuitResult(
  nodeStates:     Vector[Any],
  residue:        CircuitResidue,
  unrouted:       Vector[UnroutedCount],
  unroutedInputs: Long
):
  /** The final state of `node`, typed by its handle. The handle must come from the circuit that produced this result
   *  (states are looked up by declaration index). */
  def stateOf[S](node: CircuitNode[S, ?, ?, ?, ?, ?]): S = nodeStates(node.index).asInstanceOf[S]
