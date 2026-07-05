package stochastacy.aws.dynamodb.client

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph}
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.{DynamoDbTarget, ReadConsistency}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, ticks}

import scala.concurrent.duration.*

class SdkClientStageSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("sdk-client-stage-spec")
  given mat: Materializer   = Materializer.matFromSystem

  override protected def afterAll(): Unit =
    scala.concurrent.Await.result(system.terminate(), 10.seconds)
    super.afterAll()

  private val AwsStd: SdkRetryStrategy = SdkRetryStrategy.awsJavaSdkV2Standard
  private val NoRetries: SdkRetryStrategy = SdkRetryStrategy(
    maxAttempts = 1,
    baseBackoff = 100.millis,
    maxBackoff  = 20.seconds
  )

  private def rng(seed: Long = 42L) = RandomSource.KISS.create(seed)

  private def buildGraph(strategy: SdkRetryStrategy, tickDurationSeconds: Double = 1.0, seed: Long = 42L)
    : (org.apache.pekko.stream.testkit.TestPublisher.Probe[TimedElement[DynamoDBRequest]],
       org.apache.pekko.stream.testkit.TestPublisher.Probe[TimedElement[DynamoDBResponse]],
       org.apache.pekko.stream.testkit.TestSubscriber.Probe[TimedElement[DynamoDBRequest]]) =
    RunnableGraph.fromGraph(
      GraphDSL.createGraph(
        TestSource.probe[TimedElement[DynamoDBRequest]],
        TestSource.probe[TimedElement[DynamoDBResponse]],
        TestSink.probe[TimedElement[DynamoDBRequest]]
      )((p, r, s) => (p, r, s)) { implicit b => (primary, resp, sink) =>
        import GraphDSL.Implicits.*
        val stage = b.add(SdkClientStage.componentOf(strategy, tickDurationSeconds, rng(seed)))
        primary.out ~> stage.in0
        resp.out    ~> stage.in1
        stage.out   ~> sink.in
        ClosedShape
      }
    ).run()

  private def req(t: Long, flow: String = "primary"): GetItemRequest =
    GetItemRequest(SimTime.of(t), "test", flowId = Some(flow))

  private def okResp(r: GetItemRequest): GetItemResponse =
    GetItemResponse(
      eventTime       = r.eventTime,
      usecase         = r.usecase,
      itemFound       = true,
      itemBytes       = Some(100L),
      flowId          = r.flowId,
      clientAttempt   = r.clientAttempt,
      originalRequest = Some(r)
    )

  private def throttledResp(r: DynamoDBRequest, attempt: Int = 0): ThrottledResponse =
    ThrottledResponse(
      eventTime       = r.eventTime,
      usecase         = r.usecase,
      operation       = DynamoDbOperationKind.fromRequest(r),
      target          = DynamoDbTarget.Table("t"),
      dimension       = DynamoDbThroughputDimension.Read,
      reason          = DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded,
      flowId          = r.flowId,
      clientAttempt   = attempt,
      originalRequest = Some(r)
    )

  private def tick[T](t: Long): TimedControlEvent.Tick = TimedControlEvent.Tick(SimTime.of(t))

  /** Collect elements from the sink until EndOfTime is received.  Uses expectNext
   *  step-by-step for deterministic collection under async completion. */
  private def collectUntilEndOfTime(
    sink: org.apache.pekko.stream.testkit.TestSubscriber.Probe[TimedElement[DynamoDBRequest]],
    max:  Int = 100
  ): Vector[TimedElement[DynamoDBRequest]] =
    val buf = Vector.newBuilder[TimedElement[DynamoDBRequest]]
    var done = false
    var count = 0
    while !done && count < max do
      val next = sink.expectNext(2.seconds)
      count += 1
      if next == TimedControlEvent.EndOfTime then done = true
      else buf += next
    buf.result()

  // ── Pure helpers ───────────────────────────────────────────────────────────

  "SdkClientStage.rebuildRetry" should {

    "preserve GetItemRequest domain fields" in {
      val orig = GetItemRequest(SimTime.of(1L), "uc", flowId = Some("f"))
      val out  = SdkClientStage.rebuildRetry(orig, SimTime.of(5L), 0.3, 2)
      out shouldBe a[GetItemRequest]
      out.usecase       shouldBe "uc"
      out.flowId        shouldBe Some("f")
      out.eventTime     shouldBe SimTime.of(5L)
      out.intraTick     shouldBe 0.3
      out.clientAttempt shouldBe 2
    }

    "preserve PutItemRequest itemBytes" in {
      val orig = PutItemRequest(SimTime.of(1L), "uc", itemBytes = 4500L)
      val out  = SdkClientStage.rebuildRetry(orig, SimTime.of(5L), 0.0, 1).asInstanceOf[PutItemRequest]
      out.itemBytes shouldBe 4500L
      out.clientAttempt shouldBe 1
    }

    "preserve QueryRequest target and readConsistency" in {
      val orig = QueryRequest(
        SimTime.of(1L), "uc",
        target = DynamoDbReadTarget.GlobalSecondaryIndex("t", "gsi"),
        readConsistency = ReadConsistency.EventuallyConsistent
      )
      val out = SdkClientStage.rebuildRetry(orig, SimTime.of(5L), 0.0, 1).asInstanceOf[QueryRequest]
      out.target          shouldBe DynamoDbReadTarget.GlobalSecondaryIndex("t", "gsi")
      out.readConsistency shouldBe ReadConsistency.EventuallyConsistent
    }

    "preserve TransactWriteItemsRequest perItemBytes" in {
      val orig = TransactWriteItemsRequest(SimTime.of(1L), "uc", perItemBytes = Vector(100L, 200L, 300L))
      val out  = SdkClientStage.rebuildRetry(orig, SimTime.of(5L), 0.0, 2).asInstanceOf[TransactWriteItemsRequest]
      out.perItemBytes  shouldBe Vector(100L, 200L, 300L)
      out.clientAttempt shouldBe 2
    }
  }

  "SdkClientStage.sampleBucket" should {

    "return 0 when u = 0 and bucket 0 has non-zero weight" in {
      SdkClientStage.sampleBucket(Vector(1.0), 0.0) shouldBe 0
      SdkClientStage.sampleBucket(Vector(0.5, 0.5), 0.0) shouldBe 0
    }

    "return last-nonzero index when u = 1.0" in {
      SdkClientStage.sampleBucket(Vector(0.5, 0.5), 0.999) shouldBe 1
      SdkClientStage.sampleBucket(Vector(0.3, 0.4, 0.3), 0.999) shouldBe 2
    }

    "return the correct bucket for u in the middle of a weight range" in {
      // Weights [0.5, 0.5]: u < 0.5 → 0, u >= 0.5 → 1.
      SdkClientStage.sampleBucket(Vector(0.5, 0.5), 0.25) shouldBe 0
      SdkClientStage.sampleBucket(Vector(0.5, 0.5), 0.75) shouldBe 1
    }

    "skip zero-weight leading buckets" in {
      // Weights [0.0, 0.0, 1.0]: any u lands in bucket 2.
      SdkClientStage.sampleBucket(Vector(0.0, 0.0, 1.0), 0.5) shouldBe 2
    }
  }

  // ── Graph behavior — no-throttle pass-through ──────────────────────────────

  "SdkClientStage with no throttled responses" should {

    "pass primary requests through unchanged and emit no retries" in {
      val (pri, resp, sink) = buildGraph(AwsStd)

      sink.request(10)

      val r1 = req(1L, "primary")
      val r2 = req(1L, "primary")
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      pri.sendNext(r2)
      // Send successful responses (not retryable).
      resp.sendNext(okResp(r1))
      resp.sendNext(okResp(r2))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)

      // Combined out = primary in (no retries).
      emitted shouldBe Vector(tick(1), r1, r2, tick(2))
    }
  }

  // ── Graph behavior — maxAttempts cap ───────────────────────────────────────

  "SdkClientStage with maxAttempts = 1 (no retries allowed)" should {

    "not emit a retry even when a retryable failure occurs" in {
      val (pri, resp, sink) = buildGraph(NoRetries)

      sink.request(10)

      val r1 = req(1L, "primary")
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      resp.sendNext(throttledResp(r1, attempt = 0))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)
      emitted shouldBe Vector(tick(1), r1, tick(2))
    }
  }

  "SdkClientStage with maxAttempts = 3" should {

    "not retry a response at attempt = maxAttempts - 1" in {
      val (pri, resp, sink) = buildGraph(AwsStd)  // maxAttempts = 3

      sink.request(10)

      val r1 = req(1L, "primary")
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      // Attempt = 2 (0, 1, 2 = maxAttempts-1) → no next retry.
      resp.sendNext(throttledResp(r1, attempt = 2))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)
      emitted shouldBe Vector(tick(1), r1, tick(2))
    }
  }

  // ── Graph behavior — retryProportion ───────────────────────────────────────

  "SdkClientStage with retryProportion = 0.0" should {

    "not emit any retries even when responses are retryable and under maxAttempts" in {
      val strat = SdkRetryStrategy(3, 100.millis, 20.seconds, retryProportion = 0.0)
      val (pri, resp, sink) = buildGraph(strat)

      sink.request(10)

      val r1 = req(1L, "primary")
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      resp.sendNext(throttledResp(r1, attempt = 0))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)
      emitted shouldBe Vector(tick(1), r1, tick(2))
    }
  }

  // ── Graph behavior — retry rebuild fidelity ────────────────────────────────

  "SdkClientStage retry rebuilding" should {

    "preserve flowId and increment clientAttempt on the retry" in {
      // Strategy: attempts = 3, no jitter, base = 1s, tick = 1s → attempt-1 lands at tick+1 exactly.
      val strat = SdkRetryStrategy(
        maxAttempts = 3, baseBackoff = 1.second, maxBackoff = 8.seconds,
        jitter = JitterStrategy.None, retryProportion = 1.0
      )
      val (pri, resp, sink) = buildGraph(strat)

      sink.request(20)

      val r1 = PutItemRequest(SimTime.of(1L), "uc", itemBytes = 4500L, flowId = Some("upstream"))
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      resp.sendNext(throttledResp(r1, attempt = 0))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(tick(3))
      resp.sendNext(tick(3))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)

      // Sequence: tick(1), r1, tick(2), <retry@2>, tick(3)
      emitted should have length 5
      emitted(0) shouldBe tick(1)
      emitted(1) shouldBe r1
      emitted(2) shouldBe tick(2)
      emitted(3) match
        case retry: PutItemRequest =>
          retry.itemBytes       shouldBe 4500L
          retry.flowId          shouldBe Some("upstream")
          retry.clientAttempt   shouldBe 1
          retry.eventTime.ticks shouldBe 2L
        case other =>
          fail(s"Expected PutItemRequest retry, got: $other")
      emitted(4) shouldBe tick(3)
    }

    "not emit a retry when originalRequest is None" in {
      val (pri, resp, sink) = buildGraph(AwsStd)

      sink.request(10)

      val r1 = req(1L, "primary")
      val orphaned = ThrottledResponse(
        eventTime       = SimTime.of(1L),
        usecase         = "test",
        operation       = DynamoDbOperationKind.GetItem,
        target          = DynamoDbTarget.Table("t"),
        dimension       = DynamoDbThroughputDimension.Read,
        reason          = DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded,
        clientAttempt   = 0,
        originalRequest = None    // no template → no retry possible
      )
      pri.sendNext(tick(1))
      pri.sendNext(r1)
      resp.sendNext(orphaned)
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)
      emitted shouldBe Vector(tick(1), r1, tick(2))
    }
  }

  // ── Graph behavior — tick alignment ────────────────────────────────────────

  "SdkClientStage tick alignment" should {

    "insert a retry with target-tick T between Tick(T) and Tick(T+1) on out" in {
      // No-jitter strategy so retry timing is deterministic.  Attempt 1 → nominal=100ms,
      // bucket 0 clamped to 1 → target tick = failure tick + 1.
      val strat = SdkRetryStrategy(
        3, 100.millis, 20.seconds, jitter = JitterStrategy.None, retryProportion = 1.0
      )
      val (pri, resp, sink) = buildGraph(strat)

      sink.request(20)

      val r1 = req(1L, "primary")
      val r2 = req(2L, "primary")

      pri.sendNext(tick(1))
      pri.sendNext(r1)
      resp.sendNext(throttledResp(r1, attempt = 0))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      pri.sendNext(r2)
      resp.sendNext(okResp(r2))
      resp.sendNext(tick(2))
      pri.sendNext(tick(3))
      resp.sendNext(tick(3))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      val emitted = collectUntilEndOfTime(sink)

      // Expected order: tick(1), r1, tick(2), <retry for r1 at tick 2>, r2, tick(3)
      emitted(0) shouldBe tick(1)
      emitted(1) shouldBe r1
      emitted(2) shouldBe tick(2)
      emitted(3) should matchPattern { case _: GetItemRequest => }
      emitted(3).asInstanceOf[GetItemRequest].clientAttempt shouldBe 1
      emitted(3).asInstanceOf[GetItemRequest].eventTime.ticks shouldBe 2L
      emitted(4) shouldBe r2
      emitted(5) shouldBe tick(3)
    }
  }

  // ── Graph behavior — statistical retry thinning ────────────────────────────

  "SdkClientStage with retryProportion = 0.5" should {

    "emit approximately half the retries (statistical, 3σ bounds)" in {
      // 10,000 throttled responses at attempt=0.  Expected retries ≈ 5000, σ ≈ √(N*p*(1-p)) = 50.
      val strat = SdkRetryStrategy(
        maxAttempts = 3, baseBackoff = 1.second, maxBackoff = 8.seconds,
        jitter = JitterStrategy.None, retryProportion = 0.5
      )
      val (pri, resp, sink) = buildGraph(strat, seed = 12345L)

      val N = 10000
      sink.request((N * 3 + 100).toLong)

      pri.sendNext(tick(1))
      val requests = (1 to N).map(i => req(1L, s"f-$i"))
      requests.foreach(pri.sendNext)
      requests.foreach(r => resp.sendNext(throttledResp(r, attempt = 0)))
      resp.sendNext(tick(1))
      pri.sendNext(tick(2))
      resp.sendNext(tick(2))
      pri.sendNext(tick(3))
      resp.sendNext(tick(3))
      pri.sendNext(TimedControlEvent.EndOfTime)
      resp.sendNext(TimedControlEvent.EndOfTime)
      pri.sendComplete()
      resp.sendComplete()

      // Collect everything until EndOfTime.
      val emitted = collectUntilEndOfTime(sink, max = N * 3 + 20)
      val retries = emitted.collect { case r: GetItemRequest if r.clientAttempt == 1 => r }
      // 3σ tolerance: expected 5000, σ=50, so [4850, 5150].
      retries.size should be >= 4850
      retries.size should be <= 5150
    }
  }
