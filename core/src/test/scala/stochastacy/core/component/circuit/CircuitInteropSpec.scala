package stochastacy.core.component.circuit

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{ComponentSampler, Emission, Interface, ScheduleReleaseTransducer, Scheduled, Timed}
import stochastacy.core.component.circuit.CircuitTestSupport.*
import stochastacy.core.component.gate.FlatThrottleGate
import stochastacy.core.run.TrialRunner
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimInstant, SimTime, TimedElement, ticks}

/** A typed circuit as an ordinary component: the typed anchor (≡ `componentOf`), behind `Interface.wrap`, and driven by
 *  `TrialRunner`. */
class CircuitInteropSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("CircuitInteropSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private def rng(seed: Long): UniformRandomProvider = RandomSource.KISS.create(seed)

  /** Answers `ok<n>`, records one `served` fact; counts requests. */
  private final class Echo extends ComponentSampler[Int, Int, String, String]:
    def initialState: Int = 0
    def sample(in: Int, at: SimInstant, s: Int, rng: UniformRandomProvider): Emission[Int, String, String] =
      Emission(s + 1, Scheduled(s"ok$in", 0.0), List(Scheduled("served", 0.0)))

  "A typed circuit" should {

    "match componentOf when built with one node pinned to the transducer's seed" in {
      val circuit = Circuit.build[AnchorReq, AnchorResp, AnchorCons] { b =>
        val n = b.node("toy", new RandomLatencyToy, rngSeed = Some(1L))
        b.input(n.in)
        b.output(n.out)
        b.consumption(n.consumption)
      }
      val viaTransducer = runCircuit(ScheduleReleaseTransducer.componentOf(new RandomLatencyToy, rng(1L)), denseAnchorInput)
      val viaCircuit    = runCircuit(Circuit.componentOf(circuit, rng(777L)), denseAnchorInput) // circuit rng irrelevant: seed pinned

      viaCircuit.fwd shouldBe viaTransducer.fwd
      viaCircuit.cons shouldBe viaTransducer.cons
      viaCircuit.mat.nodeStates shouldBe Vector(viaTransducer.mat.finalState)
      viaCircuit.mat.residue shouldBe CircuitResidue(0L, viaTransducer.mat.residue.responses, viaTransducer.mat.residue.consumptions)
    }

    "compose behind Interface.wrap — rejections short-circuit, the circuit's result is preserved" in {
      var echo: CircuitNode[Int, Int, Nothing, String, String, Nothing] = null
      val circuit = Circuit.build[Int, String, String] { b =>
        echo = b.node("echo", new Echo)
        b.input(echo.in)
        b.output(echo.out)
        b.consumption(echo.consumption)
      }
      val wrapped = Interface.wrap(Circuit.componentOf(circuit, rng(1L)),
        new FlatThrottleGate[Int, String](capacityPerTick = 1, rejectResponse = "rejected"), rng(2L))
      val out = runCircuit(wrapped, framed(Seq((1L, 0.1, 1), (1L, 0.2, 2), (2L, 0.1, 3)), horizon = 2L))

      // Interface.wrap rejoins admitted and rejected responses with MergeTimedEventGraph, which does not order events
      // within a tick — so compare in conceptual-time order.
      timedOnly(out.fwd).sortBy(t => (t.eventTime.ticks, t.intraTick)).map(_.event) shouldBe Seq("ok1", "rejected", "ok3")
      out.mat.stateOf(echo) shouldBe 2 // only admitted requests reached the circuit
    }

    "run under TrialRunner, returning the CircuitResult alongside the consumption sink's value" in {
      var echo: CircuitNode[Int, Int, Nothing, String, String, Nothing] = null
      val circuit = Circuit.build[Int, String, String] { b =>
        echo = b.node("echo", new Echo)
        b.input(echo.in)
        b.output(echo.out)
        b.consumption(echo.consumption)
      }
      val source = TickFraming.frameSource(Iterator.tabulate(10)(i => Timed(i, SimTime.of(i / 5 + 1L), (i % 5) / 5.0, "uc")), 2L)
      val countFacts = Sink.fold[Int, TimedElement[Timed[String]]](0) { (n, e) => e match { case _: Timed[?] => n + 1; case _ => n } }

      val (result, facts) = Await.result(TrialRunner.run(source, Circuit.componentOf(circuit, rng(1L)), countFacts), 30.seconds)
      result.stateOf(echo) shouldBe 10
      facts shouldBe 10
    }
  }
