package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{ComponentSampler, Emission, ScheduleReleaseTransducer, Scheduled, TickEmission, Timed}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.sim.{SimInstant, TimedElement}

/**
 * The anchor invariant: a **one-node circuit** (input → node → outlets) fed a time-ordered input stream is
 * output-identical to `ScheduleReleaseTransducer.componentOf` running the same sampler with the same RNG — the same
 * forward and consumption element sequences (ticks and `EndOfTime` included), the same final state, and the same
 * forward / consumption residue. Exercised on toy equivalents of the transducer spec's fixtures.
 */
class CircuitAnchorSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitAnchorSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private final case class ToyReq(n: Int)
  private final case class ToyResp(id: Int)
  private final case class ToyCons(kind: String)

  private type Toy = ComponentSampler[Int, ToyReq, ToyResp, ToyCons]

  /** One response at `latency`, one consumption fact at `consDelay`; state counts requests (the response id). */
  private final class Delayed(latency: Double, consDelay: Double) extends Toy:
    def initialState: Int = 0
    def sample(in: ToyReq, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(s + 1, Scheduled(ToyResp(s), latency), List(Scheduled(ToyCons("work"), consDelay)))

  /** A boundary fact per tick, plus a request fact per request. */
  private final class BoundaryFacts extends Toy:
    def initialState: Int = 0
    def sample(in: ToyReq, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(s, Scheduled(ToyResp(s), 0.0), List(Scheduled(ToyCons("req"), 0.0)))
    override def onTick(tick: Long, s: Int): TickEmission[Int, ToyCons] =
      TickEmission(s, List(Scheduled(ToyCons(s"tick-$tick"), 0.0)))

  /** A per-tick counter reset by `onTick`. */
  private final class PerTickCounter extends Toy:
    def initialState: Int = 0
    def sample(in: ToyReq, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(s + 1, Scheduled(ToyResp(s), 0.0), Nil)
    override def onTick(tick: Long, s: Int): TickEmission[Int, ToyCons] = TickEmission(0, Nil)

  /** Response and consumption delays drawn from the RNG — proves the node's RNG stream lines up with the transducer's. */
  private final class RandomLatency extends Toy:
    def initialState: Int = 0
    def sample(in: ToyReq, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, ToyResp, ToyCons] =
      Emission(s + 1, Scheduled(ToyResp(s), rng.nextDouble() * 2.5), List(Scheduled(ToyCons("work"), rng.nextDouble())))

  private val sparse = framed(
    Seq((2L, 0.25, ToyReq(1)), (2L, 0.75, ToyReq(2)), (3L, 0.1, ToyReq(3)), (5L, 0.5, ToyReq(4)), (10L, 0.9, ToyReq(5))),
    horizon = 10L)

  private val dense = framed(
    (1 to 400).map(i => (((i - 1) / 20 + 1).toLong, ((i - 1) % 20) / 20.0 + 0.01, ToyReq(i))),
    horizon = 25L)

  private def assertAnchored(label: String, mk: () => Toy, input: Vector[TimedElement[Timed[ToyReq]]]): Unit =
    val viaTransducer = runCircuit(ScheduleReleaseTransducer.componentOf(mk(), RandomSource.KISS.create(1L)), input)
    val viaCircuit    = runCircuit(CircuitStage.componentOf[ToyReq, ToyResp, ToyCons](oneNodePlan(ErasedNode.of(label, mk(), RandomSource.KISS.create(1L)))), input)

    withClue(s"$label — forward plane: ")(viaCircuit.fwd shouldBe viaTransducer.fwd)
    withClue(s"$label — consumption plane: ")(viaCircuit.cons shouldBe viaTransducer.cons)
    withClue(s"$label — final state: ")(viaCircuit.mat.nodeStates shouldBe Vector(viaTransducer.mat.finalState))
    withClue(s"$label — residue: ")(viaCircuit.mat.residue shouldBe
      CircuitResidue(0L, viaTransducer.mat.residue.responses, viaTransducer.mat.residue.consumptions))
    viaCircuit.mat.unrouted shouldBe empty
    timedOnly(viaTransducer.fwd) should not be empty

  "A one-node circuit" should {
    "match componentOf for delayed responses and consumption" in {
      assertAnchored("delayed", () => new Delayed(latency = 2.0, consDelay = 0.0), sparse)
    }

    "match componentOf's post-horizon residue" in {
      assertAnchored("residue", () => new Delayed(latency = 1.5, consDelay = 1.5), sparse)
      val viaCircuit = runCircuit(CircuitStage.componentOf[ToyReq, ToyResp, ToyCons](
        oneNodePlan(ErasedNode.of("residue", new Delayed(1.5, 1.5), RandomSource.KISS.create(1L)))), sparse)
      viaCircuit.mat.residue shouldBe CircuitResidue(0L, 1L, 1L) // the tick-10 request's outputs land past the horizon
    }

    "match componentOf's tick-boundary facts" in {
      assertAnchored("boundary", () => new BoundaryFacts, sparse)
    }

    "match componentOf's per-tick onTick reset under dense input" in {
      assertAnchored("per-tick", () => new PerTickCounter, dense)
    }

    "match componentOf when delays are drawn from the node's RNG" in {
      assertAnchored("random", () => new RandomLatency, dense)
    }
  }
