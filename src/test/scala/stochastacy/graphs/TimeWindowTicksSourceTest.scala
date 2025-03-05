package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}
import stochastacy.{TimeWindow, WindowSize}
import stochastacy.graphs.TimedEventSourceVerifier.*

class TimeWindowTicksSourceTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll:
  given system: ActorSystem = ActorSystem("ExperimentSystem")
  given materializer: Materializer = Materializer(system)

  override def afterAll(): Unit =
    system.terminate()

  "A TimeWindowTicksSource" should:
    "be a valid timed event source" in:
      val source = TimeWindowTicksSource(TimeWindow(WindowSize.`10s`, 0L)).map(_._1)
      val verifiedSource = source.verifyTimedEventSource(clockIncrement = 1000L)

      val sub = verifiedSource.runWith(TestSink.probe[TimedEvent])

      sub.request(10)
      sub.expectNext(TimedEvent.Tick(0L))
      sub.expectNext(TimedEvent.Tick(1000L))
      sub.expectNext(TimedEvent.Tick(2000L))
      sub.expectNext(TimedEvent.Tick(3000L))
      sub.expectNext(TimedEvent.Tick(4000L))
      sub.expectNext(TimedEvent.Tick(5000L))
      sub.expectNext(TimedEvent.Tick(6000L))
      sub.expectNext(TimedEvent.Tick(7000L))
      sub.expectNext(TimedEvent.Tick(8000L))
      sub.expectNext(TimedEvent.Tick(9000L))

      sub.expectComplete()

    "increment tick indexes and compute totals as expected" in:
      val timeWindow = TimeWindow(WindowSize.`10s`, 0L)
      val source: Source[(Int, Long, TimeWindow), NotUsed] = TimeWindowTicksSource(timeWindow)
        .map(t => (t._2, t._3, t._4))
      val sub = source.runWith(TestSink.probe[(Int, Long, TimeWindow)])

      sub.request(11) // ...need to ask for 1 more than the amount when pulling straight from a raw source...
      sub.expectNext((0, 10L, timeWindow))
      sub.expectNext((1, 10L, timeWindow))
      sub.expectNext((2, 10L, timeWindow))
      sub.expectNext((3, 10L, timeWindow))
      sub.expectNext((4, 10L, timeWindow))
      sub.expectNext((5, 10L, timeWindow))
      sub.expectNext((6, 10L, timeWindow))
      sub.expectNext((7, 10L, timeWindow))
      sub.expectNext((8, 10L, timeWindow))
      sub.expectNext((9, 10L, timeWindow))

      sub.expectComplete()

