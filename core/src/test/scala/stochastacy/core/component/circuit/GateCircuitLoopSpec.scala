package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{ComponentSampler, Emission, FeedbackEmission, InterfaceSampler, LoopbackComponentSampler,
  LoopbackEmission, Reject, Scheduled}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.core.component.gate.{ChaosGate, ContinuousTokenBucketGate, FlatThrottleGate, LatencyGate, TokenBucketGate}
import stochastacy.sim.SimInstant

/** Every shipped gate as a node in a **retry loop**: the client re-sends exactly the requests the gate rejected, which
 *  only works because a rejection carries its request. */
class GateCircuitLoopSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("GateCircuitLoopSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def rng(): UniformRandomProvider = RandomSource.KISS.create(1L)

  private final case class Req(id: Int, attempt: Int)
  private sealed trait Resp
  private final case class Ok(id: Int) extends Resp
  private case object Denied extends Resp

  private final case class ClientState(served: Vector[Int], retried: Vector[Int], failed: Vector[Int])

  /** Sends each id once; retries a rejected request once (identified from the rejection), then gives up. */
  private final class Client extends LoopbackComponentSampler[ClientState, Int, Ok | Reject[Req, Resp], Req, Nothing, Nothing]:
    def initialState: ClientState = ClientState(Vector.empty, Vector.empty, Vector.empty)
    def sample(in: Int, at: SimInstant, s: ClientState, rng: UniformRandomProvider): LoopbackEmission[ClientState, Req, Nothing, Nothing] =
      LoopbackEmission(s, Scheduled(Req(in, 1), 0.0), Nil)
    def onFeedback(fb: Ok | Reject[Req, Resp], at: SimInstant, s: ClientState, rng: UniformRandomProvider)
        : FeedbackEmission[ClientState, Req, Nothing, Nothing] =
      fb match
        case Ok(id) => FeedbackEmission(s.copy(served = s.served :+ id))
        case Reject(req, _) if req.attempt < 2 =>
          FeedbackEmission(s.copy(retried = s.retried :+ req.id), output = Some(Scheduled(Req(req.id, req.attempt + 1), 0.1)))
        case Reject(req, _) => FeedbackEmission(s.copy(failed = s.failed :+ req.id))

  private final class Server extends ComponentSampler[Unit, Req, Ok, Nothing]:
    def initialState: Unit = ()
    def sample(in: Req, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Ok, Nothing] =
      Emission((), Scheduled(Ok(in.id), 0.05), Nil)

  /** client → gate → server, rejections straight back to the client; four requests inside tick 1. */
  private def runLoop[S](gate: InterfaceSampler[S, Req, Resp]): ClientState =
    val (circuit, client) = Circuit.buildWith[Int, Nothing, Nothing] { b =>
      val c = b.node("client", new Client)
      val s = b.node("server", new Server)
      val g = b.gate("gate", gate)(admitTo = s.in, rejectTo = c.fb)
      b.input(c.in)
      b.connect(c.out, g.in)
      b.connect(s.out, c.fb)
      c
    }
    val input = framed(Seq((1L, 0.1, 1), (1L, 0.2, 2), (1L, 0.3, 3), (1L, 0.4, 4)), horizon = 3L)
    runCircuit(Circuit.componentOf(circuit, rng()), input).mat.stateOf(client)

  "A gate inside a circuit's retry loop" should {

    "let the client retry exactly the requests a flat throttle rejected" in {
      val s = runLoop(new FlatThrottleGate[Req, Resp](capacityPerTick = 2, rejectResponse = Denied))
      s.served shouldBe Vector(1, 2)   // the tick's first two admitted
      s.retried shouldBe Vector(3, 4)  // the rejections identified their requests
      s.failed shouldBe Vector(3, 4)   // the retries hit the same exhausted tick
    }

    "let the client retry exactly the requests a token bucket rejected" in {
      val s = runLoop(new TokenBucketGate[Req, Resp](capacity = 2, refillPerTick = 0, rejectResponse = Denied))
      s.served shouldBe Vector(1, 2)
      s.retried shouldBe Vector(3, 4)
      s.failed shouldBe Vector(3, 4)
    }

    "let the client retry exactly the requests a continuous token bucket rejected" in {
      val s = runLoop(new ContinuousTokenBucketGate[Req, Resp](capacity = 2, refillPerTick = 0, rejectResponse = Denied))
      s.served shouldBe Vector(1, 2)
      s.retried shouldBe Vector(3, 4)
      s.failed shouldBe Vector(3, 4)
    }

    "retry every request when a chaos gate rejects them all, then give up" in {
      val s = runLoop(ChaosGate.constant[Req, Resp](1.0, Denied))
      s.served shouldBe empty
      s.retried shouldBe Vector(1, 2, 3, 4)
      s.failed shouldBe Vector(1, 2, 3, 4)
    }

    "never retry behind an admit-only latency gate" in {
      val s = runLoop(LatencyGate.constant[Req, Resp](0.02))
      s.served shouldBe Vector(1, 2, 3, 4)
      s.retried shouldBe empty
      s.failed shouldBe empty
    }
  }
