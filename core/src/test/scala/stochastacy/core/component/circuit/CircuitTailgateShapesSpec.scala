package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{Admit, ComponentSampler, Emission, FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, Reject, Scheduled}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.core.component.gate.FlatThrottleGate
import stochastacy.sim.SimInstant

/** Unit-level versions of the wiring shapes the tailgate simulator needs: a throttle gate whose admit / reject outcomes
 *  split along two filtered edges, and a client whose tap self-edge fires timeout probes and re-arms them on retry. */
class CircuitTailgateShapesSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitTailgateShapesSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def rng(): UniformRandomProvider = RandomSource.KISS.create(1L)

  private final case class Req(id: Int, attempt: Int)
  private sealed trait Resp
  private final case class Ok(id: Int, attempt: Int) extends Resp
  private case object Rejected extends Resp
  private final case class Probe(id: Int, attempt: Int)

  private type Log = Vector[(String, Int, Int, SimInstant)]

  private def approxTimes(log: Log, expected: Seq[Double]): Unit =
    log.map(_._4.toDouble).zip(expected).foreach((a, e) => a shouldBe (e +- 1e-9))
    log should have size expected.size.toLong

  "A circuit" should {

    "split a throttle gate's outcomes: admitted requests to the server, rejections straight back to the client" in {
      /** Sends each id; records every response it receives. */
      final class Client extends LoopbackComponentSampler[Log, Int, Resp, Req, Nothing, Nothing]:
        def initialState: Log = Vector.empty
        def sample(in: Int, at: SimInstant, s: Log, rng: UniformRandomProvider): LoopbackEmission[Log, Req, Nothing, Nothing] =
          LoopbackEmission(s, Scheduled(Req(in, 1), 0.0), Nil)
        def onFeedback(fb: Resp, at: SimInstant, s: Log, rng: UniformRandomProvider): FeedbackEmission[Log, Req, Nothing, Nothing] =
          fb match
            case Ok(id, a) => FeedbackEmission(s :+ (("ok", id, a, at)))
            case Rejected  => FeedbackEmission(s :+ (("rejected", -1, 0, at)))

      final class Server extends ComponentSampler[Unit, Req, Resp, Nothing]:
        def initialState: Unit = ()
        def sample(in: Req, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Resp, Nothing] =
          Emission((), Scheduled(Ok(in.id, in.attempt), 0.15), Nil)

      var client: CircuitNode[Log, Int, Resp, Req, Nothing, Nothing] = null
      val circuit = Circuit.build[Int, Nothing, Nothing] { b =>
        client = b.node("client", new Client)
        val throttle = b.node("throttle", new FlatThrottleGate[Req, Resp](capacityPerTick = 2, rejectResponse = Rejected))
        val server   = b.node("server", new Server)
        b.input(client.in)
        b.connect(client.out, throttle.in)
        b.connectVia(throttle.out, server.in) { case Admit(r) => r }
        b.connectVia(throttle.out, client.fb) { case Reject(_, r) => r }
        b.connect(server.out, client.fb)
      }
      val out = runCircuit(Circuit.componentOf(circuit, rng()), framed(Seq((1L, 0.1, 1), (1L, 0.2, 2), (1L, 0.3, 3)), horizon = 3L))
      val log = out.mat.stateOf(client)

      // The first two admitted (answered 0.15 later); the third rejected immediately at 0.3, between the two answers.
      log.map(e => (e._1, e._2)) shouldBe Vector(("ok", 1), ("rejected", -1), ("ok", 2))
      approxTimes(log, Seq(1.25, 1.3, 1.35))
      out.mat.unrouted shouldBe empty
    }

    "fire a timeout probe through a tap self-edge, retry once, re-arm the probe, and ignore late or resolved events" in {
      /** Tracks each id's current attempt while unresolved. On send / retry: forward the request and tap a probe 0.5
       *  later. A probe for a still-current attempt retries (attempt + 1, up to 3); anything else is ignored. */
      final case class ClientState(unresolved: Map[Int, Int], log: Log)
      final class Client extends LoopbackComponentSampler[ClientState, Int, Ok | Probe, Req, String, Probe]:
        def initialState: ClientState = ClientState(Map.empty, Vector.empty)
        def sample(in: Int, at: SimInstant, s: ClientState, rng: UniformRandomProvider): LoopbackEmission[ClientState, Req, String, Probe] =
          LoopbackEmission(ClientState(s.unresolved + (in -> 1), s.log :+ (("send", in, 1, at))),
            Scheduled(Req(in, 1), 0.0), Nil, List(Scheduled(Probe(in, 1), 0.5)))
        def onFeedback(fb: Ok | Probe, at: SimInstant, s: ClientState, rng: UniformRandomProvider): FeedbackEmission[ClientState, Req, String, Probe] =
          fb match
            case Ok(id, a) if s.unresolved.get(id).contains(a) =>
              FeedbackEmission(ClientState(s.unresolved - id, s.log :+ (("ok", id, a, at))), consumption = List(Scheduled(s"done-$id", 0.0)))
            case Ok(id, a) =>
              FeedbackEmission(s.copy(log = s.log :+ (("late", id, a, at))))
            case Probe(id, a) if s.unresolved.get(id).contains(a) && a < 3 =>
              FeedbackEmission(ClientState(s.unresolved + (id -> (a + 1)), s.log :+ (("retry", id, a + 1, at))),
                output = Some(Scheduled(Req(id, a + 1), 0.0)), taps = List(Scheduled(Probe(id, a + 1), 0.5)))
            case Probe(id, a) =>
              FeedbackEmission(s.copy(log = s.log :+ (("probe-ignored", id, a, at))))

      /** Odd ids' first attempts are slow (0.8); everything else answers in 0.2. */
      final class Server extends ComponentSampler[Unit, Req, Ok, Nothing]:
        def initialState: Unit = ()
        def sample(in: Req, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Ok, Nothing] =
          Emission((), Scheduled(Ok(in.id, in.attempt), if in.id % 2 != 0 && in.attempt == 1 then 0.8 else 0.2), Nil)

      var client: CircuitNode[ClientState, Int, Ok | Probe, Req, String, Probe] = null
      val circuit = Circuit.build[Int, Nothing, String] { b =>
        client = b.node("client", new Client)
        val server = b.node("server", new Server)
        b.input(client.in)
        b.connect(client.out, server.in)
        b.connect(server.out, client.fb)
        b.connect(client.taps, client.fb) // the timeout probe self-edge
        b.consumption(client.consumption)
      }
      val out = runCircuit(Circuit.componentOf(circuit, rng()), framed(Seq((1L, 0.0, 1), (1L, 0.1, 2)), horizon = 4L))
      val log = out.mat.stateOf(client).log

      log.map(e => (e._1, e._2, e._3)) shouldBe Vector(
        ("send", 1, 1), ("send", 2, 1),
        ("ok", 2, 1),             // fast id 2 answered at 1.3
        ("retry", 1, 2),          // id 1's probe fires at send + 0.5 = 1.5 → retry
        ("probe-ignored", 2, 1),  // id 2's probe at 1.6 — already resolved
        ("ok", 1, 2),             // the retry answered at 1.7
        ("late", 1, 1),           // the slow original lands at 1.8, after the retry resolved it
        ("probe-ignored", 1, 2)   // the re-armed probe fires at retry + 0.5 = 2.0 — resolved
      )
      approxTimes(log, Seq(1.0, 1.1, 1.3, 1.5, 1.6, 1.7, 1.8, 2.0))
      timedOnly(out.cons).map(_.event) shouldBe Seq("done-2", "done-1")
      out.mat.stateOf(client).unresolved shouldBe empty
    }
  }
