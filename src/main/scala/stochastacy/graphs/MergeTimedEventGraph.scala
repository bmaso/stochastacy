package stochastacy.graphs

import org.apache.pekko.stream._
import org.apache.pekko.stream.stage._
import org.apache.pekko.stream.scaladsl._
import scala.collection.mutable

import stochastacy.graphs.TimedEvent

/**
 * Merges two timed event sources into a single source that conforms to the timed event source
 * restrictions.
 *
 * Restrictions on the input sources:
 * * each source must begin with equal `Tick` elements
 * * each subsequent `Tick` element in each source must have a clock value exactly `clockIncrementMs`
 *   greater than the last `Tick` element in the same stream
 *   * this ensures `Tick` elements from both sources can be aligned pairwise
 *
 * Guarantees on the output source:
 * * the output source will be a valid timed event source
 * * it will have a single `Tick` for each `Tick` element in the input sources
 * * the interleaved timed event elements from each source will be interleaved in the
 *   output source, in no particular order
 *   * relative order of elements originating in each source will be preserved in the
 *     output source, but elements from each source will be interleaved randomly
 **/
class MergeTimedEventGraph private (clockIncrementMs: Long, bufferSize: Int) extends GraphStage[FanInShape2[TimedEvent, TimedEvent, TimedEvent]]:

  val in0: Inlet[TimedEvent] = Inlet("source0.in")
  val in1: Inlet[TimedEvent] = Inlet("source1.in")
  val out: Outlet[TimedEvent] = Outlet("merged.out")

  override val shape: FanInShape2[TimedEvent, TimedEvent, TimedEvent] =
    new FanInShape2(in0, in1, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      val queue: mutable.Queue[TimedEvent] = mutable.Queue() // ...buffer waiting to go out...
      var unmatchedTick0: Option[TimedEvent.Tick] = None // ...track last unmatched tick for in0...
      var unmatchedTick1: Option[TimedEvent.Tick] = None // ...track last unmatched tick for in1...
      var lastMatchedTick: Option[TimedEvent.Tick] = None // ...track last tick matched from both in0 & in1...
      var inlet0Closed: Boolean = false
      var inlet1Closed: Boolean = false

      def tryEmit(): Unit =
        if isAvailable(out) && queue.nonEmpty then
          var t = queue.dequeue()
          println(s"Pushing $t")
          push(out, t)
        if(queue.isEmpty && inlet0Closed && inlet1Closed)
          completeStage()

      /**
       * Do not
       */
      def tryPull(): Unit =
        println(s"queue.size: ${queue.size}; bufferSize: $bufferSize")
        println(s"hasBeenPulled(0)? ${hasBeenPulled(in0)}; isClosed(0)? ${isClosed(in0)}")
        println(s"hasBeenPulled(1)? ${hasBeenPulled(in1)} ; isClosed(1)? ${isClosed(in1)}")
        if((unmatchedTick0.isEmpty) && !hasBeenPulled(in0) && !isClosed(in0)) then
          println("Pulling from 0")
          pull(in0)
        if((unmatchedTick1.isEmpty) && !hasBeenPulled(in1) && !isClosed(in1)) then
          println("Pulling from 1")
          pull(in1)

      /**
       * verifies a received tick is 1 increment greater than the last tick received
       */
      def validateTick(tick: TimedEvent.Tick): Unit =
        lastMatchedTick match
          case Some(prevTick) if tick.clockTime != prevTick.clockTime + clockIncrementMs =>
            failStage(new IllegalStateException(s"Invalid Tick sequence: ${prevTick.clockTime} → ${tick.clockTime}"))
          case _ => () // Valid sequence

      /**
       * A pull has been issued on in0, which means there is not an unmatched tick received from in0.
       * * If a tick is received:
       *   * validate it
       *   * if in1 has an unmatched a tick:
       *     * verify this one matches that one (error out if not)
       *     * store new matched tick
       *     * clear both last unmatched
       *     * enqueue tick
       *     * pull from both inlets if queue is not full
       *   * else:
       *     * store unmatched tick
       *     * pull from in1 if queue is not full
       * * else (not a tick):
       *   * enqueue event
       *   * pull from in0
       * * Either way, try emit
       */
      setHandler(in0, new InHandler:
        override def onPush(): Unit =
          val elem = grab(in0)
          println(s"in0.onPush received $elem")
          (elem, unmatchedTick1) match
            case (tick: TimedEvent.Tick, Some(unmatchedOtherTick)) if tick.clockTime != unmatchedOtherTick.clockTime =>
              failStage(new IllegalStateException(s"Unmatched tick received from source.in0: ${tick.clockTime} != ${unmatchedOtherTick.clockTime}"))

            case (tick: TimedEvent.Tick, Some(_)) =>
              validateTick(tick)
              lastMatchedTick = Some(tick)
              unmatchedTick0 = None
              unmatchedTick1 = None
              queue.enqueue(tick)
              tryEmit()
              tryPull()

            case (tick: TimedEvent.Tick, None) =>
              if(!lastMatchedTick.forall(_.clockTime + clockIncrementMs == tick.clockTime))
                failStage(new IllegalStateException(s"Tick increment through source.in0 not a valid increment from last tick: ${lastMatchedTick.get} → ${tick.clockTime}"))
              else
                unmatchedTick0 = Some(tick)
                tryPull()
                // ...do not pull on in0. We have backpressure until we get same tick coming through in1

            case (te: TimedEvent, _) =>
              val tickOpt = unmatchedTick0.orElse(lastMatchedTick)
              if(!tickOpt.forall(_.clockTime == te.clockTime))
                failStage(new IllegalStateException(s"Event increment through source.in0 not a valid increment from last tick: ${tickOpt.get} → ${te.clockTime}"))
              else
                queue.enqueue(te)
                tryEmit()
                tryPull()

        override def onUpstreamFinish(): Unit =
          println("source.in0 upstream finish")
          inlet0Closed = true
          tryEmit())

      /**
       * A pull has been issued on in1, which means there is not an unmatched tick received from in1.
       * * If a tick is received:
       *   * validate it
       *   * if in0 has an unmatched a tick:
       *     * verify this one matches that one (error out if not)
       *     * store new matched tick
       *     * clear both last unmatched
       *     * enqueue tick
       *     * pull from both inlets if queue is not full
       *   * else:
       *     * store unmatched tick
       *     * pull from in0 if queue is not full
       * * else (not a tick):
       *   * enqueue event
       *   * pull from in1
       * * Either way, try emit
       */
      setHandler(in1, new InHandler:
        override def onPush(): Unit =
          val elem = grab(in1)
          println(s"in1.onPush received $elem")
          (elem, unmatchedTick0) match
            case (tick: TimedEvent.Tick, Some(unmatchedOtherTick)) if tick.clockTime != unmatchedOtherTick.clockTime =>
              failStage(new IllegalStateException(s"Unmatched tick received from source.in1: ${tick.clockTime} != ${unmatchedOtherTick.clockTime}"))

            case (tick: TimedEvent.Tick, Some(_)) =>
              validateTick(tick)
              lastMatchedTick = Some(tick)
              unmatchedTick0 = None
              unmatchedTick1 = None
              queue.enqueue(tick)
              tryEmit()
              tryPull()

            case (tick: TimedEvent.Tick, None) =>
              if(!lastMatchedTick.forall(_.clockTime + clockIncrementMs == tick.clockTime))
                failStage(new IllegalStateException(s"Tick increment through source.in1 not a valid increment from last tick: ${lastMatchedTick.get} → ${tick.clockTime}"))
              else
                unmatchedTick0 = Some(tick)
                tryPull()
                // ...do not pull on in1. We have backpressure until we get same tick coming through in0

            case (te: TimedEvent, _) =>
              val tickOpt = unmatchedTick1.orElse(lastMatchedTick)
              if(!tickOpt.forall(_.clockTime == te.clockTime))
                failStage(new IllegalStateException(s"Event through source.in1 clock time does not match last tick: ${tickOpt.get} → ${te.clockTime}"))
              else
                queue.enqueue(te)
                tryEmit()
                tryPull()

        override def onUpstreamFinish(): Unit =
          println("source.in1 upstream finish")
          inlet1Closed = true
          tryEmit())

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          println("onPull...")
          tryEmit())

      override def preStart(): Unit =
        tryPull()

object MergeTimedEventGraph:

  extension [MatT](source1: Source[TimedEvent, MatT])
    def mergeWithTimeEventSource[MatU](source2: Source[TimedEvent, MatU], clockIncrementMs: Long = 1000L, bufferSize: Int = 10): Source[TimedEvent, (MatT, MatU)] =
      apply(source1, source2, clockIncrementMs, bufferSize)

  def apply[MatT, MatU](source1: Source[TimedEvent, MatT], source2: Source[TimedEvent, MatU], clockIncrementMs: Long = 1000L, bufferSize: Int = 10): Source[TimedEvent, (MatT, MatU)] =
    Source.fromGraph(GraphDSL.createGraph(source1, source2)(Keep.both) {
      implicit builder => (src1, src2) =>
        import GraphDSL.Implicits._

        val merge = builder.add(new MergeTimedEventGraph(clockIncrementMs, bufferSize))

        src1 ~> merge.in0
        src2 ~> merge.in1

        SourceShape(merge.out)
    })