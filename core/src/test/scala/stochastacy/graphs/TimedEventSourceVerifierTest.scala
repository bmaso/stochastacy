package stochastacy.graphs

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}
import stochastacy.graphs.TimedEventSourceVerifier.*

class TimedEventSourceVerifierTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll:
  given system: ActorSystem = ActorSystem("ExperimentSystem")
  given materializer: Materializer = Materializer(system)

  override def afterAll(): Unit =
    system.terminate()

  "A TimedEventSourceVerifier" when:
    "verifying a timed event source" should:
      "complete benignly with a valid sequence" in:
        val timedEvents = List(
          TimedEvent.Tick(SimTime.of(10L)),
          TestTimedEvent(1, SimTime.of(10L)),
          TestTimedEvent(2, SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TestTimedEvent(3, SimTime.of(11L)),
          TestTimedEvent(4, SimTime.of(11L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(timedEvents.size)
        timedEvents.foreach(sub.expectNext(_))

        sub.expectComplete()

      "complete benignly with a series of ticks" in :
        val timedEvents = List(
          TimedEvent.Tick(SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TimedEvent.Tick(SimTime.of(12L)),
          TimedEvent.Tick(SimTime.of(13L)),
          TimedEvent.Tick(SimTime.of(14L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(timedEvents.size)
        timedEvents.foreach(sub.expectNext(_))

        sub.expectComplete()

      "fail because the source does not start with a Tick" in:
        val timedEvents = List(
          TestTimedEvent(1, SimTime.of(10L)),
          TestTimedEvent(2, SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TestTimedEvent(3, SimTime.of(11L)),
          TestTimedEvent(4, SimTime.of(11L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(1)
        sub.expectError()

      "fail because a time increment between two consecutive non-ticks" in:
        val timedEvents = List(
          TimedEvent.Tick(SimTime.of(10L)),
          TestTimedEvent(1, SimTime.of(10L)),
          TestTimedEvent(2, SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TestTimedEvent(3, SimTime.of(11L)),
          TestTimedEvent(4, SimTime.of(15L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(6)
        sub.expectNext(timedEvents.head)
        sub.expectNext(timedEvents(1))
        sub.expectNext(timedEvents(2))
        sub.expectNext(timedEvents(3))
        sub.expectNext(timedEvents(4))
        sub.expectError()

      "fail because two consecutive ticks without a time increment" in :
        val timedEvents = List(
          TimedEvent.Tick(SimTime.of(10L)),
          TestTimedEvent(1, SimTime.of(10L)),
          TestTimedEvent(2, SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TimedEvent.Tick(SimTime.of(11L)),
          TestTimedEvent(3, SimTime.of(11L)),
          TestTimedEvent(4, SimTime.of(11L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(5)
        sub.expectNext(timedEvents.head)
        sub.expectNext(timedEvents(1))
        sub.expectNext(timedEvents(2))
        sub.expectNext(timedEvents(3))
        sub.expectError()

      "fail because time increment skipped" in :
        val timedEvents = List(
          TimedEvent.Tick(SimTime.of(10L)),
          TestTimedEvent(1, SimTime.of(10L)),
          TestTimedEvent(2, SimTime.of(10L)),
          TimedEvent.Tick(SimTime.of(15L)),
          TestTimedEvent(3, SimTime.of(15L)),
          TestTimedEvent(4, SimTime.of(15L)))

        val sub = verifyEventSequence(timedEvents)

        sub.request(4)
        sub.expectNext(timedEvents.head)
        sub.expectNext(timedEvents(1))
        sub.expectNext(timedEvents(2))
        sub.expectError()


  private def verifyEventSequence(timedEvents: List[TimedEvent]) = {
    val source = TestSource.probe[TimedEvent]
    val verifiedSource = source.verifyTimedEventSource()

    val (pub, sub) =
      verifiedSource.toMat(TestSink.probe[TimedEvent])(Keep.both)
        .run()

    timedEvents.foreach(pub.sendNext)
    pub.sendComplete()
    sub
  }

  case class TestTimedEvent(id: Int, override val eventTime: SimTime) extends TimedEvent:
    override val usecase: Any = TimedEventUsecase
  
  object TimedEventUsecase