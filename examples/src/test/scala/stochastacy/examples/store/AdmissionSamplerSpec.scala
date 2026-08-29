package stochastacy.examples.store

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.Scheduled

class AdmissionSamplerSpec extends AnyWordSpec with should.Matchers:

  private val cfg      = AdmissionConfig(capacityPerTick = 3, admissionLatencyTicks = 0.02)
  private val admission = new AdmissionSampler(cfg)
  private val rng      = RandomSource.KISS.create(1L)

  /** Feed `n` requests through one tick, threading state; return the outcomes and the final state. */
  private def feedTick(n: Int, start: AdmissionState): (Vector[AdmissionOutcome], AdmissionState) =
    var st  = start
    val out = Vector.newBuilder[AdmissionOutcome]
    (0 until n).foreach { _ =>
      val e = admission.sample(Get(), st, rng)
      out += e.output.event
      st = e.newState
    }
    (out.result(), st)

  "AdmissionSampler" should {

    "admit up to capacity within a tick and throttle the rest" in {
      val (outcomes, _) = feedTick(5, admission.initialState)
      outcomes shouldBe Vector(Admitted(Get()), Admitted(Get()), Admitted(Get()), Throttled, Throttled)
    }

    "preserve the request payload on the admitted branch" in {
      val e = admission.sample(Put(2048L), admission.initialState, rng)
      e.output.event shouldBe Admitted(Put(2048L))
    }

    "stamp every emission at the admission latency and emit latency + decision observations" in {
      val admitted = admission.sample(Get(), AdmissionState(0), rng)
      admitted.output.delay shouldBe cfg.admissionLatencyTicks
      admitted.consumption shouldBe List(
        Scheduled(AdmissionLatency(cfg.admissionLatencyTicks), cfg.admissionLatencyTicks),
        Scheduled(AdmissionDecision(false), cfg.admissionLatencyTicks)
      )

      val throttled = admission.sample(Get(), AdmissionState(cfg.capacityPerTick), rng)
      throttled.output.event shouldBe Throttled
      throttled.consumption shouldBe List(
        Scheduled(AdmissionLatency(cfg.admissionLatencyTicks), cfg.admissionLatencyTicks),
        Scheduled(AdmissionDecision(true), cfg.admissionLatencyTicks)
      )
    }

    "reset capacity at each tick boundary via onTick" in {
      val (firstTick, endOfFirst) = feedTick(5, admission.initialState)
      firstTick.count(_ == Throttled) shouldBe 2

      // Without the reset the next tick would stay saturated; onTick restores full capacity.
      val opened = admission.onTick(2L, endOfFirst).newState
      opened shouldBe AdmissionState(0)
      val (secondTick, _) = feedTick(3, opened)
      secondTick shouldBe Vector(Admitted(Get()), Admitted(Get()), Admitted(Get()))
    }
  }
