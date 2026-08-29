package stochastacy.core.component.gate

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.Admit
import stochastacy.core.sampler.Sampler

class LatencyGateSpec extends AnyWordSpec with should.Matchers:

  private final case class Req(id: Int)
  private final case class Resp(kind: String)
  private val rng = RandomSource.KISS.create(1L)

  "LatencyGate" should {

    "admit every request with a constant latency and no consumption" in {
      val gate = LatencyGate.constant[Req, Resp](0.4)
      val e = gate.sample(Req(1), gate.initialState, rng)
      e.output.event shouldBe Admit(Req(1))
      e.output.delay shouldBe 0.4
      e.consumption shouldBe Nil
    }

    "never reject regardless of volume" in {
      val gate = LatencyGate.constant[Req, Resp](0.1)
      var s = gate.initialState
      (0 until 100).foreach { i =>
        val e = gate.sample(Req(i), s, rng)
        e.output.event shouldBe a[Admit[?]]
        s = e.newState
      }
    }

    "draw a fresh latency per request from its sampler" in {
      // A distributional latency in [0, 1): every request admitted, delay in range, draws vary.
      val gate = new LatencyGate[Req, Resp](Sampler.stateless((_, r) => r.nextDouble()))
      val delays = (0 until 20).map(i => gate.sample(Req(i), gate.initialState, rng).output.delay)
      delays.foreach { d => d should (be >= 0.0 and be < 1.0) }
      delays.distinct.size should be > 1
    }

    "thread the current tick into the sampler via onTick (time-varying latency)" in {
      // Latency = tick * 0.1; onTick sets the state the sampler reads.
      val gate  = new LatencyGate[Req, Resp](Sampler.deterministic(tick => tick.toDouble * 0.1))
      val atT5  = gate.onTick(5L, gate.initialState).newState
      gate.sample(Req(0), atT5, rng).output.delay shouldBe (0.5 +- 1e-9)
      val atT12 = gate.onTick(12L, atT5).newState
      gate.sample(Req(0), atT12, rng).output.delay shouldBe (1.2 +- 1e-9)
    }

    "clamp negative latency draws to zero" in {
      val gate = new LatencyGate[Req, Resp](Sampler.deterministic(_ => -1.0))
      gate.sample(Req(0), gate.initialState, rng).output.delay shouldBe 0.0
    }
  }
