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

      "benignly merge a 2-element source and a single-element source" in :
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
