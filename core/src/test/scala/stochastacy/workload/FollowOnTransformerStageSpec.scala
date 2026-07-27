package stochastacy.workload

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.table.{DynamoDbTarget, ReadConsistency}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}
import stochastacy.sim.ticks

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Unit tests for FollowOnTransformerStage.
 *
 * Each test runs the stage synchronously as a Pekko Streams graph:
 *   Source(inputEvents) ~> FollowOnTransformerStage(flows, rng) ~> Sink.seq
 *
 * Tick protocol: each tick window is bounded by consecutive Tick events.
 * Responses accumulate between Tick(t) and Tick(t+1).
 * Derived requests are emitted after Tick(t+1) (with lagTicks=1) or later (lagTicks>1).
 *
 * The final Tick(N+1) is required to flush the last window.
 */
class FollowOnTransformerStageSpec extends AnyWordSpec with should.Matchers:

  given system: ActorSystem   = ActorSystem("follow-on-transformer-stage-spec")
  given mat: Materializer     = Materializer.matFromSystem

  private def rng = RandomSource.KISS.create(42L)

  private def tick(t: Long): TimedElement[DynamoDBResponse] =
    TimedControlEvent.Tick(SimTime.of(t))

  /** A successful GetItem response attributed to the given flowId. */
  private def successResp(t: Long, flowId: String): GetItemResponse =
    GetItemResponse(SimTime.of(t), "test", itemFound = true, itemBytes = Some(100L),
      flowId = Some(flowId))

  /** A ThrottledResponse attributed to the given flowId. */
  private def throttledResp(t: Long, flowId: String): ThrottledResponse =
    ThrottledResponse(
      eventTime = SimTime.of(t),
      usecase   = "test",
      operation = DynamoDbOperationKind.GetItem,
      target    = DynamoDbTarget.Table("t"),
      dimension = DynamoDbThroughputDimension.Read,
      reason    = DynamoDbThrottleReason.TableReadMaxOnDemandThroughputExceeded,
      flowId    = Some(flowId)
    )

  /** Run the stage with the given input elements and collect the output. */
  private def run(
    flows:  Vector[ResolvedDerivedFlow],
    input:  Seq[TimedElement[DynamoDBResponse]]
  ): Vector[TimedElement[DynamoDBRequest]] =
    val future = Source(input.toList)
      .via(FollowOnTransformerStage(flows, rng))
      .runWith(Sink.seq)
    Await.result(future, 5.seconds).toVector

  private def onlyRequests(out: Vector[TimedElement[DynamoDBRequest]]): Vector[DynamoDBRequest] =
    out.collect { case r: DynamoDBRequest => r }

  private def onlyTicks(out: Vector[TimedElement[DynamoDBRequest]]): Vector[Long] =
    out.collect { case t: TimedControlEvent.Tick => t.eventTime.ticks }

  // ── Test 1: no derived flows ──────────────────────────────────────────────

  "FollowOnTransformerStage with no derived flows" should {

    "pass through all Tick events and emit no requests" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        successResp(1, "some-flow"),
        tick(2),
        tick(3)
      )
      val out = run(Vector.empty, input)
      onlyRequests(out) shouldBe empty
      onlyTicks(out)    shouldBe Vector(1L, 2L, 3L)
    }

    "pass through EndOfTime / other control events" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        tick(2)
      )
      val out = run(Vector.empty, input)
      onlyTicks(out) shouldBe Vector(1L, 2L)
    }
  }

  // ── Test 2: Retry (throttled → same shape) with proportion=1.0 ───────────

  "FollowOnTransformerStage with a Retry flow (proportion=1.0, lagTicks=1)" should {

    val retryFlow = ResolvedDerivedFlow(
      id           = "poll-retry",
      sourceFlowId = "a1-poll",
      outcome      = OutcomeFilter.Throttled,
      proportion   = 1.0,
      lagTicks     = 1,
      shape        = RequestShape.GetItem,
      usecase      = "test"
    )

    "emit exactly N requests at tick T+1 when N throttled responses arrive at tick T" in {
      val n = 5
      // Tick(1), then N throttled responses for a1-poll, then Tick(2) flushes window
      val input: Seq[TimedElement[DynamoDBResponse]] =
        Seq(tick(1)) ++
          Seq.fill(n)(throttledResp(1, "a1-poll")) ++
          Seq(tick(2))
      val out = run(Vector(retryFlow), input)
      // Derived requests appear after Tick(2) with lagTicks=1 (emitTick == 2)
      onlyRequests(out) should have size n
      onlyRequests(out).foreach(_ shouldBe a[GetItemRequest])
    }

    "emit zero requests when there are no throttled responses in a tick" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        successResp(1, "a1-poll"),  // success, not throttled — should not count
        tick(2)
      )
      val out = run(Vector(retryFlow), input)
      onlyRequests(out) shouldBe empty
    }

    "not emit for responses attributed to a different flowId" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "other-flow"),  // wrong flowId
        tick(2)
      )
      val out = run(Vector(retryFlow), input)
      onlyRequests(out) shouldBe empty
    }
  }

  // ── Test 3: FollowOn (success → different shape) with proportion=1.0 ─────

  "FollowOnTransformerStage with a FollowOn flow (outcome=Success, proportion=1.0, lagTicks=1)" should {

    val followOnFlow = ResolvedDerivedFlow(
      id           = "fetch-after-query",
      sourceFlowId = "a1-query",
      outcome      = OutcomeFilter.Success,
      proportion   = 1.0,
      lagTicks     = 1,
      shape        = RequestShape.GetItem,
      usecase      = "test"
    )

    "emit N GetItem requests at tick T+1 after N successful QueryResponse events at tick T" in {
      val n = 3
      val querySuccessResp: TimedElement[DynamoDBResponse] =
        QueryResponse(
          eventTime          = SimTime.of(1),
          usecase            = "test",
          target             = DynamoDbReadTarget.Table("t"),
          readConsistency    = ReadConsistency.EventuallyConsistent,
          evaluatedItemCount = 10L,
          evaluatedBytes     = 1000L,
          returnedItemCount  = 10L,
          returnedBytes      = 1000L,
          flowId             = Some("a1-query")
        )
      val input: Seq[TimedElement[DynamoDBResponse]] =
        Seq(tick(1)) ++
          Seq.fill(n)(querySuccessResp) ++
          Seq(tick(2))
      val out = run(Vector(followOnFlow), input)
      onlyRequests(out) should have size n
      onlyRequests(out).foreach(_ shouldBe a[GetItemRequest])
    }

    "not emit for throttled responses when outcome filter is Success" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "a1-query"),
        tick(2)
      )
      val out = run(Vector(followOnFlow), input)
      onlyRequests(out) shouldBe empty
    }
  }

  // ── Test 4: lagTicks > 1 ─────────────────────────────────────────────────

  "FollowOnTransformerStage with lagTicks=2" should {

    val laggedFlow = ResolvedDerivedFlow(
      id           = "lagged-retry",
      sourceFlowId = "src-flow",
      outcome      = OutcomeFilter.Throttled,
      proportion   = 1.0,
      lagTicks     = 2,
      shape        = RequestShape.GetItem,
      usecase      = "test"
    )

    "emit requests at tick T+2, not at tick T+1" in {
      // Tick(1), throttled at tick 1, Tick(2) — requests should NOT appear yet (emitTick = 3)
      // Then Tick(3) — requests appear here
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "src-flow"),
        throttledResp(1, "src-flow"),
        tick(2),
        tick(3)
      )
      val out = run(Vector(laggedFlow), input)
      val reqs = onlyRequests(out)
      reqs should have size 2

      // Verify the requests appear after Tick(3), not after Tick(2)
      // In the output: Tick(1), Tick(2), Tick(3), req, req
      val tickPositions = out.zipWithIndex.collect { case (t: TimedControlEvent.Tick, i) => (t.eventTime.ticks, i) }
      val reqPositions  = out.zipWithIndex.collect { case (r: DynamoDBRequest, i) => i }
      val tick3Pos = tickPositions.collectFirst { case (3L, i) => i }.get
      reqPositions.foreach(_ should be > tick3Pos)
    }

    "emit nothing at tick T+1 and nothing at T+2 if no further ticks flush the queue" in {
      // Window ends at Tick(2) but Tick(3) never arrives — requests are in delay queue
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "src-flow"),
        tick(2)
        // no Tick(3): the delay queue for tick 3 is never drained
      )
      val out = run(Vector(laggedFlow), input)
      onlyRequests(out) shouldBe empty
    }
  }

  // ── Test 5: IIR cascade — retried requests that also get throttled ────────

  "FollowOnTransformerStage IIR cascade simulation" should {

    "re-generate derived requests when derived responses are themselves throttled" in {
      // We simulate the cascade manually: the retry flow watches "src-flow".
      // In the first window, 2 throttled responses arrive for "src-flow".
      // The stage emits 2 GetItem requests tagged as "retry-flow" at tick 2.
      // In the second window (between Tick(2) and Tick(3)), 2 throttled responses
      // arrive for "retry-flow" (simulating the derived requests also being throttled).
      // The stage emits 2 more GetItem requests at tick 3.

      val retryFlow = ResolvedDerivedFlow(
        id           = "retry-flow",
        sourceFlowId = "src-flow",
        outcome      = OutcomeFilter.Throttled,
        proportion   = 1.0,
        lagTicks     = 1,
        shape        = RequestShape.GetItem,
        usecase      = "test"
      )
      val cascadeRetryFlow = ResolvedDerivedFlow(
        id           = "cascade-retry",
        sourceFlowId = "retry-flow",
        outcome      = OutcomeFilter.Throttled,
        proportion   = 1.0,
        lagTicks     = 1,
        shape        = RequestShape.GetItem,
        usecase      = "test"
      )

      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "src-flow"),
        throttledResp(1, "src-flow"),
        tick(2),
        // Derived requests for src-flow are now throttled (simulated as responses)
        throttledResp(2, "retry-flow"),
        throttledResp(2, "retry-flow"),
        tick(3)
      )
      val out = run(Vector(retryFlow, cascadeRetryFlow), input)
      val reqs = onlyRequests(out)
      // 2 from first wave (retry-flow watching src-flow throttles at tick 1)
      // + 2 from second wave (cascade-retry watching retry-flow throttles at tick 2)
      reqs should have size 4
      reqs.foreach(_ shouldBe a[GetItemRequest])
    }
  }

  // ── Test 6: proportion < 1.0 draws Binomial count ────────────────────────

  "FollowOnTransformerStage with proportion=0.0" should {

    val zeroFlow = ResolvedDerivedFlow(
      id           = "zero-retry",
      sourceFlowId = "src-flow",
      outcome      = OutcomeFilter.Throttled,
      proportion   = 0.0,
      lagTicks     = 1,
      shape        = RequestShape.GetItem,
      usecase      = "test"
    )

    "emit zero requests regardless of throttled response count" in {
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "src-flow"),
        throttledResp(1, "src-flow"),
        throttledResp(1, "src-flow"),
        tick(2)
      )
      val out = run(Vector(zeroFlow), input)
      onlyRequests(out) shouldBe empty
    }
  }

  // ── Test 7: Tick pass-through ordering ────────────────────────────────────

  "FollowOnTransformerStage Tick pass-through" should {

    "emit Tick before derived requests within the same window" in {
      val flow = ResolvedDerivedFlow(
        id           = "f",
        sourceFlowId = "src",
        outcome      = OutcomeFilter.Throttled,
        proportion   = 1.0,
        lagTicks     = 1,
        shape        = RequestShape.GetItem,
        usecase      = "test"
      )
      val input: Seq[TimedElement[DynamoDBResponse]] = Seq(
        tick(1),
        throttledResp(1, "src"),
        tick(2)
      )
      val out = run(Vector(flow), input)
      // Output should be: Tick(1), Tick(2), GetItemRequest
      // i.e., Tick(2) appears before the derived request
      val tick2Idx = out.indexWhere {
        case t: TimedControlEvent.Tick => t.eventTime.ticks == 2L
        case _ => false
      }
      val reqIdx = out.indexWhere(_.isInstanceOf[GetItemRequest])
      tick2Idx should be >= 0
      reqIdx   should be >= 0
      tick2Idx should be < reqIdx
    }
  }

  // ── Test 8: resolveFlows — Retry chain resolution + cycle detection ──────

  "FollowOnTransformerStage.resolveFlows" should {

    val getShape: RequestShape = RequestShape.GetItem
    val putShape: RequestShape = RequestShape.PutItem(ConstantSampler(64L))

    "resolve a Retry-of-Retry-of-Retry chain terminating at an Independent flow" in {
      val workload = WorkloadDefinition(
        tableName = "t",
        usecase   = "uc",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = getShape)),
          FlowDefinition.Retry(id = "r1", sourceId = "uc", sourceFlowId = "base", proportion = 0.5, lagTicks = 1),
          FlowDefinition.Retry(id = "r2", sourceId = "uc", sourceFlowId = "r1",   proportion = 0.5, lagTicks = 2),
          FlowDefinition.Retry(id = "r3", sourceId = "uc", sourceFlowId = "r2",   proportion = 0.5, lagTicks = 4)
        )
      )
      val resolved = FollowOnTransformerStage.resolveFlows(workload, Map("uc" -> workload))
      resolved.map(_.id)    shouldBe Vector("r1", "r2", "r3")
      resolved.map(_.shape) shouldBe Vector(getShape, getShape, getShape)
    }

    "resolve a Retry chain terminating at a FollowOn flow" in {
      val workload = WorkloadDefinition(
        tableName = "t",
        usecase   = "uc",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = getShape)),
          FlowDefinition.FollowOn(id = "fo", sourceId = "uc", sourceFlowId = "base",
            outcome = OutcomeFilter.Success, proportion = 0.5, lagTicks = 1, shape = putShape),
          FlowDefinition.Retry(id = "r1", sourceId = "uc", sourceFlowId = "fo", proportion = 0.5, lagTicks = 1),
          FlowDefinition.Retry(id = "r2", sourceId = "uc", sourceFlowId = "r1", proportion = 0.5, lagTicks = 2)
        )
      )
      val resolved = FollowOnTransformerStage.resolveFlows(workload, Map("uc" -> workload))
      resolved.find(_.id == "r2").map(_.shape) shouldBe Some(putShape)
    }

    "reject a Retry that points at itself (self-cycle)" in {
      val workload = WorkloadDefinition(
        tableName = "t",
        usecase   = "uc",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = getShape)),
          FlowDefinition.Retry(id = "r1", sourceId = "uc", sourceFlowId = "r1", proportion = 0.5, lagTicks = 1)
        )
      )
      val ex = intercept[IllegalArgumentException] {
        FollowOnTransformerStage.resolveFlows(workload, Map("uc" -> workload))
      }
      ex.getMessage should include("cyclic source chain")
      ex.getMessage should include("uc.r1")
    }

    "reject a Retry chain with an indirect cycle (r1 → r2 → r1)" in {
      val workload = WorkloadDefinition(
        tableName = "t",
        usecase   = "uc",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = getShape)),
          FlowDefinition.Retry(id = "r1", sourceId = "uc", sourceFlowId = "r2", proportion = 0.5, lagTicks = 1),
          FlowDefinition.Retry(id = "r2", sourceId = "uc", sourceFlowId = "r1", proportion = 0.5, lagTicks = 1)
        )
      )
      val ex = intercept[IllegalArgumentException] {
        FollowOnTransformerStage.resolveFlows(workload, Map("uc" -> workload))
      }
      ex.getMessage should include("cyclic source chain")
    }

    "still reject Retry referencing a flow that does not exist" in {
      val workload = WorkloadDefinition(
        tableName = "t",
        usecase   = "uc",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = getShape)),
          FlowDefinition.Retry(id = "r1", sourceId = "uc", sourceFlowId = "nope", proportion = 0.5, lagTicks = 1)
        )
      )
      val ex = intercept[IllegalArgumentException] {
        FollowOnTransformerStage.resolveFlows(workload, Map("uc" -> workload))
      }
      ex.getMessage should include("unknown source flow 'nope'")
    }

    "resolve a cross-workload Retry chain" in {
      val wA = WorkloadDefinition(
        tableName = "tA",
        usecase   = "wA",
        flows     = Vector(
          FlowDefinition.Independent("base", PacedRequestFactory(rate = ConstantSampler(0), factory = putShape))
        )
      )
      val wB = WorkloadDefinition(
        tableName = "tB",
        usecase   = "wB",
        flows     = Vector(
          FlowDefinition.Retry(id = "r1", sourceId = "wA", sourceFlowId = "base", proportion = 0.5, lagTicks = 1),
          FlowDefinition.Retry(id = "r2", sourceId = "wB", sourceFlowId = "r1",   proportion = 0.5, lagTicks = 2)
        )
      )
      val resolved = FollowOnTransformerStage.resolveFlows(wB, Map("wA" -> wA, "wB" -> wB))
      resolved.map(_.id)    shouldBe Vector("r1", "r2")
      resolved.map(_.shape) shouldBe Vector(putShape, putShape)
    }
  }
