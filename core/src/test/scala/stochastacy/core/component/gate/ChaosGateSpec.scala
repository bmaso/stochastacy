package stochastacy.core.component.gate

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.{Admit, InterfaceSampler, Reject}
import stochastacy.core.sampler.BernoulliSampler

class ChaosGateSpec extends AnyWordSpec with should.Matchers:

  private final case class Req(id: Int)
  private final case class Resp(kind: String)
  private val rng    = RandomSource.KISS.create(1L)
  private val fail503 = Resp("unavailable")

  "ChaosGate" should {

    "reject with the configured response when the failure draw is certain" in {
      val gate = ChaosGate.constant[Req, Resp](1.0, fail503)
      val e = gate.sample(Req(1), gate.initialState, rng)
      e.output.event shouldBe Reject(fail503)
      e.consumption shouldBe Nil
    }

    "admit when the failure draw never fires" in {
      val gate = ChaosGate.constant[Req, Resp](0.0, fail503)
      gate.sample(Req(1), gate.initialState, rng).output.event shouldBe Admit(Req(1))
    }

    "produce a mix of outcomes at an intermediate probability" in {
      val gate = ChaosGate.constant[Req, Resp](0.5, fail503)
      val outcomes = (0 until 200).map(i => gate.sample(Req(i), gate.initialState, rng).output.event)
      outcomes.exists(_.isInstanceOf[Admit[?]]) shouldBe true
      outcomes.exists(_.isInstanceOf[Reject[?]]) shouldBe true
    }

    "thread the current tick into the sampler via onTick (time-varying failure rate)" in {
      // Fails only from tick 10 onward.
      val gate  = new ChaosGate[Req, Resp](BernoulliSampler(t => if t >= 10L then 1.0 else 0.0), fail503)
      val atT5  = gate.onTick(5L, gate.initialState).newState
      gate.sample(Req(0), atT5, rng).output.event shouldBe Admit(Req(0))
      val atT10 = gate.onTick(10L, atT5).newState
      gate.sample(Req(0), atT10, rng).output.event shouldBe Reject(fail503)
    }
  }

  "The chaos/throttle orthogonality experiment" should {

    // outer = chaos (503), inner = flat throttle (429): served / 503 / 429 counts.
    def simulateStack[S1, S2](
      outer: InterfaceSampler[S1, Req, Resp],
      inner: InterfaceSampler[S2, Req, Resp],
      arrivalsPerTick: Seq[Int]
    ): (Int, Int, Int) =
      var so = outer.initialState
      var si = inner.initialState
      var served, outerRej, innerRej = 0
      arrivalsPerTick.zipWithIndex.foreach { case (n, i) =>
        so = outer.onTick(i + 1L, so).newState
        si = inner.onTick(i + 1L, si).newState
        (0 until n).foreach { _ =>
          val eo = outer.sample(Req(0), so, rng); so = eo.newState
          eo.output.event match
            case _: Reject[?] => outerRej += 1
            case _: Admit[?] =>
              val ei = inner.sample(Req(0), si, rng); si = ei.newState
              ei.output.event match
                case _: Reject[?] => innerRej += 1
                case _: Admit[?]  => served += 1
        }
      }
      (served, outerRej, innerRej)

    val p    = 0.1
    val cap  = 5
    val ticks = 200

    /** (chaosRate, throttleRate) as fractions of all requests, at a given constant per-tick load. */
    def rates(loadPerTick: Int): (Double, Double) =
      val chaos    = ChaosGate.constant[Req, Resp](p, fail503)
      val throttle = new FlatThrottleGate[Req, Resp](cap, Resp("throttled"))
      val (served, r503, r429) = simulateStack(chaos, throttle, Seq.fill(ticks)(loadPerTick))
      val total = served + r503 + r429
      total shouldBe ticks * loadPerTick          // one terminal outcome per request
      (r503.toDouble / total, r429.toDouble / total)

    "hold the 503 (chaos) rate ≈ constant while the 429 (throttle) rate climbs with load" in {
      val (chaosLo, throttleLo) = rates(3)         // survivors under cap: no throttling
      val (chaosMd, throttleMd) = rates(8)
      val (chaosHi, throttleHi) = rates(20)        // survivors well over cap: heavy throttling

      // Chaos is load-independent: ~p at every load, and barely moves across a 6.7x load increase.
      Seq(chaosLo, chaosMd, chaosHi).foreach { r => r shouldBe (p +- 0.03) }
      (Seq(chaosLo, chaosMd, chaosHi).max - Seq(chaosLo, chaosMd, chaosHi).min) should be < 0.03

      // Throttling is load-driven: strictly increasing, and swings far more than chaos does.
      throttleLo should be < throttleMd
      throttleMd should be < throttleHi
      (throttleHi - throttleLo) should be > 0.4
    }
  }
