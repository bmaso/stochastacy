package stochastacy.aws.dynamodb.autoscaling

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{
  AdmissionMetricEvent,
  DynamoDbManagementEvent,
  DynamoDbTable,
  TableMetricEvent
}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class DynamoDbAutoScalerSpec extends AnyWordSpec with should.Matchers:

  given ActorSystem  = ActorSystem("auto-scaler-spec")
  given Materializer = Materializer.matFromSystem
  import scala.concurrent.ExecutionContext.Implicits.global

  private val baseInitialMode = DynamoDbTable.BillingMode.Provisioned(
    readCapacityUnits  = 100L,
    writeCapacityUnits = 100L
  )

  private val basePolicy = DynamoDbAutoScaler.Policy(
    targetUtilization           = 0.70,
    evaluationWindowTicks       = 3,
    scaleUpReactionDelayTicks   = 2,
    scaleDownReactionDelayTicks = 5,
    scaleUpCooldownTicks        = 10,
    scaleDownCooldownTicks      = 20,
    scaleDownThresholdFactor    = 0.5,
    minReadCapacityUnits        = 10L,
    maxReadCapacityUnits        = 1000L,
    minWriteCapacityUnits       = 10L,
    maxWriteCapacityUnits       = 1000L
  )

  private def util(
    tick: Long,
    consumedR: Double,
    provisionedR: Long,
    consumedW: Double = 0.0,
    provisionedW: Long = 100L
  ): TimedElement[TableMetricEvent] =
    AdmissionMetricEvent.ProvisionedCapacityUtilization(
      eventTime                    = SimTime.of(tick),
      usecase                      = "test",
      consumedReadUnits            = BigDecimal(consumedR),
      consumedWriteUnits           = BigDecimal(consumedW),
      provisionedReadCapacityUnits = provisionedR,
      provisionedWriteCapacityUnits = provisionedW
    )

  private def tick(t: Long): TimedElement[TableMetricEvent] =
    TimedControlEvent.Tick(SimTime.of(t))

  private def billingSnapshot(t: Long, code: Int): TimedElement[TableMetricEvent] =
    AdmissionMetricEvent.BillingModeSnapshot(SimTime.of(t), "test", code)

  /** Runs the auto-scaler with the given metric events and collects all management events. */
  private def runScaler(
    policy: DynamoDbAutoScaler.Policy,
    initialMode: DynamoDbTable.BillingMode.Provisioned,
    metricEvents: List[TimedElement[TableMetricEvent]]
  ): List[TimedElement[DynamoDbManagementEvent]] =
    val scaler = new DynamoDbAutoScaler(policy, initialMode)
    val managementF: Future[Seq[TimedElement[DynamoDbManagementEvent]]] =
      scaler.managementSource.runWith(Sink.seq)
    Source(metricEvents).runWith(scaler.metricSink)
    Await.result(managementF, 10.seconds).toList

  "DynamoDbAutoScaler" should {

    "emit UpdateProvisionedCapacity after window fills above threshold and reaction delay elapses" in {
      // Window size = 3; provisionedR = 100; consumed = 80 → util = 0.80 > 0.70
      val events: List[TimedElement[TableMetricEvent]] = List(
        tick(1),
        util(1, consumedR = 80.0, provisionedR = 100L),
        tick(2),
        util(2, consumedR = 80.0, provisionedR = 100L),
        tick(3),
        util(3, consumedR = 80.0, provisionedR = 100L),
        // window full; decision fires at tick 3 + 2 = 5
        tick(4),
        tick(5),
        tick(6)
      )
      val mgmtEvents = runScaler(basePolicy, baseInitialMode, events)
      val updates = mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e }
      updates should have size 1
      updates.head.newCapacity.readCapacityUnits should be > baseInitialMode.readCapacityUnits
    }

    "emit scale-down UpdateProvisionedCapacity after window fills below threshold" in {
      // Read util = 20/100 = 0.20 < 0.70 * 0.50 = 0.35; scale down
      // Write util = 60/100 = 0.60 — neutral (between scale-down threshold 0.35 and scale-up 0.70)
      // newRCU = ceil(20 / 0.70) = 29; clamped to min 10 → 29 (29 < 100 so fires)
      val events: List[TimedElement[TableMetricEvent]] = List(
        tick(1),
        util(1, consumedR = 20.0, provisionedR = 100L, consumedW = 60.0),
        tick(2),
        util(2, consumedR = 20.0, provisionedR = 100L, consumedW = 60.0),
        tick(3),
        util(3, consumedR = 20.0, provisionedR = 100L, consumedW = 60.0),
        // window full; decision fires at tick 3 + 5 = 8
        tick(4), tick(5), tick(6), tick(7), tick(8), tick(9)
      )
      val mgmtEvents = runScaler(basePolicy, baseInitialMode, events)
      val updates = mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e }
      updates should have size 1
      updates.head.newCapacity.readCapacityUnits should be < baseInitialMode.readCapacityUnits
    }

    "scale-up cooldown prevents re-trigger within cooldown window" in {
      // First scale-up fires at tick 5; cooldown = 10 ticks; second over-threshold window at
      // tick 6 should not trigger because cooldown hasn't elapsed by tick ~8
      val highUtil = (t: Long) => util(t, consumedR = 80.0, provisionedR = 100L)
      val events: List[TimedElement[TableMetricEvent]] =
        List(tick(1), highUtil(1), tick(2), highUtil(2), tick(3), highUtil(3),
             // first decision: fire at tick 5
             tick(4), tick(5),
             // second window (window was flushed; now 3 more ticks of high util)
             tick(6), highUtil(6), tick(7), highUtil(7), tick(8), highUtil(8),
             // potential second fire tick = 8 + 2 = 10 — but cooldown (lastScaleTick=3, cooldown=10)
             // means we can't scale again until tick 3+10=13
             tick(9), tick(10), tick(11))
      val mgmtEvents = runScaler(basePolicy, baseInitialMode, events)
      val updates = mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e }
      // Only the first scale-up should fire; second is blocked by cooldown
      updates.count(_.newCapacity.readCapacityUnits > baseInitialMode.readCapacityUnits) shouldBe 1
    }

    "ignore utilization events when in on-demand mode" in {
      val events: List[TimedElement[TableMetricEvent]] = List(
        billingSnapshot(1, code = 0),  // switch to on-demand
        tick(1),
        util(1, consumedR = 80.0, provisionedR = 100L),
        tick(2),
        util(2, consumedR = 80.0, provisionedR = 100L),
        tick(3),
        util(3, consumedR = 80.0, provisionedR = 100L),
        tick(4), tick(5), tick(6)
      )
      val mgmtEvents = runScaler(basePolicy, baseInitialMode, events)
      mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e } shouldBe Nil
    }

    "clamp new capacity to maxReadCapacityUnits" in {
      // consumed = 900; ceil(900 / 0.70) = 1286 > max 1000 → should emit max 1000
      val highPolicy = basePolicy.copy(maxReadCapacityUnits = 1000L)
      val events: List[TimedElement[TableMetricEvent]] = List(
        tick(1), util(1, consumedR = 900.0, provisionedR = 100L),
        tick(2), util(2, consumedR = 900.0, provisionedR = 100L),
        tick(3), util(3, consumedR = 900.0, provisionedR = 100L),
        tick(4), tick(5), tick(6)
      )
      val mgmtEvents = runScaler(highPolicy, baseInitialMode, events)
      val updates = mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e }
      updates should have size 1
      updates.head.newCapacity.readCapacityUnits shouldBe 1000L
    }

    "clamp new capacity to minReadCapacityUnits" in {
      // Read util = 0/100 = 0.0 < 0.70 * 0.50 = 0.35, so scale-down triggers
      // Write util = 60/100 = 0.60 — neutral; no write scale event
      // ceil(0 / 0.70) = 0 < min 10 → should emit min 10
      val events: List[TimedElement[TableMetricEvent]] = List(
        tick(1), util(1, consumedR = 0.0, provisionedR = 100L, consumedW = 60.0),
        tick(2), util(2, consumedR = 0.0, provisionedR = 100L, consumedW = 60.0),
        tick(3), util(3, consumedR = 0.0, provisionedR = 100L, consumedW = 60.0),
        tick(4), tick(5), tick(6), tick(7), tick(8), tick(9)
      )
      val mgmtEvents = runScaler(basePolicy, baseInitialMode, events)
      val updates = mgmtEvents.collect { case e: DynamoDbManagementEvent.UpdateProvisionedCapacity => e }
      updates should have size 1
      updates.head.newCapacity.readCapacityUnits shouldBe 10L
    }

    "management source completes cleanly after metric stream ends" in {
      // Just verify no deadlock: even with no scale events, managementSource must complete
      val events: List[TimedElement[TableMetricEvent]] = List(
        tick(1), tick(2), tick(3)
      )
      // This would hang indefinitely if queue.complete() were never called
      val completed = scala.util.Try(runScaler(basePolicy, baseInitialMode, events))
      completed.isSuccess shouldBe true
    }

  }
