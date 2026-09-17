package stochastacy.core.component.gate

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.core.component.{Admit, InterfaceSampler, Reject}
import stochastacy.sim.SimInstant

/** The continuous-refill bucket: tokens accrue from elapsed conceptual time, so a loop that closes inside a tick sees
 *  a real rate limit rather than the tick. Its defining bound — admissions over any interval `T` never exceed
 *  `capacity + refill × T` — is asserted directly, and the tick-granular bucket is shown failing the mid-tick case. */
class ContinuousTokenBucketGateSpec extends AnyWordSpec with should.Matchers:

  private final case class Req(id: Int)
  private final case class Resp(kind: String)
  private val rng    = RandomSource.KISS.create(1L)
  private val denied = Resp("throttled")

  private def at(tick: Long, intra: Double): SimInstant = SimInstant(tick, intra)

  /** Feed one request at each instant, threading state; return each instant paired with whether it was admitted. */
  private def feed[S](gate: InterfaceSampler[S, Req, Resp], instants: Seq[SimInstant]): Vector[(SimInstant, Boolean)] =
    var st  = gate.initialState
    val out = Vector.newBuilder[(SimInstant, Boolean)]
    instants.zipWithIndex.foreach { (i, n) =>
      val e = gate.sample(Req(n), i, st, rng)
      st = e.newState
      out += ((i, e.output.event.isInstanceOf[Admit[?]]))
    }
    out.result()

  "ContinuousTokenBucketGate" should {

    "refill mid-tick — where the tick-granular bucket stays empty until the boundary" in {
      // capacity 2, refill 2/tick. Drain both tokens at (1, 0.0), then ask again half a tick later: half a tick of
      // elapsed time is worth a whole token.
      val instants = Seq(at(1L, 0.0), at(1L, 0.0), at(1L, 0.1), at(1L, 0.6))
      val continuous = new ContinuousTokenBucketGate[Req, Resp](capacity = 2, refillPerTick = 2, denied)
      feed(continuous, instants).map(_._2) shouldBe Vector(true, true, false, true)

      // The tick-granular bucket only tops up at a boundary, so the same mid-tick request is rejected.
      val perTick = new TokenBucketGate[Req, Resp](capacity = 2, refillPerTick = 2, denied)
      feed(perTick, instants).map(_._2) shouldBe Vector(true, true, false, false)
    }

    "never admit more than capacity + refill × T over any interval" in {
      val capacity = 5.0
      val refill   = 10.0
      val gate     = new ContinuousTokenBucketGate[Req, Resp](capacity, refill, denied)
      // 300 arrivals at 0.01-tick spacing across three ticks — heavy overload, so the bound is actually exercised.
      val instants = (0 until 300).map { i => val t = i * 0.01; at(1L + t.toLong, t - t.toLong) }
      val admitted = feed(gate, instants).collect { case (i, true) => i.toDouble }

      admitted.size should be > 20 // the run is not trivially empty
      for
        i <- admitted.indices
        j <- i until admitted.size
      do
        val count = j - i + 1
        val span  = admitted(j) - admitted(i)
        withClue(s"[${admitted(i)}, ${admitted(j)}]: ") {
          count.toDouble should be <= capacity + refill * span + 1e-9
        }
    }

    "start full, and cap accrual at capacity however long it idles" in {
      val gate = new ContinuousTokenBucketGate[Req, Resp](capacity = 3, refillPerTick = 1, denied)
      // Three tokens at the start; the fourth immediate request finds none.
      feed(gate, Seq.fill(4)(at(1L, 0.0))).map(_._2) shouldBe Vector(true, true, true, false)
      // Drain, idle ten ticks, then burst: accrual is capped at capacity, so only three more are admitted.
      val idleThenBurst = Seq.fill(3)(at(1L, 0.0)) ++ Seq.fill(5)(at(11L, 0.0))
      feed(gate, idleThenBurst).map(_._2) shouldBe Vector(true, true, true, true, true, true, false, false)
    }

    "reject with the configured response, carrying the rejected request" in {
      val gate = new ContinuousTokenBucketGate[Req, Resp](capacity = 0, refillPerTick = 0, denied)
      gate.sample(Req(7), at(1L, 0.0), gate.initialState, rng).output.event shouldBe Reject(Req(7), denied)
    }
  }
