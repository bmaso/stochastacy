package stochastacy.core.component.gate

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.{Admit, InterfaceSampler, Reject}

class TokenBucketGateSpec extends AnyWordSpec with should.Matchers:

  private final case class Req(id: Int)
  private final case class Resp(kind: String)
  private val rng = RandomSource.KISS.create(1L)
  private val reject = Resp("throttled")

  /** Pure-sampler simulation: for each tick, refill (`onTick`) then admit/reject each arrival. */
  private def simulate[S](gate: InterfaceSampler[S, Req, Resp], arrivalsPerTick: Seq[Int]): (Int, Int) =
    var st: S = gate.initialState
    var admitted = 0
    var rejected = 0
    arrivalsPerTick.zipWithIndex.foreach { case (n, i) =>
      st = gate.onTick(i + 1L, st).newState
      (0 until n).foreach { _ =>
        val e = gate.sample(Req(0), st, rng)
        e.output.event match
          case _: Admit[?]  => admitted += 1
          case _: Reject[?] => rejected += 1
        st = e.newState
      }
    }
    (admitted, rejected)

  "TokenBucketGate" should {

    "start full, admit while tokens remain, and reject once empty" in {
      val gate = new TokenBucketGate[Req, Resp](capacity = 3, refillPerTick = 0.0, reject)
      // One tick, 5 arrivals, no refill: the full-3 bucket admits 3 then rejects 2.
      simulate(gate, Seq(5)) shouldBe (3, 2)
    }

    "accumulate fractional refill across ticks until a whole token is available" in {
      val gate = new TokenBucketGate[Req, Resp](capacity = 3, refillPerTick = 0.5, reject)
      // [3,1,1]: tick1 drains the full bucket (admit 3); tick2 has 0.5 tokens (reject 1);
      // tick3 reaches 1.0 (admit 1).
      simulate(gate, Seq(3, 1, 1)) shouldBe (4, 1)
    }

    "cap refill at capacity (quiet ticks do not over-fill)" in {
      val gate = new TokenBucketGate[Req, Resp](capacity = 3, refillPerTick = 5.0, reject)
      // Quiet ticks cannot bank beyond capacity 3, so a later 10-arrival burst admits only 3.
      simulate(gate, Seq(0, 0, 0, 10)) shouldBe (3, 7)
    }

    // --- the headline experiment: flat cap vs. token bucket at equal average rate R = 5 ---

    "absorb a burst that a flat cap rejects, when the mean is under capacity (burst tolerance)" in {
      val arrivals = Seq(2, 2, 2, 18, 2, 2)                       // mean ≈ 4.67 < R = 5
      val flat   = simulate(new FlatThrottleGate[Req, Resp](5, reject), arrivals)
      val bucket = simulate(new TokenBucketGate[Req, Resp](capacity = 20, refillPerTick = 5, reject), arrivals)

      flat shouldBe (15, 13)                                      // flat cap rejects the spike
      bucket shouldBe (28, 0)                                     // bucket absorbs it entirely
      bucket._2 shouldBe 0
      bucket._1 should be > flat._1                               // and admits more
    }

    "still throttle under sustained overload, with an advantage bounded by its bucket size (no cheating)" in {
      val capacity = 20.0
      val arrivals = Seq.fill(20)(10)                             // mean 10 > R = 5, sustained
      val flat   = simulate(new FlatThrottleGate[Req, Resp](5, reject), arrivals)
      val bucket = simulate(new TokenBucketGate[Req, Resp](capacity, refillPerTick = 5, reject), arrivals)

      flat._2 shouldBe 100
      bucket._2 should be > 0                                     // the bucket throttles heavily too
      (flat._2 - bucket._2).toDouble should be <= capacity        // its one-time advantage ≤ bucket size
    }
  }
