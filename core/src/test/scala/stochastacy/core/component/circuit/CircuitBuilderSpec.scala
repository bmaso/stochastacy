package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{ComponentSampler, Emission, FeedbackEmission, LoopbackComponentSampler, LoopbackEmission, Scheduled, TickEmission}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.core.component.gate.FlatThrottleGate
import stochastacy.sim.SimInstant

/** The typed circuit builder: a two-node loop with typed final states, every wiring form, wiretaps, build-time
 *  validation, per-node RNG derivation, and blueprint reuse. */
class CircuitBuilderSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitBuilderSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def rng(seed: Long = 1L): UniformRandomProvider = RandomSource.KISS.create(seed)

  // --- a two-node paging loop ---

  private final case class Session(id: Int)
  private final case class PageReq(session: Int, page: Int)
  private final case class PageResp(session: Int, page: Int)

  /** Requests page 1 per session; on each response, requests the next page until page 3; counts finished sessions. */
  private final class Client extends LoopbackComponentSampler[Int, Session, PageResp, PageReq, String, Nothing]:
    def initialState: Int = 0
    def sample(in: Session, at: SimInstant, s: Int, rng: UniformRandomProvider): LoopbackEmission[Int, PageReq, String, Nothing] =
      LoopbackEmission(s, Scheduled(PageReq(in.id, 1), 0.0), Nil)
    def onFeedback(fb: PageResp, at: SimInstant, s: Int, rng: UniformRandomProvider): FeedbackEmission[Int, PageReq, String, Nothing] =
      if fb.page < 3 then FeedbackEmission(s, output = Some(Scheduled(PageReq(fb.session, fb.page + 1), 0.0)))
      else FeedbackEmission(s + 1, consumption = List(Scheduled(s"done-${fb.session}", 0.0)))

  /** Serves each page after 0.1 ticks; counts pages served. */
  private final class Server extends ComponentSampler[Int, PageReq, PageResp, String]:
    def initialState: Int = 0
    def sample(in: PageReq, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, PageResp, String] =
      Emission(s + 1, Scheduled(PageResp(in.session, in.page), 0.1), List(Scheduled("served", 0.0)))

  // --- small typed toys ---

  /** Answers `n`; records `n × 10` as an Int consumption fact. */
  private final class IntEcho extends ComponentSampler[Unit, Int, Int, Int]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Int, Int] =
      Emission((), Scheduled(in, 0.0), List(Scheduled(in * 10, 0.0)))

  /** Answers `s + "!"`; no consumption. */
  private final class Shout extends ComponentSampler[Unit, String, String, Nothing]:
    def initialState: Unit = ()
    def sample(in: String, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, String, Nothing] =
      Emission((), Scheduled(in + "!", 0.0), Nil)

  /** Answers `n` after 0.1; records `work<n>`. */
  private final class Worker extends ComponentSampler[Unit, Int, Int, String]:
    def initialState: Unit = ()
    def sample(in: Int, at: SimInstant, s: Unit, rng: UniformRandomProvider): Emission[Unit, Int, String] =
      Emission((), Scheduled(in, 0.1), List(Scheduled(s"work$in", 0.0)))

  /** Draws one long per input from its RNG, keeping every draw in its state and answering with it. */
  private final class RngEcho extends ComponentSampler[Vector[Long], Int, Long, Nothing]:
    def initialState: Vector[Long] = Vector.empty
    def sample(in: Int, at: SimInstant, s: Vector[Long], rng: UniformRandomProvider): Emission[Vector[Long], Long, Nothing] =
      val v = rng.nextLong()
      Emission(s :+ v, Scheduled(v, 0.0), Nil)

  private type EchoNode    = CircuitNode[Unit, Int, Nothing, Int, Int, Nothing]
  private type RngEchoNode = CircuitNode[Vector[Long], Int, Nothing, Long, Nothing, Nothing]

  "A circuit built with the typed builder" should {

    "run a two-node paging loop and expose each node's typed final state" in {
      var client: CircuitNode[Int, Session, PageResp, PageReq, String, Nothing] = null
      var server: CircuitNode[Int, PageReq, Nothing, PageResp, String, Nothing] = null
      val circuit = Circuit.build[Session, Nothing, String] { b =>
        client = b.node("client", new Client)
        server = b.node("server", new Server)
        b.input(client.in)
        b.connect(client.out, server.in)
        b.connect(server.out, client.fb)
        b.consumption(client.consumption)
        b.consumption(server.consumption)
      }
      val out = runCircuit(Circuit.componentOf(circuit, rng()), framed(Seq((1L, 0.1, Session(1)), (1L, 0.5, Session(2))), horizon = 3L))

      out.mat.stateOf(client) shouldBe 2 // both sessions finished
      out.mat.stateOf(server) shouldBe 6 // 2 sessions × 3 pages
      timedOnly(out.cons).map(_.event).count(_ == "served") shouldBe 6
      timedOnly(out.cons).map(_.event).filter(_.startsWith("done")) shouldBe Seq("done-1", "done-2")
      circuit.nodeNames shouldBe Vector("client", "server")
    }

    "support every wiring form — connect(Via), input(Via), output(Via), consumption(Via), ignore" in {
      var c: EchoNode = null
      val circuit = Circuit.build[Int, String, String] { b =>
        val a = b.node("a", new IntEcho)
        val s = b.node("shout", new Shout)
        c = b.node("c", new IntEcho)
        b.inputVia(a.in) { case n if n >= 0 => n }                     // negatives filtered at the input
        b.connectVia(a.out, s.in) { case n if n % 2 == 0 => s"even$n" } // evens → shout (type conversion)
        b.outputVia(a.out) { case n if n % 2 != 0 => s"odd$n" }        // odds → out
        b.output(s.out)
        b.connect(a.out, c.in)                                          // every a output also feeds c
        b.consumptionVia(a.consumption) { case n => s"cons$n" }
        b.ignore(c.consumption)                                         // c's Int consumption dropped explicitly
      }
      val out = runCircuit(Circuit.componentOf(circuit, rng()), framed(Seq((1L, 0.1, 1), (1L, 0.2, 2), (1L, 0.3, -3)), horizon = 2L))

      timedOnly(out.fwd).map(_.event) shouldBe Seq("odd1", "even2!")
      timedOnly(out.cons).map(_.event) shouldBe Seq("cons10", "cons20")
      out.mat.unroutedInputs shouldBe 1L
      out.mat.unrouted.map(u => (u.nodeName, u.plane, u.count)).toSet shouldBe
        Set(("c", CircuitPlane.Out, 2L), ("c", CircuitPlane.Consumption, 2L))
    }

    "wiretap a plane onto the consumption outlet in time order, without counting the copy as routing" in {
      val routed = Circuit.build[Int, Int, String] { b =>
        val w = b.node("worker", new Worker)
        b.input(w.in)
        b.output(w.out)
        b.consumption(w.consumption)
        b.wiretap(w.out) { case n => s"tap$n" }
      }
      val out = runCircuit(Circuit.componentOf(routed, rng()), framed(Seq((1L, 0.1, 1), (1L, 0.5, 2)), horizon = 2L))
      timedOnly(out.cons).map(t => (t.event, t.intraTick)).map((e, i) => (e, math.round(i * 10))) shouldBe
        Seq(("work1", 1L), ("tap1", 2L), ("work2", 5L), ("tap2", 6L))
      out.mat.unrouted shouldBe empty

      val onlyTapped = Circuit.build[Int, Nothing, String] { b =>
        val w = b.node("worker", new Worker)
        b.input(w.in)
        b.consumption(w.consumption)
        b.wiretap(w.out) { case n => s"tap$n" }
      }
      val tapped = runCircuit(Circuit.componentOf(onlyTapped, rng()), framed(Seq((1L, 0.1, 1), (1L, 0.5, 2)), horizon = 2L))
      timedOnly(tapped.cons).map(_.event).filter(_.startsWith("tap")) shouldBe Seq("tap1", "tap2")
      tapped.mat.unrouted shouldBe Vector(UnroutedCount(0, "worker", CircuitPlane.Out, 2L))
    }

    "reject a malformed circuit when it is built" in {
      def failure(body: CircuitBuilder[Int, Int, Int] => Unit): String =
        intercept[IllegalArgumentException](Circuit.build[Int, Int, Int](body)).getMessage

      failure { b => b.ignore(b.node("a", new IntEcho).consumption) } should include ("at least one input route")

      failure { b =>
        val a = b.node("dup", new IntEcho); val a2 = b.node("dup", new IntEcho)
        b.input(a.in); b.input(a2.in); b.ignore(a.consumption); b.ignore(a2.consumption)
      } should include ("unique")

      failure { b =>
        val a = b.node("a", new IntEcho); val orphan = b.node("orphan", new IntEcho)
        b.input(a.in); b.ignore(a.consumption); b.ignore(orphan.consumption)
      } should include ("'orphan' has no inbound route")

      failure { b => val a = b.node("a", new IntEcho); b.input(a.in) } should include ("neither routed")

      failure { b =>
        val a = b.node("a", new IntEcho)
        b.input(a.in); b.ignore(a.consumption); b.consumption(a.consumption)
      } should include ("explicitly ignored")

      var foreign: EchoNode = null
      Circuit.build[Int, Int, Int] { b => foreign = b.node("x", new IntEcho); b.input(foreign.in); b.consumption(foreign.consumption) }
      failure { b =>
        val a = b.node("a", new IntEcho)
        b.input(a.in); b.consumption(a.consumption); b.connect(foreign.out, a.in)
      } should include ("different circuit builder")
    }

    "not require routing for a node whose consumption type is Nothing (a gate)" in {
      noException should be thrownBy Circuit.build[Int, Int, Nothing] { b =>
        val gate = b.node("gate", new FlatThrottleGate[Int, Int](capacityPerTick = 1, rejectResponse = -1))
        b.input(gate.in)
      }
    }

    "derive per-node RNGs by declaration order, independent of wiring, with a pinned seed shifting no other node" in {
      val input = framed((1L to 5L).map(t => (t, 0.5, t.toInt)), horizon = 5L)
      def statesOf(pinSecond: Option[Long], outputAll: Boolean): Vector[Vector[Long]] =
        var nodes = Vector.empty[RngEchoNode]
        val circuit = Circuit.build[Int, Long, Nothing] { b =>
          nodes = Vector(b.node("n1", new RngEcho), b.node("n2", new RngEcho, rngSeed = pinSecond), b.node("n3", new RngEcho))
          nodes.foreach(n => b.input(n.in))
          if outputAll then nodes.foreach(n => b.output(n.out)) else b.output(nodes(2).out)
        }
        val result = runCircuit(Circuit.componentOf(circuit, rng(7L)), input).mat
        nodes.map(result.stateOf(_))

      val baseline = statesOf(pinSecond = None, outputAll = true)
      baseline.map(_.size) shouldBe Vector(5, 5, 5)
      baseline.distinct should have size 3                               // independent streams
      statesOf(pinSecond = None, outputAll = false) shouldBe baseline    // wiring changes don't move any stream

      val pinned = statesOf(pinSecond = Some(99L), outputAll = true)
      pinned(0) shouldBe baseline(0)
      pinned(2) shouldBe baseline(2)
      pinned(1).head shouldBe RandomSource.KISS.create(99L).nextLong()
    }

    "be reusable: materializing one blueprint twice with equal seeds gives identical runs" in {
      val circuit = Circuit.build[Int, Long, Nothing] { b =>
        val n = b.node("n", new RngEcho)
        b.input(n.in)
        b.output(n.out)
      }
      val input  = framed((1L to 4L).map(t => (t, 0.25, t.toInt)), horizon = 4L)
      val first  = runCircuit(Circuit.componentOf(circuit, rng(3L)), input)
      val second = runCircuit(Circuit.componentOf(circuit, rng(3L)), input)
      second.fwd shouldBe first.fwd
      second.mat shouldBe first.mat
    }
  }
