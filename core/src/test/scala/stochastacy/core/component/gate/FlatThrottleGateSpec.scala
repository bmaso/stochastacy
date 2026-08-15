package stochastacy.core.component.gate

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.{Admit, Reject, Scheduled}

class FlatThrottleGateSpec extends AnyWordSpec with should.Matchers:

  private final case class Req(id: Int)
  private final case class Resp(kind: String)

  private val gate = new FlatThrottleGate[Req, Resp](capacityPerTick = 3, rejectResponse = Resp("throttled"))
  private val rng  = RandomSource.KISS.create(1L)

  /** Feed `n` requests through one tick, threading state; return the outcomes and the final state. */
  private def feedTick(n: Int, start: FlatThrottleGate.State): (Vector[Any], FlatThrottleGate.State) =
    var st  = start
    val out = Vector.newBuilder[Any]
    (0 until n).foreach { i =>
      val e = gate.sample(Req(i), st, rng)
      out += e.output.event
      st = e.newState
    }
    (out.result(), st)

  "FlatThrottleGate" should {

    "admit up to capacity within a tick and reject the rest with the configured response" in {
      val (outcomes, _) = feedTick(5, gate.initialState)
      outcomes shouldBe Vector(Admit(Req(0)), Admit(Req(1)), Admit(Req(2)), Reject(Resp("throttled")), Reject(Resp("throttled")))
    }

    "carry no consumption and stamp the outcome at the configured latency" in {
      val e = new FlatThrottleGate[Req, Resp](2, Resp("x"), latencyTicks = 0.03).sample(Req(1), FlatThrottleGate.State(0), rng)
      e.consumption shouldBe Nil
      e.output.delay shouldBe 0.03
    }

    "reset capacity at each tick boundary via onTick" in {
      val (_, endOfFirst) = feedTick(5, gate.initialState)
      endOfFirst.admittedThisTick shouldBe 3

      val opened = gate.onTick(2L, endOfFirst)
      opened shouldBe FlatThrottleGate.State(0)
      val (secondTick, _) = feedTick(3, opened)
      secondTick shouldBe Vector(Admit(Req(0)), Admit(Req(1)), Admit(Req(2)))
    }
  }
