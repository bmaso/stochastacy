package stochastacy.workload

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.{
  DynamoDBRequest, DeleteItemRequest, GetItemRequest, PutItemRequest,
  QueryRequest, ScanRequest, TransactGetItemsRequest, TransactWriteItemsRequest,
  UpdateItemRequest, DynamoDbReadTarget
}
import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

class WorkloadRequestStreamSpec extends AnyWordSpec with should.Matchers:

  private def freshRng() = RandomSource.KISS.create(42L)

  private def run(workload: WorkloadDefinition, ticks: Long = 3L) =
    WorkloadRequestStream(workload, freshRng(), ticks).toVector

  private def isTick(e: TimedElement[DynamoDBRequest]): Boolean =
    e.isInstanceOf[TimedControlEvent.Tick]

  private def requests(events: Vector[TimedElement[DynamoDBRequest]]): Vector[DynamoDBRequest] =
    events.collect { case r: DynamoDBRequest => r }

  private def ticks(events: Vector[TimedElement[DynamoDBRequest]]): Vector[TimedControlEvent.Tick] =
    events.collect { case t: TimedControlEvent.Tick => t }

  private val noRequestsWorkload = WorkloadDefinition.ofIndependent(
    tableName = "t",
    usecase   = "test",
    requests  = Vector(PacedRequestFactory.getItem(ConstantSampler(0)))
  )

  // ── Tick framing ───────────────────────────────────────────────────────────

  "WorkloadRequestStream tick framing" should {

    "emit Tick(1) through Tick(N) plus final Tick(N+1) for N simulation ticks" in {
      val ts = ticks(run(noRequestsWorkload, ticks = 3L))
      ts.map(_.eventTime) shouldBe Vector(SimTime.of(1), SimTime.of(2), SimTime.of(3), SimTime.of(4))
    }

    "emit a Tick before any requests for each tick" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.getItem(ConstantSampler(2))))
      val events = run(workload, ticks = 2L)
      // Structure should be: Tick(1), req, req, Tick(2), req, req, Tick(3), EndOfTime
      events(0) shouldBe a[TimedControlEvent.Tick]
      events(3) shouldBe a[TimedControlEvent.Tick]
      events(6) shouldBe a[TimedControlEvent.Tick]
    }

    "produce no requests when rate is 0 for all shapes" in {
      requests(run(noRequestsWorkload)) shouldBe empty
    }
  }

  // ── Request counts ─────────────────────────────────────────────────────────

  "WorkloadRequestStream request counts" should {

    "produce exactly rate requests per tick for a constant rate" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.putItem(ConstantSampler(5), ConstantSampler(100L))))
      val events = run(workload, ticks = 4L)
      // 4 ticks × 5 requests = 20 requests, plus 5 Tick events
      requests(events).size shouldBe 20
    }

    "sum rates across multiple shapes" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test", Vector(
        PacedRequestFactory.putItem(ConstantSampler(3), ConstantSampler(100L)),
        PacedRequestFactory.getItem(ConstantSampler(2))
      ))
      val events = run(workload, ticks = 1L)
      requests(events).size shouldBe 5
    }
  }

  // ── Request types ──────────────────────────────────────────────────────────

  "WorkloadRequestStream request types" should {

    "produce only PutItemRequests for a PutItem shape" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.putItem(ConstantSampler(3), ConstantSampler(64L))))
      requests(run(workload)).foreach(_ shouldBe a[PutItemRequest])
    }

    "produce only GetItemRequests for a GetItem shape" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.getItem(ConstantSampler(3))))
      requests(run(workload)).foreach(_ shouldBe a[GetItemRequest])
    }

    "produce only UpdateItemRequests for an UpdateItem shape" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.updateItem(ConstantSampler(2), ConstantSampler(64L))))
      requests(run(workload)).foreach(_ shouldBe a[UpdateItemRequest])
    }

    "produce only DeleteItemRequests for a DeleteItem shape" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.deleteItem(ConstantSampler(2))))
      requests(run(workload)).foreach(_ shouldBe a[DeleteItemRequest])
    }

    "produce correct mix of types for multiple shapes" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test", Vector(
        PacedRequestFactory.putItem(ConstantSampler(3), ConstantSampler(64L)),
        PacedRequestFactory.getItem(ConstantSampler(2))
      ))
      val rs = requests(run(workload, ticks = 1L))
      rs.count(_.isInstanceOf[PutItemRequest]) shouldBe 3
      rs.count(_.isInstanceOf[GetItemRequest]) shouldBe 2
    }
  }

  // ── Parameter propagation ──────────────────────────────────────────────────

  "WorkloadRequestStream parameter propagation" should {

    "set itemBytes on PutItemRequest from the sampler" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.putItem(ConstantSampler(2), ConstantSampler(512L))))
      requests(run(workload)).foreach {
        case r: PutItemRequest => r.itemBytes shouldBe 512L
        case _ =>
      }
    }

    "set itemBytes on UpdateItemRequest from the sampler" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.updateItem(ConstantSampler(2), ConstantSampler(256L))))
      requests(run(workload)).foreach {
        case r: UpdateItemRequest => r.itemBytes shouldBe 256L
        case _ =>
      }
    }

    "set target and readConsistency on QueryRequest" in {
      val target = DynamoDbReadTarget.GlobalSecondaryIndex("t", "gsi-1")
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.query(ConstantSampler(1), target,
          ReadConsistency.StronglyConsistent)))
      requests(run(workload, ticks = 1L)).foreach {
        case r: QueryRequest =>
          r.target          shouldBe target
          r.readConsistency shouldBe ReadConsistency.StronglyConsistent
        case _ =>
      }
    }

    "set target and readConsistency on ScanRequest" in {
      val target = DynamoDbReadTarget.Table("t")
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.scan(ConstantSampler(1), target)))
      requests(run(workload, ticks = 1L)).foreach {
        case r: ScanRequest =>
          r.target          shouldBe target
          r.readConsistency shouldBe ReadConsistency.EventuallyConsistent
        case _ =>
      }
    }

    "set perItemBytes on TransactWriteItemsRequest from samplers" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.transactWriteItems(
          ConstantSampler(1),
          Vector(ConstantSampler(100L), ConstantSampler(200L))
        )))
      requests(run(workload, ticks = 1L)).foreach {
        case r: TransactWriteItemsRequest => r.perItemBytes shouldBe Vector(100L, 200L)
        case _ =>
      }
    }

    "set itemCount on TransactGetItemsRequest" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.transactGetItems(ConstantSampler(1), itemCount = ConstantSampler(3))))
      requests(run(workload, ticks = 1L)).foreach {
        case r: TransactGetItemsRequest => r.itemCount shouldBe 3
        case _ =>
      }
    }
  }

  // ── usecase and SimTime ────────────────────────────────────────────────────

  "WorkloadRequestStream metadata" should {

    "propagate the workload usecase to all requests" in {
      val workload = WorkloadDefinition.ofIndependent("t", "my-usecase",
        Vector(PacedRequestFactory.putItem(ConstantSampler(2), ConstantSampler(64L))))
      requests(run(workload)).foreach(_.usecase shouldBe "my-usecase")
    }

    "stamp each request with the SimTime of its tick" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.getItem(ConstantSampler(1))))
      val rs = requests(run(workload, ticks = 3L))
      rs.map(_.eventTime) shouldBe Vector(SimTime.of(1), SimTime.of(2), SimTime.of(3))
    }
  }

  // ── Protocol termination ───────────────────────────────────────────────────

  "WorkloadRequestStream protocol termination" should {

    "end with EndOfTime as the absolute last element" in {
      run(noRequestsWorkload).last shouldBe TimedControlEvent.EndOfTime
    }

    "have the final flush Tick immediately before EndOfTime" in {
      val events = run(noRequestsWorkload)
      events(events.size - 2) shouldBe a[TimedControlEvent.Tick]
      events.last              shouldBe TimedControlEvent.EndOfTime
    }

    "end with EndOfTime even when requests are present in the stream" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.getItem(ConstantSampler(3))))
      run(workload, ticks = 5L).last shouldBe TimedControlEvent.EndOfTime
    }
  }

  // ── intraTick arrivals ─────────────────────────────────────────────────────

  "WorkloadRequestStream intraTick arrivals" should {

    "stamp each request with intraTick in [0.0, 1.0)" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.putItem(ConstantSampler(10), ConstantSampler(64L))))
      requests(run(workload, ticks = 5L)).foreach { r =>
        r.intraTick should be >= 0.0
        r.intraTick should be < 1.0
      }
    }

    "produce non-degenerate intraTick values across many requests" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test",
        Vector(PacedRequestFactory.getItem(ConstantSampler(20))))
      val vals = requests(run(workload, ticks = 10L)).map(_.intraTick)
      vals should have size 200
      // 200 Uniform(0,1) draws — probability all are exactly 0.0 is astronomically small
      vals.exists(_ > 0.0) shouldBe true
      vals.forall(_ >= 0.0) shouldBe true
      vals.forall(_ < 1.0)  shouldBe true
    }

    "produce independent intraTick draws for different shapes" in {
      val workload = WorkloadDefinition.ofIndependent("t", "test", Vector(
        PacedRequestFactory.getItem(ConstantSampler(5)),
        PacedRequestFactory.putItem(ConstantSampler(5), ConstantSampler(64L))
      ))
      val rs   = requests(run(workload, ticks = 10L))
      val gets = rs.collect { case r: GetItemRequest => r.intraTick }
      val puts = rs.collect { case r: PutItemRequest => r.intraTick }
      // Each shape uses its own independent RNG, so the sequences differ
      gets should not equal puts
    }

    "control events carry intraTick = 0.0" in {
      val events = run(noRequestsWorkload)
      events.collect { case t: TimedControlEvent => t }.foreach { t =>
        t.intraTick shouldBe 0.0
      }
    }
  }

  // ── Convenience constructors ───────────────────────────────────────────────

  "PacedRequestFactory convenience constructors" should {

    "produce the same result as direct construction" in {
      val direct = PacedRequestFactory(
        rate  = ConstantSampler(5),
        factory = RequestShape.PutItem(ConstantSampler(128L))
      )
      val convenient = PacedRequestFactory.putItem(ConstantSampler(5), ConstantSampler(128L))
      // Compare by running both through the generator and checking output
      val w1 = WorkloadDefinition.ofIndependent("t", "test", Vector(direct))
      val w2 = WorkloadDefinition.ofIndependent("t", "test", Vector(convenient))
      run(w1, ticks = 1L).collect { case r: PutItemRequest => r.itemBytes } shouldBe
        run(w2, ticks = 1L).collect { case r: PutItemRequest => r.itemBytes }
    }
  }
