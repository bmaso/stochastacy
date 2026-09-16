package stochastacy.core.component.circuit

/**
 * A node **input port** — somewhere an item can be delivered. Contravariant: a port accepting `A` accepts any subtype
 * of `A`, so a plane of a narrower type can feed it. [[InPort]] dispatches to the node's `sample`, [[FbPort]] to its
 * `onFeedback`. Ports belong to the [[CircuitBuilder]] that created their node.
 */
sealed trait NodePort[-A]:
  private[circuit] def owner: AnyRef
  private[circuit] def node: Int
  private[circuit] def nodeName: String
  private[circuit] def port: Port

/** A node's primary input port (dispatches to `sample`). */
final class InPort[-A] private[circuit] (
  private[circuit] val owner: AnyRef, private[circuit] val node: Int, private[circuit] val nodeName: String
) extends NodePort[A]:
  private[circuit] def port: Port = Port.In
  override def toString: String = s"$nodeName.in"

/** A node's feedback input port (dispatches to `onFeedback`). For a plain `ComponentSampler` its type is
 *  `FbPort[Nothing]`, so nothing can be connected to it. */
final class FbPort[-A] private[circuit] (
  private[circuit] val owner: AnyRef, private[circuit] val node: Int, private[circuit] val nodeName: String
) extends NodePort[A]:
  private[circuit] def port: Port = Port.Fb
  override def toString: String = s"$nodeName.fb"

/**
 * A node **emission plane** that can be wired onward — its forward output ([[OutPlane]]) or its taps ([[TapPlane]]).
 * Covariant: a plane of `A` can feed any port accepting a supertype of `A`. Consumption is deliberately *not* an
 * emission plane: it can only leave the circuit on the consumption outlet (see [[ConsumptionPlane]]).
 */
sealed trait EmissionPlane[+A]:
  private[circuit] def owner: AnyRef
  private[circuit] def node: Int
  private[circuit] def nodeName: String
  private[circuit] def plane: CircuitPlane

/** A node's forward-output plane. */
final class OutPlane[+A] private[circuit] (
  private[circuit] val owner: AnyRef, private[circuit] val node: Int, private[circuit] val nodeName: String
) extends EmissionPlane[A]:
  private[circuit] def plane: CircuitPlane = CircuitPlane.Out
  override def toString: String = s"$nodeName.out"

/** A node's tap (loop-out) plane. */
final class TapPlane[+A] private[circuit] (
  private[circuit] val owner: AnyRef, private[circuit] val node: Int, private[circuit] val nodeName: String
) extends EmissionPlane[A]:
  private[circuit] def plane: CircuitPlane = CircuitPlane.Taps
  override def toString: String = s"$nodeName.taps"

/** A node's consumption plane — routed to the circuit's consumption outlet or explicitly ignored. */
final class ConsumptionPlane[+A] private[circuit] (
  private[circuit] val owner: AnyRef, private[circuit] val node: Int, private[circuit] val nodeName: String
):
  override def toString: String = s"$nodeName.consumption"

/**
 * A typed **handle** to one node of a circuit under construction, returned by [[CircuitBuilder.node]]. Its ports and
 * planes carry the node sampler's own types, so wiring that doesn't type-check doesn't compile. After a run, pass the
 * handle to [[CircuitResult.stateOf]] to read the node's final state.
 */
final class CircuitNode[S, In, Fb, Out, Cons, Tap] private[circuit] (
  val name:                   String,
  private[circuit] val index: Int,
  owner:                      AnyRef
):
  val in:          InPort[In]             = new InPort(owner, index, name)
  val fb:          FbPort[Fb]             = new FbPort(owner, index, name)
  val out:         OutPlane[Out]          = new OutPlane(owner, index, name)
  val taps:        TapPlane[Tap]          = new TapPlane(owner, index, name)
  val consumption: ConsumptionPlane[Cons] = new ConsumptionPlane(owner, index, name)

  override def toString: String = s"CircuitNode($name)"
