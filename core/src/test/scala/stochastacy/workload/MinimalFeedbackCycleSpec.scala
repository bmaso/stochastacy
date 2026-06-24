package stochastacy.workload

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * Minimal reproducing test for the feedback-cycle deadlock.
 *
 * ── Graph topology under test (Variants A–E) ────────────────────────────────
 *
 *   Source(1 to 5) ──→ Merge.in(0)
 *                      Merge.out ──→ Broadcast(2)
 *                                     out(0) ──→ Sink.seq        (collect results)
 *                                     out(1) ──→ filter(_ < 100)
 *                                                map(_ + 100) ──→ Merge.in(1)
 *
 * Termination logic:
 *   - Base elements 1..5 (all < 100) pass the filter and generate derived elements 101..105.
 *   - Derived elements 101..105 (all >= 100) fail the filter; no further feedback.
 *   - Expected result: {1,2,3,4,5,101,102,103,104,105} in some order — sum = 530.
 *
 * ── Root cause of the deadlock ───────────────────────────────────────────────
 *
 * ALL variants A–E deadlock. The issue is a CIRCULAR COMPLETION DEPENDENCY,
 * not a back-pressure cycle:
 *
 *   1. Source(1 to 5) completes -> sends completion to Merge.in(0).
 *   2. Merge (eagerComplete=false) says "I won't complete until in(1) completes."
 *   3. Merge.in(1) = filter output -> waits for Broadcast.out(1) to complete.
 *   4. Broadcast.out(1) completes when Broadcast.in completes.
 *   5. Broadcast.in = Merge.out -> completes when Merge completes.  CIRCLE.
 *
 * Adding .async boundaries gives each stage its own actor-mailbox buffer,
 * which lets elements flow concurrently -- but it does NOT change the completion
 * signal path.  When every derived element has been processed, the filter stage
 * is idle and waiting for more input.  That input can only arrive via
 * Broadcast.out(1), which is still waiting for Broadcast.in to complete, which
 * depends on Merge.out, which won't complete until Merge.in(1) completes.
 *
 * Adding a dropHead buffer breaks back-pressure propagation around the cycle,
 * preventing a back-pressure deadlock -- but the completion deadlock remains
 * because the buffer stage itself never receives a completion signal (same cycle).
 *
 * Conclusion: no standard Pekko combinator (async, buffer, MergePreferred, ...)
 * can fix the circular completion dependency.  The graph cycle itself must go.
 *
 * ── The fix: Variant F ────────────────────────────────────────────────────────
 *
 * Variant F replaces the closed cycle with a single Source.unfold that holds
 * the pending-derived-element queue as state.  There is no Merge, no Broadcast,
 * no completion cycle.  This is the pattern WorkloadRequestBusStage applies.
 */
class MinimalFeedbackCycleSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  given ActorSystem     = ActorSystem("minimal-feedback-cycle-spec")
  given Materializer    = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val N           = 5
  private val Timeout     = 5.seconds
  private val ExpectedSum = (1 to N).sum + (101 to (100 + N)).sum  // 15 + 515 = 530

  // ── Variant A: fully synchronous — EXPECTED TO DEADLOCK ─────────────────

  "Variant A — synchronous Merge + synchronous feedback (circular completion)" should {
    "DEADLOCK — timeout expected" in {
      val resultSink = Sink.seq[Int]

      val f = RunnableGraph.fromGraph(
        GraphDSL.createGraph(resultSink) { implicit b =>
          sink =>
            import GraphDSL.Implicits.*
            val src    = b.add(Source(1 to N))
            val merge  = b.add(Merge[Int](2, eagerComplete = false))
            val bcast  = b.add(Broadcast[Int](2))
            val filter = b.add(Flow[Int].filter(_ < 100).map(_ + 100))

            src.out      ~> merge.in(0)
            merge.out    ~> bcast.in
            bcast.out(0) ~> sink
            bcast.out(1) ~> filter ~> merge.in(1)
            ClosedShape
        }
      ).run()

      // Documented baseline: this WILL timeout.
      assertThrows[java.util.concurrent.TimeoutException] {
        Await.result(f, Timeout)
      }
    }
  }

  // ── Variants B–E: "obvious" fixes that still deadlock ───────────────────
  //
  // Each variant adds async boundaries or drop buffers.  All still deadlock
  // because the circular completion dependency is unchanged.  Preserved here
  // as documentation showing that standard Pekko combinators cannot fix this.

  "Variant B — async on feedback flow only (still deadlocks)" should {
    "DEADLOCK — timeout expected" in {
      val resultSink = Sink.seq[Int]
      val f = RunnableGraph.fromGraph(
        GraphDSL.createGraph(resultSink) { implicit b =>
          sink =>
            import GraphDSL.Implicits.*
            val src    = b.add(Source(1 to N))
            val merge  = b.add(Merge[Int](2, eagerComplete = false))
            val bcast  = b.add(Broadcast[Int](2))
            val filter = b.add(Flow[Int].filter(_ < 100).map(_ + 100).async)

            src.out      ~> merge.in(0)
            merge.out    ~> bcast.in
            bcast.out(0) ~> sink
            bcast.out(1) ~> filter ~> merge.in(1)
            ClosedShape
        }
      ).run()
      assertThrows[java.util.concurrent.TimeoutException] { Await.result(f, Timeout) }
    }
  }

  "Variant C — async on source AND async on feedback (still deadlocks)" should {
    "DEADLOCK — timeout expected" in {
      val resultSink = Sink.seq[Int]
      val f = RunnableGraph.fromGraph(
        GraphDSL.createGraph(resultSink) { implicit b =>
          sink =>
            import GraphDSL.Implicits.*
            val src    = b.add(Source(1 to N).async)
            val merge  = b.add(Merge[Int](2, eagerComplete = false))
            val bcast  = b.add(Broadcast[Int](2))
            val filter = b.add(Flow[Int].filter(_ < 100).map(_ + 100).async)

            src.out      ~> merge.in(0)
            merge.out    ~> bcast.in
            bcast.out(0) ~> sink
            bcast.out(1) ~> filter ~> merge.in(1)
            ClosedShape
        }
      ).run()
      assertThrows[java.util.concurrent.TimeoutException] { Await.result(f, Timeout) }
    }
  }

  "Variant D — dropHead buffer on feedback path (still deadlocks)" should {
    "DEADLOCK — timeout expected" in {
      val resultSink = Sink.seq[Int]
      val f = RunnableGraph.fromGraph(
        GraphDSL.createGraph(resultSink) { implicit b =>
          sink =>
            import GraphDSL.Implicits.*
            val src    = b.add(Source(1 to N))
            val merge  = b.add(Merge[Int](2, eagerComplete = false))
            val bcast  = b.add(Broadcast[Int](2))
            val filter = b.add(
              Flow[Int].filter(_ < 100).map(_ + 100).buffer(100, OverflowStrategy.dropHead)
            )

            src.out      ~> merge.in(0)
            merge.out    ~> bcast.in
            bcast.out(0) ~> sink
            bcast.out(1) ~> filter ~> merge.in(1)
            ClosedShape
        }
      ).run()
      assertThrows[java.util.concurrent.TimeoutException] { Await.result(f, Timeout) }
    }
  }

  "Variant E — dropHead + async on source + async on feedback (still deadlocks)" should {
    "DEADLOCK — timeout expected" in {
      val resultSink = Sink.seq[Int]
      val f = RunnableGraph.fromGraph(
        GraphDSL.createGraph(resultSink) { implicit b =>
          sink =>
            import GraphDSL.Implicits.*
            val src    = b.add(Source(1 to N).async)
            val merge  = b.add(Merge[Int](2, eagerComplete = false))
            val bcast  = b.add(Broadcast[Int](2))
            val filter = b.add(
              Flow[Int].filter(_ < 100).map(_ + 100).buffer(100, OverflowStrategy.dropHead).async
            )

            src.out      ~> merge.in(0)
            merge.out    ~> bcast.in
            bcast.out(0) ~> sink
            bcast.out(1) ~> filter ~> merge.in(1)
            ClosedShape
        }
      ).run()
      assertThrows[java.util.concurrent.TimeoutException] { Await.result(f, Timeout) }
    }
  }

  // ── Variant F: correct fix — no graph cycle ──────────────────────────────
  //
  // Use Source.unfold to hold the pending-derived-element queue as state.
  // There is no Merge, no Broadcast, no completion cycle.  The stream
  // terminates when the state queue drains to empty.
  //
  // This is the same principle used by WorkloadRequestBusStage: instead of a
  // cycle, manage base + derived elements as internal stage state and call
  // complete(outlet) when the natural termination condition is met.

  "Variant F — Source.unfold with internal derived queue (no cycle)" should {
    "terminate within timeout and produce the correct sum" in {

      // Each unfold step dequeues one element and, if it satisfies the
      // feedback condition, enqueues a derived element.  No graph cycle.
      val initial = mutable.Queue.from(1 to N)

      val resultF = Source.unfold(initial) { q =>
        if q.isEmpty then None
        else
          val n = q.dequeue()
          if n < 100 then q.enqueue(n + 100)   // derived element
          Some((q, n))
      }.runWith(Sink.seq)

      val results = Await.result(resultF, Timeout)
      results.sum shouldBe ExpectedSum
    }
  }
