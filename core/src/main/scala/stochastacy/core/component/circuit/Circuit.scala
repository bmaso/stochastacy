package stochastacy.core.component.circuit

import scala.concurrent.Future
import scala.util.NotGiven

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.stream.{FanOutShape2, Graph}

import stochastacy.core.component.Timed
import stochastacy.sim.TimedElement

/** One declared node: its name, optional pinned RNG seed, and a factory producing its erased adapter for a given RNG. */
private[circuit] final case class NodeSpec(name: String, rngSeed: Option[Long], mk: UniformRandomProvider => ErasedNode)

/**
 * A **circuit** blueprint: sampler nodes plus a wiring among them — cycles allowed — that runs as a single component.
 * Inside, every event is dispatched in conceptual-time order from an internal calendar, so a feedback loop closes
 * exactly within a tick; outside, [[Circuit.componentOf]] presents an ordinary `FanOutShape2` component (timed `In` →
 * forward `Out` + consumption `Cons`), usable anywhere a component is — `Interface.wrap`, `TrialRunner`, a custom graph.
 *
 * A blueprint is immutable and reusable: each `componentOf` call creates fresh node RNGs, so one blueprint serves every
 * trial. Samplers are shared across materializations, as with `ScheduleReleaseTransducer` — they keep no state.
 *
 * {{{
 * val circuit = Circuit.build[Session, Nothing, Fact] { b =>
 *   val client = b.node("client", new ClientNode(cfg))   // loopback: in Session, fb PageResponse, out PageRequest
 *   val server = b.node("server", new ServerNode(cfg))   // plain:    in PageRequest, out PageResponse
 *   b.input(client.in)
 *   b.connect(client.out, server.in)
 *   b.connect(server.out, client.fb)
 *   b.consumptionVia(client.consumption)(Fact.Client(_))
 *   b.consumptionVia(server.consumption)(Fact.Server(_))
 * }
 * }}}
 */
final class Circuit[In, Out, Cons] private[circuit] (
  private[circuit] val nodeSpecs:          Vector[NodeSpec],
  private[circuit] val routes:             Map[RouteSource, Vector[Route]],
  private[circuit] val maxEventsPerWindow: Long
):
  /** The node names, in declaration order. */
  def nodeNames: Vector[String] = nodeSpecs.map(_.name)

object Circuit:

  /**
   * Describe a circuit. Runs `body` against a fresh [[CircuitBuilder]], then validates the result — throwing an
   * `IllegalArgumentException` if the circuit has no nodes or no input route, if node names repeat, if a node has no
   * inbound route (it would never be dispatched), or if a node with real (non-`Nothing`) consumption neither routes nor
   * explicitly ignores it. A circuit with no forward-output route is valid (it reports through consumption only).
   */
  def build[In, Out, Cons](body: CircuitBuilder[In, Out, Cons] => Unit): Circuit[In, Out, Cons] =
    val b = new CircuitBuilder[In, Out, Cons]()
    body(b)
    b.result()

  /**
   * Materialize `circuit` as a running component. Each node gets its own RNG: a seed is drawn from `rng` for **every**
   * node in declaration order (so pinning one node's `rngSeed` never shifts another's), and a node uses its pinned seed
   * if it has one, otherwise the drawn seed. The materialized value completes at `EndOfTime` with the [[CircuitResult]].
   */
  def componentOf[In, Out, Cons](circuit: Circuit[In, Out, Cons], rng: UniformRandomProvider): Graph[
    FanOutShape2[TimedElement[Timed[In]], TimedElement[Timed[Out]], TimedElement[Timed[Cons]]],
    Future[CircuitResult]
  ] =
    val nodes = circuit.nodeSpecs.map { spec =>
      val drawn = rng.nextLong()
      spec.mk(RandomSource.KISS.create(spec.rngSeed.getOrElse(drawn)))
    }
    CircuitStage.componentOf[In, Out, Cons](CircuitPlan(nodes, circuit.routes, circuit.maxEventsPerWindow))

/**
 * Whether a node's consumption type carries real facts — resolved at compile time. A node whose consumption type is
 * `Nothing` (e.g. a gate) emits no consumption and needs no routing; any other node must route or ignore its
 * consumption plane (see [[Circuit.build]]).
 */
sealed trait ConsumptionDemand[C]:
  def mustRoute: Boolean

object ConsumptionDemand:
  private final class Demand[C](val mustRoute: Boolean) extends ConsumptionDemand[C]

  given nothing: ConsumptionDemand[Nothing] = new Demand[Nothing](false)

  given some[C](using NotGiven[C =:= Nothing]): ConsumptionDemand[C] = new Demand[C](true)
