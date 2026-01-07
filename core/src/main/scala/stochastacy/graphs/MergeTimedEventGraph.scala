package stochastacy.graphs

import org.apache.pekko.stream.*
import org.apache.pekko.stream.stage.*
import org.apache.pekko.stream.scaladsl.*

import scala.collection.mutable

/**
 * Merges two timed event sources into a single source that conforms to the timed event source
 * restrictions.
 *
 * Restrictions on the input sources:
 * * each source must begin with equal `Tick` elements
 * * each subsequent `Tick` element in each source must have a clock value exactly 1
 *   greater than the last `Tick` element in the same stream
 * * this ensures `Tick` elements from both sources can be aligned pairwise
 *
 * Guarantees on the output source:
 * * the output source will be a valid timed event source
 * * it will have a single `Tick` for each `Tick` element in the input sources
 * * the interleaved timed event elements from each source will be interleaved in the
 *   output source, in no particular order
 * * relative order of elements originating in each source will be preserved in the
 *   output source, but elements from each source will be interleaved randomly
 * */
class MergeTimedEventGraph private(bufferSize: Int) extends GraphStage[FanInShape2[TimedEvent, TimedEvent, TimedEvent]]:

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
          push(out, queue.dequeue())
        if (queue.isEmpty && inlet0Closed && inlet1Closed)
          completeStage()

      /**
       * Either input 0 or input 1 has previously provided a `Tick`; pull from unmatched input source
       */
      def tryPull(): Unit =
        if ((unmatchedTick0.isEmpty) && !hasBeenPulled(in0) && !isClosed(in0)) then
          pull(in0)
        if ((unmatchedTick1.isEmpty) && !hasBeenPulled(in1) && !isClosed(in1)) then
          pull(in1)

      /**
       * verifies a received tick is 1 increment greater than the last tick received
       */
      def validateTick(tick: TimedEvent.Tick): Unit =
        lastMatchedTick match
          case Some(prevTick) if tick.eventTime != prevTick.eventTime.nextTime =>
            failStage(new IllegalStateException(s"Invalid Tick sequence: ${prevTick.eventTime} → ${tick.eventTime}"))
          case _ => () // Valid sequence

      /**
       * A pull has been issued on in0, which means there is not an unmatched tick received from in0.
       * 
       * * If a tick is received:
       *   * validate it
       *   * if in1 has an unmatched a tick:
       *     * verify this one matches that one (error out if not)
       *   * store new matched tick
       *   * clear both last unmatched
       *   * enqueue tick
       *   * pull from both inlets if queue is not full
       * * else:
       *   * store unmatched tick
       *   * pull from in1 if queue is not full
       *   * else (not a tick):
       *   * enqueue event
       *   * pull from in0
       *
       * * Either way, try emit
       */
      setHandler(in0, new InHandler:
        override def onPush(): Unit =
          val elem = grab(in0)
          (elem, unmatchedTick1) match
            case (tick: TimedEvent.Tick, Some(unmatchedOtherTick)) if tick.eventTime != unmatchedOtherTick.eventTime =>
              failStage(new IllegalStateException(s"Unmatched tick received from source.in0: ${tick.eventTime} != ${unmatchedOtherTick.eventTime}"))

            case (tick: TimedEvent.Tick, Some(_)) =>
              validateTick(tick)
              lastMatchedTick = Some(tick)
              unmatchedTick0 = None
              unmatchedTick1 = None
              queue.enqueue(tick)
              tryEmit()
              tryPull()

            case (tick: TimedEvent.Tick, None) =>
              if (!lastMatchedTick.forall(_.eventTime.nextTime == tick.eventTime))
                failStage(new IllegalStateException(s"Tick increment through source.in0 not a valid increment from last tick: ${lastMatchedTick.get} → ${tick.eventTime}"))
              else
                unmatchedTick0 = Some(tick)
                tryPull()
            // ...do not pull on in0. We have backpressure until we get same tick coming through in1

            case (te: TimedEvent, _) =>
              val tickOpt = unmatchedTick0.orElse(lastMatchedTick)
              if (!tickOpt.forall(_.eventTime == te.eventTime))
                failStage(new IllegalStateException(s"Event increment through source.in0 not a valid increment from last tick: ${tickOpt.get} → ${te.eventTime}"))
              else
                queue.enqueue(te)
                tryEmit()
                tryPull()

        override def onUpstreamFinish(): Unit =
          inlet0Closed = true
          tryEmit()

        )

        /**
         * A pull has been issued on in1, which means there is not an unmatched tick received from in1.
         *
         * * If a tick is received:
         *   * validate it
         *   * if in0 has an unmatched a tick:
         *     * verify this one matches that one (error out if not)
         *   * store new matched tick
         *   * clear both last unmatched
         *   * enqueue tick
         *   * pull from both inlets if queue is not full
         * * else:
         *   * store unmatched tick
         *   * pull from in0 if queue is not full
         *     * else (not a tick):
         *   * enqueue event
         *   * pull from in1
         *
         * * Either way, try emit
         */
        setHandler(in1, new InHandler:
          override def onPush(): Unit =
            val elem = grab(in1)
            (elem, unmatchedTick0) match
              case (tick: TimedEvent.Tick, Some(unmatchedOtherTick)) if tick.eventTime != unmatchedOtherTick.eventTime =>
                failStage(new IllegalStateException(s"Unmatched tick received from source.in1: ${tick.eventTime} != ${unmatchedOtherTick.eventTime}"))

              case (tick: TimedEvent.Tick, Some(_)) =>
                validateTick(tick)
                lastMatchedTick = Some(tick)
                unmatchedTick0 = None
                unmatchedTick1 = None
                queue.enqueue(tick)
                tryEmit()
                tryPull()

              case (tick: TimedEvent.Tick, None) =>
                if (!lastMatchedTick.forall(_.eventTime.nextTime == tick.eventTime))
                  failStage(new IllegalStateException(s"Tick increment through source.in1 not a valid increment from last tick: ${lastMatchedTick.get.eventTime} → ${tick.eventTime}"))
                else
                  unmatchedTick1 = Some(tick)
                  tryPull()
              // ...do not pull on in1. We have backpressure until we get same tick coming through in0

              case (te: TimedEvent, _) =>
                val tickOpt = unmatchedTick1.orElse(lastMatchedTick)
                if (!tickOpt.forall(_.eventTime == te.eventTime))
                  failStage(new IllegalStateException(s"Event through source.in1 clock time does not match last tick: ${tickOpt.get.eventTime} → ${te.eventTime}"))
                else
                  queue.enqueue(te)
                  tryEmit()
                  tryPull()

          override def onUpstreamFinish(): Unit =
            inlet1Closed = true
            tryEmit()
  
          )
  
          setHandler(out, new OutHandler:
          override def onPull(): Unit =
            tryEmit()
  
          )

      override def preStart(): Unit =
        tryPull()

object MergeTimedEventGraph:

  extension [MatT](source1: Source[TimedEvent, MatT])
    def mergeWithTimeEventSource[MatU](source2: Source[TimedEvent, MatU], bufferSize: Int = 10): Source[TimedEvent, (MatT, MatU)] =
      apply(source1, source2, bufferSize)

  def apply[MatT, MatU](source1: Source[TimedEvent, MatT], source2: Source[TimedEvent, MatU], bufferSize: Int = 10): Source[TimedEvent, (MatT, MatU)] =
    Source.fromGraph(GraphDSL.createGraph(source1, source2)(Keep.both) {
      implicit builder =>
        (src1, src2) =>
          import GraphDSL.Implicits._

          val merge = builder.add(new MergeTimedEventGraph(bufferSize))

          src1 ~> merge.in0
          src2 ~> merge.in1

          SourceShape(merge.out)
    })

  def apply[Mat1, Mat2, Mat3](source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2],
                              source3: Source[TimedEvent, Mat3]): Source[TimedEvent, (Mat1, Mat2, Mat3)] =
    apply(source1, apply(source2, source3)).mapMaterializedValue({ case (m1, (m2, m3)) => (m1, m2, m3) })

  def apply[Mat1, Mat2, Mat3, Mat4](source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2],
                                    source3: Source[TimedEvent, Mat3], source4: Source[TimedEvent, Mat4]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4)] =
    apply(apply(source1, source2), apply(source3, source4)).mapMaterializedValue({ case ((m1, m2), (m3, m4)) => (m1, m2, m3, m4) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5](source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2],
                                          source3: Source[TimedEvent, Mat3], source4: Source[TimedEvent, Mat4],
                                          source5: Source[TimedEvent, Mat5]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5)] =
    apply(source1, apply(source2, source3, source4, source5)).mapMaterializedValue({ case (m1, (m2, m3, m4, m5)) => (m1, m2, m3, m4, m5) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6](source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2],
                                          source3: Source[TimedEvent, Mat3], source4: Source[TimedEvent, Mat4],
                                          source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6]):
        Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6)] =
    apply(apply(source1, source2), apply(source3, source4, source5, source6)).mapMaterializedValue({ case ((m1, m2), (m3, m4, m5, m6)) => (m1, m2, m3, m4, m5, m6) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7)] =

    apply(apply(source1, source2, source3), apply(source4, source5, source6, source7))
      .mapMaterializedValue({ case ((m1, m2, m3), (m4,  m5, m6, m7)) => (m1, m2, m3, m4, m5, m6, m7) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8)] =
    
    apply(apply(source1, source2, source3, source4), apply(source5, source6, source7, source8))
      .mapMaterializedValue({ case ((m1, m2, m3, m4), (m5, m6, m7, m8)) => (m1, m2, m3, m4, m5, m6, m7, m8) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9)] =
    
    apply(source1, apply(source2, source3, source4, source5, source6, source7, source8, source9))
      .mapMaterializedValue({ case (m1, (m2, m3, m4, m5, m6, m7, m8, m9)) => (m1, m2, m3, m4, m5, m6, m7, m8, m9) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10)] =
    
    apply(apply(source1, source2), apply(source3, source4, source5, source6, source7, source8, source9, source10))
      .mapMaterializedValue({ case ((m1, m2), (m3, m4, m5, m6, m7, m8, m9, m10)) => (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11)] =
  
    apply(apply(source1, source2, source3), apply(source4, source5, source6, source7, source8, source9, source10, source11))
      .mapMaterializedValue({ case ((m1, m2, m3), (m4, m5, m6, m7, m8, m9, m10, m11)) => (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12)] =
    
    apply(apply(source1, source2, source3, source4), apply(source5, source6, source7, source8, source9, source10, source11, source12))
      .mapMaterializedValue({
        case ((m1, m2, m3, m4), (m5, m6, m7, m8, m9, m10, m11, m12)) =>
          (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12) })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13]): Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13)] =
    
      apply(apply(source1, source2, source3, source4, source5), apply(source6, source7, source8, source9, source10, source11, source12, source13))
        .mapMaterializedValue({
          case ((m1, m2, m3, m4, m5), (m6, m7, m8, m9, m10, m11, m12, m13)) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14)] =
    
      apply(apply(source1, source2, source3, source4, source5, source6), apply(source7, source8, source9, source10, source11, source12, source13, source14))
        .mapMaterializedValue({
          case ((m1, m2, m3, m4, m5, m6), (m7, m8, m9, m10, m11, m12, m13, m14)) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15)] =
    
      apply(apply(source1, source2, source3, source4, source5, source6, source7), apply(source8, source9, source10, source11, source12, source13, source14, source15))
        .mapMaterializedValue({
          case ((m1, m2, m3, m4, m5, m6, m7), (m8, m9, m10, m11, m12, m13, m14, m15)) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15],
     source16: Source[TimedEvent, Mat16]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16)] =
    
      apply(apply(source1, source2, source3, source4, source5, source6, source7, source8), apply(source9, source10, source11, source12, source13, source14, source15, source16))
        .mapMaterializedValue({
          case ((m1, m2, m3, m4, m5, m6, m7, m8), (m9, m10, m11, m12, m13, m14, m15, m16)) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15],
     source16: Source[TimedEvent, Mat16], source17: Source[TimedEvent, Mat17]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17)] =
    
      apply(source1, apply(apply(source2, source3, source4, source5, source6, source7, source8, source9), apply(source10, source11, source12, source13, source14, source15, source16, source17)))
        .mapMaterializedValue({
          case (m1, ((m2, m3, m4, m5, m6, m7, m8, m9), (m10, m11, m12, m13, m14, m15, m16, m17))) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15],
     source16: Source[TimedEvent, Mat16], source17: Source[TimedEvent, Mat17], source18: Source[TimedEvent, Mat18]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18)] =
    
      apply(apply(source1, source2), apply(apply(source3, source4, source5, source6, source7, source8, source9, source10), apply(source11, source12, source13, source14, source15, source16, source17, source18)))
        .mapMaterializedValue({
          case ((m1, m2), ((m3, m4, m5, m6, m7, m8, m9, m10), (m11, m12, m13, m14, m15, m16, m17, m18))) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17, m18)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18, Mat19]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15],
     source16: Source[TimedEvent, Mat16], source17: Source[TimedEvent, Mat17], source18: Source[TimedEvent, Mat18],
     source19: Source[TimedEvent, Mat19]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18, Mat19)] =
    
      apply(apply(source1, source2, source3), apply(apply(source4, source5, source6, source7, source8, source9, source10, source11), apply(source12, source13, source14, source15, source16, source17, source18, source19)))
        .mapMaterializedValue({
          case ((m1, m2, m3), ((m4, m5, m6, m7, m8, m9, m10, m11), (m12, m13, m14, m15, m16, m17, m18, m19))) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17, m18, m19)
        })

  def apply[Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18, Mat19, Mat20]
    (source1: Source[TimedEvent, Mat1], source2: Source[TimedEvent, Mat2], source3: Source[TimedEvent, Mat3],
     source4: Source[TimedEvent, Mat4], source5: Source[TimedEvent, Mat5], source6: Source[TimedEvent, Mat6],
     source7: Source[TimedEvent, Mat7], source8: Source[TimedEvent, Mat8], source9: Source[TimedEvent, Mat9],
     source10: Source[TimedEvent, Mat10], source11: Source[TimedEvent, Mat11], source12: Source[TimedEvent, Mat12],
     source13: Source[TimedEvent, Mat13], source14: Source[TimedEvent, Mat14], source15: Source[TimedEvent, Mat15],
     source16: Source[TimedEvent, Mat16], source17: Source[TimedEvent, Mat17], source18: Source[TimedEvent, Mat18],
     source19: Source[TimedEvent, Mat19], source20: Source[TimedEvent, Mat20]):
    Source[TimedEvent, (Mat1, Mat2, Mat3, Mat4, Mat5, Mat6, Mat7, Mat8, Mat9, Mat10, Mat11, Mat12, Mat13, Mat14, Mat15, Mat16, Mat17, Mat18, Mat19, Mat20)] =
    
      apply(apply(source1, source2, source3, source4), apply(apply(source5, source6, source7, source8, source9, source10, source11, source12), apply(source13, source14, source15, source16, source17, source18, source19, source20)))
        .mapMaterializedValue({
          case ((m1, m2, m3, m4), ((m5, m6, m7, m8, m9, m10, m11, m12), (m13, m14, m15, m16, m17, m18, m19, m20))) =>
            (m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13, m14, m15, m16, m17, m18, m19, m20)
        })

