package stochastacy.core.stream

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.*
import stochastacy.sim.TimedControlEvent.{EndOfTime, Tick}

class TickFramingSpec extends AnyWordSpec with should.Matchers:

  private final case class Ev(eventTime: SimTime, override val intraTick: Double = 0.0, usecase: Any = "uc")
      extends TimedEvent

  private def t(n: Long): SimTime = SimTime.of(n)

  "TickFraming.frame" should {

    "produce Tick(1..N), a flush Tick(N+1), then EndOfTime for an empty event stream" in {
      TickFraming.frame(Iterator.empty[Ev], 3).toList shouldBe
        List(Tick(t(1)), Tick(t(2)), Tick(t(3)), Tick(t(4)), EndOfTime)
    }

    "open each tick with its Tick and drain that tick's events after it" in {
      val e1  = Ev(t(1))
      val e1b = Ev(t(1))
      val e3  = Ev(t(3))
      TickFraming.frame(Vector(e1, e1b, e3).iterator, 3).toList shouldBe
        List[Any](Tick(t(1)), e1, e1b, Tick(t(2)), Tick(t(3)), e3, Tick(t(4)), EndOfTime)
    }

    "end every framed stream with EndOfTime as the terminal element" in {
      TickFraming.frame(Vector(Ev(t(2))).iterator, 5).toList.last shouldBe EndOfTime
    }
  }

  "TickFraming.unframe" should {
    "recover exactly the business events, dropping all control events" in {
      val evs = Vector(Ev(t(1)), Ev(t(2)), Ev(t(2)), Ev(t(5)))
      TickFraming.unframe(TickFraming.frame(evs.iterator, 5)).toList shouldBe evs.toList
    }
  }
