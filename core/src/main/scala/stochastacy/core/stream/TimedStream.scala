package stochastacy.core.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.sim.{TimedControlEvent, TimedElement, TimedEvent}

/** Convenience constructors for timed-event streams, primarily for tests and small fixtures.
 *
 *  These build a `Source` of already-formed `TimedElement`s directly; they do NOT frame
 *  (interleave `Tick`/`EndOfTime`). Use [[TickFraming]] to turn a bare sequence of business
 *  events into a protocol-correct framed stream. */
object TimedStream:

  /** An empty-but-valid timed-event stream: no business events, just the terminal sentinel. */
  def empty[E <: TimedEvent]: Source[TimedElement[E], NotUsed] =
    Source.single(TimedControlEvent.EndOfTime)

  def of[E <: TimedEvent](elements: TimedElement[E]*): Source[TimedElement[E], NotUsed] =
    Source(elements.toVector)

  def fromIterator[E <: TimedEvent](elements: => Iterator[TimedElement[E]]): Source[TimedElement[E], NotUsed] =
    Source.fromIterator(() => elements)

  def fromLazyList[E <: TimedEvent](elements: LazyList[TimedElement[E]]): Source[TimedElement[E], NotUsed] =
    Source(elements)
