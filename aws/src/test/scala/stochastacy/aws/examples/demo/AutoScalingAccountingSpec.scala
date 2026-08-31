package stochastacy.aws.examples.demo

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.component.Timed
import stochastacy.core.sampler.LogNormalSampler
import stochastacy.core.stream.TickFraming
import stochastacy.sim.*

/** Dynamic capacity → accounting (Slice 3): an auto-scaling table emits its per-tick reserved capacity, and
 *  the accounting bills that runtime trace instead of the static schedule; non-auto-scaling tables byte-identical. */
class AutoScalingAccountingSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("AutoScalingAccountingSpec")
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val rates = Pricing.phase1Default
  private val Table = DynamoDbTarget.Table

  private def tick(t: Long): TimedElement[Timed[DynamoDbConsumption]] = TimedControlEvent.Tick(SimTime.of(t))
  private val eot: TimedElement[Timed[DynamoDbConsumption]]           = TimedControlEvent.EndOfTime
  private def snap(t: Long, r: Long, w: Long): TimedElement[Timed[DynamoDbConsumption]] =
    Timed(ProvisionedCapacitySnapshot(r, w), SimTime.of(t), 0.0, "auto")

  "TrialAccounting with per-tick capacity snapshots" should {

    "bill the reserved capacity from the snapshots, not the initial capacity" in {
      // write capacity ramps 10 → 20 → 40 across three ticks; read holds at 100
      val stream = Vector(
        tick(1), snap(1, 100, 10),
        tick(2), snap(2, 100, 20),
        tick(3), snap(3, 100, 40),
        tick(4), eot
      )
      val (summary, _) = TrialAccounting.account(stream, initialStorageBytes = 0L, rates, billingMode = BillingMode.Provisioned(100, 10))
      summary.totalProvisionedReadCapacityUnitTicks  shouldBe BigInt(300) // 100 + 100 + 100
      summary.totalProvisionedWriteCapacityUnitTicks shouldBe BigInt(70)  // 10 + 20 + 40 (the trace, not 10×3)
    }

    "fall back to the static schedule when no snapshot is present (byte-identical)" in {
      val stream = Vector(tick(1), tick(2), tick(3), tick(4), eot)
      val (summary, _) = TrialAccounting.account(stream, initialStorageBytes = 0L, rates, billingMode = BillingMode.Provisioned(100, 10))
      summary.totalProvisionedReadCapacityUnitTicks  shouldBe BigInt(300) // 100 × 3, the initial reservation
      summary.totalProvisionedWriteCapacityUnitTicks shouldBe BigInt(30)  // 10 × 3
    }
  }

  // --- end-to-end: an auto-scaling table's own emissions feed the accounting ---

  private val putBehavior = new TableBehavior:
    def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
      request match
        case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = None)
        case other                 => throw new IllegalArgumentException(s"unexpected $other")

  private val latency = LogNormalSampler.constant(math.log(0.01), 0.0)

  private val upScalePolicy = AutoScalingPolicy(
    targetUtilization = 0.7, evaluationWindowTicks = 2,
    scaleUpReactionDelayTicks = 1, scaleDownReactionDelayTicks = 1,
    scaleUpCooldownTicks = 1, scaleDownCooldownTicks = 1,
    minReadCapacityUnits = 1, maxReadCapacityUnits = 10000,
    minWriteCapacityUnits = 1, maxWriteCapacityUnits = 10000
  )

  private def config(policy: Option[AutoScalingPolicy]): DynamoDbTable.Config =
    DynamoDbTable.Config(
      initialState = TableSummaryState.empty, behavior = putBehavior, latency = latency,
      billingMode = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 3),
      autoScalingPolicy = policy
    )

  private val Ticks = 8L
  // 3 writes per tick (= the initial write ceiling → utilization 1.0, forcing scale-up)
  private val input: Vector[Timed[DynamoDbRequest]] =
    (1L to Ticks).flatMap(t => Seq(0.1, 0.2, 0.3).map(phi => Timed(PutItemRequest(1024L): DynamoDbRequest, SimTime.of(t), phi, "auto"))).toVector

  private def runConsumption(cfg: DynamoDbTable.Config): Seq[TimedElement[Timed[DynamoDbConsumption]]] =
    val framed = TickFraming.frame(input.iterator, Ticks).toVector
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.seq[TimedElement[Timed[DynamoDbConsumption]]]) { implicit b => consSink =>
        import GraphDSL.Implicits.*
        val td = b.add(DynamoDbTable.componentOf(cfg, RandomSource.KISS.create(1L)))
        b.add(Source(framed)) ~> td.in
        td.out0 ~> b.add(Sink.ignore)
        td.out1 ~> consSink.in
        ClosedShape
      }
    )
    Await.result(graph.run(), 5.seconds)

  private def snapshots(cons: Seq[TimedElement[Timed[DynamoDbConsumption]]]): Seq[ProvisionedCapacitySnapshot] =
    cons.collect { case x: Timed[DynamoDbConsumption] @unchecked => x.event }.collect { case p: ProvisionedCapacitySnapshot => p }

  "An auto-scaling table, end to end," should {

    "emit a growing per-tick capacity snapshot under sustained load" in {
      val snaps = snapshots(runConsumption(config(Some(upScalePolicy))))
      snaps                          should not be empty
      snaps.map(_.writeCapacityUnits).head shouldBe 3L      // starts at the initial ceiling
      snaps.map(_.writeCapacityUnits).max  should be > 3L   // scales up over the run
    }

    "emit no capacity snapshot when there is no policy" in {
      snapshots(runConsumption(config(None))) shouldBe empty
    }

    "bill the accounting for the scaled-up capacity, above the initial reservation" in {
      val cons = runConsumption(config(Some(upScalePolicy)))
      val (summary, _) = TrialAccounting.account(cons, initialStorageBytes = 0L, rates, billingMode = BillingMode.Provisioned(1000, 3))
      // the accounting bills the runtime trace, not the initial reservation: write scaled *up* under load
      // (> 3 × 8), read scaled *down* toward min under zero read load (< 1000 × 8).
      summary.totalProvisionedWriteCapacityUnitTicks should be > BigInt(3 * 8)
      summary.totalProvisionedReadCapacityUnitTicks  should be < BigInt(1000 * 8)
    }
  }
