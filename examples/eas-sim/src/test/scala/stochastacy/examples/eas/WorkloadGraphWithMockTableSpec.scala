package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{
  DynamoDBRequest, DynamoDBResponse,
  GetItemRequest, GetItemResponse,
  PutItemRequest, PutItemResponse,
  QueryRequest, QueryResponse,
  UpdateItemRequest, UpdateItemResponse
}
import stochastacy.sim.{TimedControlEvent, TimedElement}
import stochastacy.workload.WorkloadGraph

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * Suite 4 — WorkloadGraph (full derived flows: A1 IIR retry + A2 FIR follow-on) wired
 * to a trivial mock "table" instead of the real DynamoDbTable.
 *
 * The mock table is a Flow that synchronously converts each DynamoDBRequest to a
 * plausible DynamoDBResponse (QueryResponse for queries, PutItemResponse for puts,
 * GetItemResponse for gets) and passes TimedControlEvent elements through unchanged.
 * It never throttles, so only the A2 FollowOn path fires (not A1 retry).
 *
 * PURPOSE OF THIS TEST
 * --------------------
 * By replacing the real table with a zero-latency synchronous mock we isolate the
 * question: "does the WorkloadGraph feedback cycle itself deadlock, independent of the
 * real table's internal back-pressure?"
 *
 * Expected outcome: this test HANGS (throws TimeoutException) because MergePreferred
 * gives unconditional priority to the base workload stream, so transformer.out is never
 * pulled from, which back-pressures the entire cycle.
 *
 * Once the fix (async boundary inside WorkloadGraph) is applied, this test should pass.
 */
class WorkloadGraphWithMockTableSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  given ActorSystem     = ActorSystem("workload-graph-mock-table-test")
  given Materializer    = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val SimTicks     = 30L
  private val config       = EasScenarioConfig(simulationTicks = SimTicks, burstMultiplier = 2.0)
  private val fullWorkload = config.toAlertsWorkload               // includes Retry + FollowOn
  private val allWorkloads = Map(fullWorkload.usecase -> fullWorkload)

  /**
   * Synchronous mock table: converts each DynamoDBRequest to a DynamoDBResponse, passes
   * TimedControlEvent elements through unchanged.  Never throttles.
   */
  private val mockTable: Flow[TimedElement[DynamoDBRequest], TimedElement[DynamoDBResponse], NotUsed] =
    Flow[TimedElement[DynamoDBRequest]].map {
      case r: QueryRequest =>
        QueryResponse(
          eventTime          = r.eventTime,
          usecase            = r.usecase,
          target             = r.target,
          readConsistency    = r.readConsistency,
          evaluatedItemCount = 1L,
          evaluatedBytes     = 200L,
          returnedItemCount  = 1L,
          returnedBytes      = 200L,
          flowId             = r.flowId
        )
      case r: GetItemRequest =>
        GetItemResponse(
          eventTime = r.eventTime,
          usecase   = r.usecase,
          itemFound = true,
          itemBytes = Some(200L),
          flowId    = r.flowId
        )
      case r: PutItemRequest =>
        PutItemResponse(
          eventTime         = r.eventTime,
          usecase           = r.usecase,
          storedItemBytes   = r.itemBytes,
          createdNewItem    = true,
          previousItemBytes = None,
          flowId            = r.flowId
        )
      case r: UpdateItemRequest =>
        UpdateItemResponse(
          eventTime         = r.eventTime,
          usecase           = r.usecase,
          storedItemBytes   = r.itemBytes,
          createdNewItem    = false,
          previousItemBytes = Some(r.itemBytes),
          flowId            = r.flowId
        )
      case ctrl: TimedControlEvent =>
        // Pass control events (Tick, EndOfTime) through unchanged.
        // TimedControlEvent <: TimedElement[DynamoDBResponse] by the union-type definition.
        ctrl
    }

  "WorkloadGraph with full derived flows and a synchronous mock table" should {

    /**
     * This test is expected to FAIL with TimeoutException until the async-boundary fix
     * is applied inside WorkloadGraph.
     *
     * Topology:
     *   wg.requestOut → Broadcast(2) → mockTable → wg.responseIn
     *                               └→ Sink.ignore (tap so we have something to await)
     *
     * The Broadcast tap gives us a Future to await without disturbing the cycle.
     * Sink.ignore always demands, so it does not contribute to the deadlock.
     */
    "terminate within the timeout (EXPECTED TO FAIL until async fix is applied)" in {
      val workloadRng = RandomSource.KISS.create(42L)

      val tapSink = Sink.ignore

      val doneF = RunnableGraph.fromGraph(
        GraphDSL.createGraph(tapSink) { implicit b =>
          tap =>
            import GraphDSL.Implicits.*
            val wg       = b.add(WorkloadGraph(fullWorkload, allWorkloads, workloadRng, SimTicks))
            val mock     = b.add(mockTable)
            val reqBcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](2))

            // wg produces requests → broadcast to mock AND to tap sink
            wg.requestOut    ~> reqBcast.in
            reqBcast.out(0)  ~> mock.in
            reqBcast.out(1)  ~> tap          // Sink.ignore: always demands, never blocks

            // mock produces responses → back into wg
            mock.out         ~> wg.responseIn

            ClosedShape
        }
      ).run()

      // 5-second timeout: short enough to fail fast, long enough for the graph to
      // complete if the async fix is in place.
      Await.result(doneF, 5.seconds)
    }

    /**
     * Once the async fix is applied this second test verifies that derived-flow requests
     * (a2-fetch) are actually emitted into the cycle — i.e., the FollowOnTransformerStage
     * is doing its job.
     */
    "emit a2-fetch GetItem requests as follow-on to successful a1-poll queries" in {
      val workloadRng = RandomSource.KISS.create(42L)

      // Collect all requests that pass through the cycle
      val reqSeqSink = Sink.seq[TimedElement[DynamoDBRequest]]

      val requestsF = RunnableGraph.fromGraph(
        GraphDSL.createGraph(reqSeqSink) { implicit b =>
          reqSink =>
            import GraphDSL.Implicits.*
            val wg       = b.add(WorkloadGraph(fullWorkload, allWorkloads, workloadRng, SimTicks))
            val mock     = b.add(mockTable)
            val reqBcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](2))

            wg.requestOut   ~> reqBcast.in
            reqBcast.out(0) ~> mock.in
            reqBcast.out(1) ~> reqSink

            mock.out        ~> wg.responseIn

            ClosedShape
        }
      ).run()

      val requests = Await.result(requestsF, 5.seconds)

      val a2Fetches = requests.collect {
        case r: DynamoDBRequest if r.flowId.contains("a2-fetch") => r
      }
      a2Fetches should not be empty

      // a1-retry should be empty because the mock never throttles
      val a1Retries = requests.collect {
        case r: DynamoDBRequest if r.flowId.contains("a1-retry") => r
      }
      a1Retries shouldBe empty
    }
  }
