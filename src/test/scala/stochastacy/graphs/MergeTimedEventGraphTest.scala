package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike

class MergeTimedEventGraphTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll:
  given actorSystem: ActorSystem = ActorSystem("TestSystem")
  given materializer: Materializer = Materializer(actorSystem)

  case class TestEvent(clockTime: Long) extends TimedEvent.UserTimedEvent:
    override val usecase: String = "test"

  override def afterAll(): Unit =
    actorSystem.terminate()

  "A MergeTimedEventGraph" when:
    "merging two valid sources" should:
      "benignly merge two trivial single-element sources" in:
        val tick = TimedEvent.Tick(100L)
        val sourceA: Source[TimedEvent, NotUsed] = Source.single(tick)
        val sourceB: Source[TimedEvent, NotUsed] = Source.single(tick)

        val merged = MergeTimedEventGraph.apply(sourceA, sourceB)
        val verified = TimedEventSourceVerifier.apply(merged)

        val sub = verified.runWith(TestSink.probe[TimedEvent])

        sub.request(1)
        sub.expectNext(tick)
        sub.expectComplete()

      "benignly merge two 2-element sources" in :
        val tick = TimedEvent.Tick(100L)
        val testEvent = TestEvent(100L)
        val sourceA: Source[TimedEvent, NotUsed] = Source(List(tick, testEvent))
        val sourceB: Source[TimedEvent, NotUsed] = Source(List(tick, testEvent))

        val merged = MergeTimedEventGraph.apply(sourceA, sourceB)
        val verified = TimedEventSourceVerifier.apply(merged)

        val sub = verified.runWith(TestSink.probe[TimedEvent])

        sub.request(3)
        sub.expectNext(tick)
        sub.expectNext(testEvent)
        sub.expectNext(testEvent)
        sub.expectComplete()

      "benignly merge a multi-element source and a 2-element source" in :
        val tick = TimedEvent.Tick(100L)
        val testEvent = TestEvent(100L)
        val sourceA: Source[TimedEvent, NotUsed] = Source(List(tick, testEvent))
        val sourceB: Source[TimedEvent, NotUsed] = Source.single(tick)

        val merged = MergeTimedEventGraph.apply(sourceA, sourceB)

        val sub = merged.runWith(TestSink.probe[TimedEvent])

        sub.request(2)
        sub.expectNext(tick)
        sub.expectNext(testEvent)

        sub.expectComplete()

      "merge a 2 sources covering 3 ticks and several events in each time period" in :
        val ticks = List(TimedEvent.Tick(1000L), TimedEvent.Tick(2000L), TimedEvent.Tick(3000L))
        val events = List(TestEvent(1000L), TestEvent(2000L), TestEvent(3000L))
        val sourceA: Source[TimedEvent, NotUsed] = Source(List(
          ticks(0),
          events(0), events(0),
          ticks(1),
          events(1),
          ticks(2),
          events(2), events(2)))
        val sourceB: Source[TimedEvent, NotUsed] = Source(List(
          ticks(0),
          events(0),
          ticks(1),
          events(1), events(1),
          ticks(2),
          events(2)))

        val merged = MergeTimedEventGraph.apply(sourceA, sourceB)

        val sub = merged.runWith(TestSink.probe[TimedEvent])

        sub.request(12)
        sub.expectNext(ticks(0))
        sub.expectNext(events(0))
        sub.expectNext(events(0))
        sub.expectNext(events(0))
        sub.expectNext(ticks(1))
        sub.expectNext(events(1))
        sub.expectNext(events(1))
        sub.expectNext(events(1))
        sub.expectNext(ticks(2))
        sub.expectNext(events(2))
        sub.expectNext(events(2))
        sub.expectNext(events(2))

        sub.expectComplete()

      "merge a 2 sources covering 3 ticks with sparely populated time periods" in :
        val ticks = List(TimedEvent.Tick(1000L), TimedEvent.Tick(2000L), TimedEvent.Tick(3000L))
        val events = List(TestEvent(1000L), TestEvent(2000L), TestEvent(3000L))
        val sourceA: Source[TimedEvent, NotUsed] = Source(List(
          ticks(0),
          ticks(1),
          ticks(2),
          events(2), events(2)))
        val sourceB: Source[TimedEvent, NotUsed] = Source(List(
          ticks(0),
          events(0), events(0),
          ticks(1),
          events(1), events(1),
          ticks(2)))
      
        val merged = MergeTimedEventGraph.apply(sourceA, sourceB)
      
        val sub = merged.runWith(TestSink.probe[TimedEvent])
      
        sub.request(9)
        sub.expectNext(ticks(0))
        sub.expectNext(events(0))
        sub.expectNext(events(0))
        sub.expectNext(ticks(1))
        sub.expectNext(events(1))
        sub.expectNext(events(1))
        sub.expectNext(ticks(2))
        sub.expectNext(events(2))
        sub.expectNext(events(2))
      
        sub.expectComplete()
