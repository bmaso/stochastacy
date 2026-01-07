package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import stochastacy.{SimTimeWindow, WindowSize}
import stochastacy.graphs.TimedEventSourceVerifier.*

class TimeWindowTicksSourceTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll:
  given system: ActorSystem = ActorSystem("ExperimentSystem")
  given materializer: Materializer = Materializer(system)

  override def afterAll(): Unit =
    system.terminate()

  "A TimeWindowTicksSource" should:
    "be a valid timed event source" in:
      val source = TimeWindowTicksSource(SimTimeWindow(WindowSize.`10s`, 0L)).map(_._1)
      val verifiedSource = source.verifyTimedEventSource()

      val sub = verifiedSource.runWith(TestSink.probe[TimedEvent])

      sub.request(10)
      sub.expectNext(TimedEvent.Tick(SimTime.of(0L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(1L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(2L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(3L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(4L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(5L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(6L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(7L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(8L)))
      sub.expectNext(TimedEvent.Tick(SimTime.of(9L)))

      sub.expectComplete()

    "increment tick indexes and compute totals as expected" in:
      val timeWindow = SimTimeWindow(WindowSize.`10s`, 0L)
      val source: Source[(Int, Long, SimTimeWindow), NotUsed] = TimeWindowTicksSource(timeWindow)
        .map(t => (t._2, t._3, t._4))
      val sub = source.runWith(TestSink.probe[(Int, Long, SimTimeWindow)])

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

