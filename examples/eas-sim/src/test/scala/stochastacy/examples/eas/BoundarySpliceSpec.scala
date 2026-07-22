package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.boundary.SystemBoundaryStage
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.aws.dynamodb.boundary.DynamoDbBoundaryProtocol
import stochastacy.aws.dynamodb.client.SdkClientStage
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTable}
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.TimedElement
import stochastacy.workload.WorkloadGraph

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * Task 5.1 regression — the EXACT `EasSingleTrialRunner` alerts sub-graph
 * (full workload with derived flows, SdkClientStage, both Broadcast taps),
 * with an identity-configured `SystemBoundaryStage` spliced between the SDK
 * client and the table.  A control variant runs the same graph without the
 * boundary.
 *
 * If the control passes and the boundary variant hangs, the deadlock lives in
 * the boundary splice specifically.
 */
class BoundarySpliceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  given ActorSystem      = ActorSystem("boundary-splice-test")
  given Materializer     = Materializer.matFromSystem
  given ExecutionContext = summon[ActorSystem].dispatcher

  override protected def afterAll(): Unit =
    Await.result(summon[ActorSystem].terminate(), 10.seconds)
    super.afterAll()

  private val SimTicks = 30L
  private val config   = EasScenarioConfig(simulationTicks = SimTicks, burstMultiplier = 2.0)

  /** Runs the runner's alerts sub-graph and returns total consumption-event count.
   *  `withBoundary = false` is the control (pre-splice wiring). */
  private def runAlertsGraph(withBoundary: Boolean, seed: Long,
                             simTicks: Long = SimTicks,
                             cfg: EasScenarioConfig = config,
                             timeout: FiniteDuration = 20.seconds): Long =
    val config      = cfg
    val SimTicks    = simTicks
    val masterRng   = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(masterRng.nextLong())
    val samplerRng  = RandomSource.KISS.create(masterRng.nextLong())
    val sdkRng      = RandomSource.KISS.create(masterRng.nextLong())
    val boundaryRng = RandomSource.KISS.create(masterRng.nextLong())

    val sampler   = EasAlertsSampler(config.alertsConfig, samplerRng)
    val tableCfg  = EasTableConfigs.alertsTableConfig(config.alertsConfig, sampler)
    val aw        = config.toAlertsWorkload
    // Await ALL sink futures, exactly like the runner does — a wedged Broadcast
    // branch (e.g. respBcast stalling once WorkloadGraph stops pulling) shows up
    // as a hang here even when table.out1 completes fine.
    val consSink     = Sink.fold[Long, TimedElement[DynamoDbConsumptionEvent]](0L)((n, _) => n + 1L)
    val throttleSink = Sink.fold[Long, TimedElement[DynamoDBResponse]](0L)((n, _) => n + 1L)
    val flowSink     = Sink.fold[Long, TimedElement[DynamoDBRequest]](0L)((n, _) => n + 1L)

    val (consF, throttleF, flowF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consSink, throttleSink, flowSink)((a, b, c) => (a, b, c)) { implicit b =>
        (cs, ts, fs) =>
          import GraphDSL.Implicits.*
          val workloadG = b.add(WorkloadGraph(aw, Map(aw.usecase -> aw), workloadRng, SimTicks))
          val sdkClient = b.add(SdkClientStage.componentOf(
                                  strategy            = config.sdkRetryStrategy,
                                  tickDurationSeconds = 1.0,
                                  rng                 = sdkRng))
          val table     = b.add(DynamoDbTable.componentOf(tableCfg))
          val reqBcast  = b.add(Broadcast[TimedElement[DynamoDBRequest]](2))
          val respBcast = b.add(Broadcast[TimedElement[DynamoDBResponse]](3))

          workloadG.requestOut ~> sdkClient.in0
          sdkClient.out        ~> reqBcast.in
          reqBcast.out(1)      ~> fs                   // flowCountSink

          if withBoundary then
            val boundary = b.add(SystemBoundaryStage.componentOf[
                                   DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
                                   DynamoDbBoundaryProtocol,
                                   SystemBoundaryStage.Config(),
                                   boundaryRng))
            reqBcast.out(0)         ~> boundary.requestIn
            boundary.requestOut     ~> table.in
            table.out0              ~> boundary.responseIn
            boundary.responseOut    ~> respBcast.in
            boundary.consumptionOut ~> b.add(Sink.ignore)
          else
            reqBcast.out(0) ~> table.in
            table.out0      ~> respBcast.in

          respBcast.out(0) ~> sdkClient.in1
          respBcast.out(1) ~> workloadG.responseIn
          respBcast.out(2) ~> ts                       // throttleSink

          table.out1 ~> cs
          table.out2 ~> b.add(Sink.ignore)
          ClosedShape
      }
    ).run()

    val result = Await.result(consF, timeout)
    Await.result(throttleF, timeout)
    Await.result(flowF, timeout)
    result

  /** No-SDK variant: workloadG (independent flows only) → [boundary] → table.
   *  Without the SDK's dual-input interleaving in the loop, the request stream
   *  is fully determined by the workload RNG, so consumption counts must be
   *  EXACTLY equal with and without an identity boundary — this isolates
   *  whether the boundary itself is value-transparent. */
  private def runNoSdkGraph(withBoundary: Boolean, seed: Long): Long =
    val masterRng   = RandomSource.KISS.create(seed)
    val workloadRng = RandomSource.KISS.create(masterRng.nextLong())
    val samplerRng  = RandomSource.KISS.create(masterRng.nextLong())
    val boundaryRng = RandomSource.KISS.create(masterRng.nextLong())

    val sampler  = EasAlertsSampler(config.alertsConfig, samplerRng)
    val tableCfg = EasTableConfigs.alertsTableConfig(config.alertsConfig, sampler)
    val indep    = stochastacy.workload.WorkloadDefinition(
      tableName = "alerts", usecase = "alerts", flows = config.toAlertsWorkload.independentFlows)
    val consSink = Sink.fold[Long, TimedElement[DynamoDbConsumptionEvent]](0L)((n, _) => n + 1L)

    val consF = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consSink) { implicit b =>
        cs =>
          import GraphDSL.Implicits.*
          val wg    = b.add(WorkloadGraph(indep, Map.empty, workloadRng, SimTicks))
          val table = b.add(DynamoDbTable.componentOf(tableCfg))

          if withBoundary then
            val boundary = b.add(SystemBoundaryStage.componentOf[
                                   DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
                                   DynamoDbBoundaryProtocol,
                                   SystemBoundaryStage.Config(),
                                   boundaryRng))
            wg.requestOut           ~> boundary.requestIn
            boundary.requestOut     ~> table.in
            table.out0              ~> boundary.responseIn
            boundary.responseOut    ~> wg.responseIn
            boundary.consumptionOut ~> b.add(Sink.ignore)
          else
            wg.requestOut ~> table.in
            table.out0    ~> wg.responseIn

          table.out1 ~> cs
          table.out2 ~> b.add(Sink.ignore)
          ClosedShape
      }
    ).run()

    Await.result(consF, 20.seconds)

  "the EAS alerts sub-graph" should {

    "terminate without the boundary (control)" in {
      runAlertsGraph(withBoundary = false, seed = 42L) should be > 0L
    }

    "be value-transparent with no SDK in the loop (bit-equal consumption counts)" in {
      val without = runNoSdkGraph(withBoundary = false, seed = 7L)
      val withB   = runNoSdkGraph(withBoundary = true,  seed = 7L)
      without should be > 0L
      withB shouldBe without
    }

    "terminate with an identity boundary spliced between SDK client and table" in {
      runAlertsGraph(withBoundary = true, seed = 42L) should be > 0L
    }

    "produce identical consumption counts with and without the identity boundary" in {
      val without = runAlertsGraph(withBoundary = false, seed = 7L)
      val withB   = runAlertsGraph(withBoundary = true,  seed = 7L)
      withB shouldBe without
    }

    // ── Scale: full 900-tick production config (burst + retry storm) ──

    "be bit-transparent at 900 ticks under the production burst config" in {
      val prodCfg = EasScenarioConfig()   // production defaults: 900 ticks, 7.6x burst
      val without = runAlertsGraph(withBoundary = false, seed = 42L,
        simTicks = 900L, cfg = prodCfg, timeout = 240.seconds)
      val withB   = runAlertsGraph(withBoundary = true, seed = 42L,
        simTicks = 900L, cfg = prodCfg, timeout = 240.seconds)
      without should be > 0L
      withB shouldBe without
    }
  }
