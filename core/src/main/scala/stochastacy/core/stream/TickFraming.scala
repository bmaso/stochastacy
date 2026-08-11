package stochastacy.core.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import stochastacy.sim.*

/** Frames a time-ordered sequence of business events into a protocol-correct timed-event
 *  stream, and the inverse. Extracted from the `ips` `WorkloadRequestStream`, minus request
 *  generation.
 *
 *  A framed stream has the structure: for each tick `t` in `[1, N]`, a `Tick(t)` followed by
 *  that tick's events; then a flush `Tick(N + 1)` to close the last window; then `EndOfTime`
 *  as the terminal sentinel. */
object TickFraming:

  /** Frame `events` over the horizon `[1, simulationTicks]`.
   *
   *  Input contract: `events` are in nondecreasing `eventTime` order with eventTimes in
   *  `[1, simulationTicks]`. An event whose `eventTime` is not reached by any tick in that
   *  range (e.g. `0`, or `> simulationTicks`) is never drained and is effectively dropped;
   *  well-formed callers stay within `[1, N]`. */
  def frame[E <: TimedEvent](events: Iterator[E], simulationTicks: Long): Iterator[TimedElement[E]] =
    val buffered = events.buffered
    (1L to simulationTicks).iterator.flatMap { tick =>
      Iterator.single[TimedElement[E]](TimedControlEvent.Tick(SimTime.of(tick))) ++
        Iterator.unfold(()) { _ =>
          if buffered.hasNext && buffered.head.eventTime.ticks == tick then
            Some((buffered.next(): TimedElement[E]) -> ())
          else None
        }
    } ++ Iterator[TimedElement[E]](
      TimedControlEvent.Tick(SimTime.of(simulationTicks + 1L)),
      TimedControlEvent.EndOfTime
    )

  def frameSource[E <: TimedEvent](
    events:          => Iterator[E],
    simulationTicks: Long
  ): Source[TimedElement[E], NotUsed] =
    Source.fromIterator(() => frame(events, simulationTicks))

  /** Strip all control events, recovering the bare business-event stream. */
  def unframe[E <: TimedEvent](stream: Iterator[TimedElement[E]]): Iterator[E] =
    stream.collect { case e: TimedEvent if !e.isInstanceOf[TimedControlEvent] => e.asInstanceOf[E] }
