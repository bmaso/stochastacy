package stochastacy.core.component.circuit

import scala.collection.mutable

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.core.component.LoopbackComponentSampler

/**
 * The builder a [[Circuit]] is described with, inside `Circuit.build { b => … }`. Declare nodes with [[node]], then wire
 * them — every call is type-checked against the nodes' sampler types:
 *
 *  - [[connect]] / [[connectVia]] — a node's `out` or `taps` plane into a node's `in` or `fb` port;
 *  - [[input]] / [[inputVia]] — the circuit's external input into a node port;
 *  - [[output]] / [[outputVia]] — a node's `out` or `taps` plane out of the circuit's forward outlet;
 *  - [[consumption]] / [[consumptionVia]] — a node's consumption facts out of the circuit's consumption outlet, or
 *    [[ignore]] them explicitly;
 *  - [[wiretap]] — a copy of a node's `out` or `taps` events onto the consumption outlet.
 *
 * The `…Via` forms take a partial function: it converts each item, and items it isn't defined at are filtered out
 * (e.g. `{ case Admit(r) => r }`). Routes add no delay — an emission's own `Scheduled` delay is its latency. An emission
 * is offered to every route on its plane, in declaration order.
 *
 * Mistakes that can't be caught by types fail with an `IllegalArgumentException`: at the call (a port or plane from a
 * different builder, ignoring a routed consumption plane or routing an ignored one) or when `Circuit.build` completes
 * (see [[Circuit.build]]).
 */
final class CircuitBuilder[In, Out, Cons] private[circuit] ():

  private enum ConsRouting:
    case Untouched, Routed, Ignored

  private val specs     = mutable.ArrayBuffer.empty[NodeSpec]
  private val mustRoute = mutable.ArrayBuffer.empty[Boolean]
  private val consState = mutable.ArrayBuffer.empty[ConsRouting]
  private val inbound   = mutable.ArrayBuffer.empty[Int]
  private val routes    = mutable.LinkedHashMap.empty[RouteSource, Vector[Route]]
  private var maxEvents = CircuitPlan.DefaultMaxEventsPerWindow
  private var built     = false

  /** Declare a node hosting `sampler`. `rngSeed` pins the node's RNG to a fixed seed; otherwise the node's seed is
   *  derived from the RNG given to [[Circuit.componentOf]], in node-declaration order. */
  def node[S, NIn, NFb, NOut, NCons, NTap](
    name:    String,
    sampler: LoopbackComponentSampler[S, NIn, NFb, NOut, NCons, NTap],
    rngSeed: Option[Long] = None
  )(using demand: ConsumptionDemand[NCons]): CircuitNode[S, NIn, NFb, NOut, NCons, NTap] =
    open()
    require(name.trim.nonEmpty, "a circuit node needs a non-blank name")
    val index = specs.size
    specs += NodeSpec(name, rngSeed, (rng: UniformRandomProvider) => ErasedNode.of(name, sampler, rng))
    mustRoute += demand.mustRoute
    consState += ConsRouting.Untouched
    inbound += 0
    new CircuitNode[S, NIn, NFb, NOut, NCons, NTap](name, index, this)

  /** Wire a node's `out` or `taps` plane into a node port, unchanged. */
  def connect[A](from: EmissionPlane[A], to: NodePort[A]): Unit =
    addToPort(sourceOf(from), to, Route.accept)

  /** Wire a node's `out` or `taps` plane into a node port through `pf` (converting; filtering where `pf` is undefined). */
  def connectVia[A, B](from: EmissionPlane[A], to: NodePort[B])(pf: PartialFunction[A, B]): Unit =
    addToPort(sourceOf(from), to, lift(pf))

  /** Deliver the circuit's external input into a node port, unchanged. */
  def input(to: NodePort[In]): Unit =
    addToPort(RouteSource.CircuitInput, to, Route.accept)

  /** Deliver the circuit's external input into a node port through `pf`. */
  def inputVia[B](to: NodePort[B])(pf: PartialFunction[In, B]): Unit =
    addToPort(RouteSource.CircuitInput, to, lift(pf))

  /** Emit a node's `out` or `taps` plane on the circuit's forward outlet, unchanged. */
  def output(from: EmissionPlane[Out]): Unit =
    add(sourceOf(from), Route(RouteTarget.ForwardOutlet))

  /** Emit a node's `out` or `taps` plane on the circuit's forward outlet through `pf`. */
  def outputVia[A](from: EmissionPlane[A])(pf: PartialFunction[A, Out]): Unit =
    add(sourceOf(from), Route(RouteTarget.ForwardOutlet, lift(pf)))

  /** Emit a node's consumption facts on the circuit's consumption outlet, unchanged. */
  def consumption(from: ConsumptionPlane[Cons]): Unit =
    add(consumptionSource(from), Route(RouteTarget.ConsumptionOutlet))

  /** Emit a node's consumption facts on the circuit's consumption outlet through `pf`. */
  def consumptionVia[A](from: ConsumptionPlane[A])(pf: PartialFunction[A, Cons]): Unit =
    add(consumptionSource(from), Route(RouteTarget.ConsumptionOutlet, lift(pf)))

  /** Copy a node's `out` or `taps` events onto the circuit's consumption outlet through `pf`, so internal interactions
   *  stay observable. A wiretap copy does not count as routing the emission: a plane that is *only* wiretapped still
   *  reports its emissions as unrouted in [[CircuitResult.unrouted]]. */
  def wiretap[A](from: EmissionPlane[A])(pf: PartialFunction[A, Cons]): Unit =
    add(sourceOf(from), Route(RouteTarget.ConsumptionOutlet, lift(pf), wiretap = true))

  /** Explicitly drop a node's consumption facts (required for a node with real consumption that isn't routed). */
  def ignore(from: ConsumptionPlane[?]): Unit =
    open()
    val n = owned(from.owner, from.node, from.toString)
    require(consState(n) != ConsRouting.Routed, s"node '${specs(n).name}' consumption is already routed; it cannot also be ignored")
    consState(n) = ConsRouting.Ignored

  /** Cap on dispatches in one tick window (default 10 M); exceeding it fails the stage as a runaway zero-delay cycle. */
  def maxEventsPerWindow(n: Long): Unit =
    open()
    require(n > 0L, s"maxEventsPerWindow must be positive, got $n")
    maxEvents = n

  // --- internals ---

  private def open(): Unit =
    if built then throw new IllegalStateException("a CircuitBuilder cannot be used after Circuit.build has returned")

  private def owned(owner: AnyRef, node: Int, what: String): Int =
    require(owner eq this, s"$what belongs to a different circuit builder")
    node

  private def sourceOf(from: EmissionPlane[?]): RouteSource =
    open()
    RouteSource.NodePlane(owned(from.owner, from.node, from.toString), from.plane)

  private def consumptionSource(from: ConsumptionPlane[?]): RouteSource =
    open()
    val n = owned(from.owner, from.node, from.toString)
    require(consState(n) != ConsRouting.Ignored, s"node '${specs(n).name}' consumption is explicitly ignored; it cannot also be routed")
    consState(n) = ConsRouting.Routed
    RouteSource.NodePlane(n, CircuitPlane.Consumption)

  private def addToPort(source: RouteSource, to: NodePort[?], transform: Any => Option[Any]): Unit =
    open()
    val n = owned(to.owner, to.node, to.toString)
    inbound(n) += 1
    add(source, Route(RouteTarget.NodePort(n, to.port), transform))

  private def add(source: RouteSource, route: Route): Unit =
    routes.update(source, routes.getOrElse(source, Vector.empty) :+ route)

  private def lift[A, B](pf: PartialFunction[A, B]): Any => Option[Any] =
    val lifted = pf.lift
    (a: Any) => lifted(a.asInstanceOf[A])

  /** Validate the finished description and seal the builder. */
  private[circuit] def result(): Circuit[In, Out, Cons] =
    open()
    require(specs.nonEmpty, "a circuit needs at least one node")
    require(routes.contains(RouteSource.CircuitInput), "a circuit needs at least one input route (input / inputVia)")
    val duplicates = specs.groupBy(_.name).collect { case (name, ss) if ss.size > 1 => name }
    require(duplicates.isEmpty, s"circuit node names must be unique; duplicated: ${duplicates.toVector.sorted.mkString(", ")}")
    specs.indices.foreach { n =>
      require(inbound(n) > 0, s"node '${specs(n).name}' has no inbound route and would never be dispatched")
      require(!mustRoute(n) || consState(n) != ConsRouting.Untouched,
        s"node '${specs(n).name}' emits consumption that is neither routed (consumption / consumptionVia) nor explicitly ignored")
    }
    built = true
    new Circuit[In, Out, Cons](specs.toVector, routes.toMap, maxEvents)
