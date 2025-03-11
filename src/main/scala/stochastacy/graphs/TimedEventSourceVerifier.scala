package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}

/**
 * This pass-through flow verifies the content of a `Source[TimedEvent]` conforms to the
 * restrictions of the "timed event stream". Specifically:
 *
 * - the first stream element must be a `Tick`
 * - for every pair of elements (prev, next):
 *   - if next is not a `Tick` then it must have the same clock value as prev
 *   - if prev and next have different clock values then
 *     - next must be a tick
 *     - prev and next clock times must differ by exactly the flow `clockIncrement` value (default: 1)
 **/
object TimedEventSourceVerifier:

  def apply[Mat](timedEventSource: Source[TimedEvent, Mat], clockIncrement: Long = 1L): Source[TimedEvent, Mat] =
    timedEventSource.verifyTimedEventSource(clockIncrement)

  /**
   * provides an extension method to `Source[TimedEvent, Mat]`: `verifyTimedEventSource`.
   * `source.verifyTimedEventSource(clockIncrement)` is equivalent to
   * `TimedEventSourceVerifier(source, clockIncrement)`.
   */
  extension [Mat](timedEventSource: Source[TimedEvent, Mat])
    def verifyTimedEventSource(clockIncrement: Long = 1L): Source[TimedEvent, Mat] =
    timedEventSource.concat(Source.single(TimedEvent.EndOfTime))
      .sliding(2)
      .statefulMap(() => false)({
        case (false, Seq(prev, _)) if (prev.usecase != "tick") =>
          throw new IllegalArgumentException("First source element must be a \"tick\"")
        case (false, Seq(prev, next)) if (prev.usecase == "tick") =>
          (true, List(prev, next)) // ...the very first tick and first element are preserved...
        case (true, Seq(prev, TimedEvent.Tick(cl2))) if prev.clockTime >= cl2 =>
          throw new IllegalArgumentException("Rule violation: a clock tick must increase clock time vs previous element")
        case (true, Seq(prev, next)) if prev.clockTime > next.clockTime =>
          throw new IllegalArgumentException("Rule violation: clock time must increase monotonically")
        case (true, Seq(prev, next)) if prev.clockTime != next.clockTime && next.usecase != "!" && next.usecase != "tick" =>
          throw new IllegalArgumentException("Rule violation: a tick must be interleaved between clock time changes")
        case (true, Seq(prev, next)) if prev.clockTime != next.clockTime && prev.clockTime != next.clockTime - clockIncrement && next.usecase != "!" =>
          throw new IllegalArgumentException(s"Rule violation: clock time must increase by no more than $clockIncrement unit(s)")
        case (true, p) => (true, p.tail) // ...drop the first element, use the second...
      }, _ => None)
      .flatMap(Source(_))
      .filterNot(_.usecase == "!")
