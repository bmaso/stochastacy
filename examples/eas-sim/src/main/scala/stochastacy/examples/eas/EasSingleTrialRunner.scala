package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import stochastacy.aws.boundary.SystemBoundaryStage
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse, ThrottledResponse}
import stochastacy.aws.dynamodb.boundary.DynamoDbBoundaryProtocol
import stochastacy.aws.dynamodb.client.SdkClientStage
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTable}
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.aws.dynamodb.pricing.DynamoDbPricingRates
import stochastacy.demo.{DemoMetric, SimulationTimeSeriesPoint, SingleTrialRunner, TrialResult, TrialRunConfig, TrialSummaryValue}
import stochastacy.sim.{TimedElement, ticks}
import stochastacy.workload.WorkloadGraph

import scala.concurrent.{ExecutionContext, Future}

/**
 * `SingleTrialRunner[EasScenarioConfig]` for the EAS burst-scenario simulation.
 *
 * Runs two DynamoDB tables in a single Pekko Streams graph:
 *
 *   - **alerts** (feedback loop): `WorkloadGraph` drives A1 polling + A3 writes as independent
 *     flows; `FollowOnTransformerStage` inside `WorkloadGraph` handles A1 IIR retries (on
 *     throttle) and A2 FIR follow-on GetItems (on success). A `Broadcast` tap on the request
 *     outlet counts arrivals per (tick, flowId). A `Broadcast` tap on the response outlet counts
 *     throttled responses per tick.
 *
 *   - **user-alert-status** (open-loop): S1/S2/S3 writes driven directly by
 *     `WorkloadRequestStream` — no derived flows, no feedback.
 *
 * The result is a standard `TrialResult` whose `timeSeries` contains per-tick
 * `SimulationTimeSeriesPoint` entries for all EAS metrics.
 */
final class EasSingleTrialRunner()(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[EasScenarioConfig]:

  import EasSingleTrialRunner.*

  override def runTrial(config: EasScenarioConfig, run: TrialRunConfig): Future[TrialResult] =

    // Derive independent sub-RNGs from the trial seed.
    val masterRng          = RandomSource.KISS.create(run.seed)
    val alertsWorkloadRng  = RandomSource.KISS.create(masterRng.nextLong())
    val alertsSamplerRng   = RandomSource.KISS.create(masterRng.nextLong())
    val alertsSdkClientRng = RandomSource.KISS.create(masterRng.nextLong())
    val uasWorkloadRng     = RandomSource.KISS.create(masterRng.nextLong())
    val uasSamplerRng      = RandomSource.KISS.create(masterRng.nextLong())
    // Derived AFTER the pre-existing five so their seed values are unchanged.
    // The identity-configured boundary draws nothing from it (loss and latency
    // are both off), so trial output is bit-identical to the pre-boundary graph.
    val alertsBoundaryRng  = RandomSource.KISS.create(masterRng.nextLong())

    // Use-case samplers
    val alertsSampler = EasAlertsSampler(config.alertsConfig, alertsSamplerRng)
    val uasSampler    = EasUserAlertStatusSampler(config.uasConfig, uasSamplerRng)

    // DynamoDbTable configs (from EasTableConfigs — unchanged)
    val alertsTableCfg = EasTableConfigs.alertsTableConfig(config.alertsConfig, alertsSampler)
    val uasTableCfg    = EasTableConfigs.uasTableConfig(config.uasConfig, uasSampler)

    // WorkloadDefinitions (from EasScenarioConfig)
    val aw = config.toAlertsWorkload
    val uw = config.toUasWorkload

    // allWorkloads keyed by usecase — needed by resolveFlows to look up Retry source shapes.
    val allWorkloads = Map(aw.usecase -> aw)

    // Materializing sinks
    val alertsConsSink = Sink.fold[ConsPerTick, TimedElement[DynamoDbConsumptionEvent]](Map.empty)(foldCons)
    val uasConsSink    = Sink.fold[ConsPerTick, TimedElement[DynamoDbConsumptionEvent]](Map.empty)(foldCons)
    val throttleSink   = Sink.fold[ThrottlePerTick, TimedElement[DynamoDBResponse]](Map.empty)(foldThrottle)
    val flowCountSink  = Sink.fold[FlowCountPerTick, TimedElement[DynamoDBRequest]](Map.empty)(foldFlowCount)

    val (alertsConsF, uasConsF, throttleF, flowCountF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(alertsConsSink, uasConsSink, throttleSink, flowCountSink)(
        (a, u, t, f) => (a, u, t, f)
      ) { implicit b =>
        (alertsConsSinkShape, uasConsSinkShape, throttleSinkShape, flowCountSinkShape) =>
          import GraphDSL.Implicits.*

          // ── Alerts sub-graph (IIR + FIR feedback loop) ──────────────────
          //
          //   WorkloadGraph                                        [a1-poll, a3-write, a2-fetch]
          //   ├─ requestOut → SdkClientStage.in0
          //   │                     ↓ out              (primary + injected SDK retries)
          //   │              reqBcast(2) → boundary.requestIn → alertsTable.in
          //   │                         ↘ flowCountSink      [client-side: counts every attempt sent]
          //   │
          //   alertsTable.out0 → boundary.responseIn
          //   boundary.responseOut → respBcast(3) → SdkClientStage.in1     [retry decisions]
          //   │                                  → WorkloadGraph.responseIn [a2-fetch decisions]
          //   └─                                → throttleSink              [metrics]
          //
          //   The SystemBoundaryStage models the network between the SDK client and
          //   the table.  Task 5.1: identity Config() — pure pass-through, zero
          //   behavior change.  Task 5.2 moves the config into EasScenarioConfig
          //   and enables loss/budget so the boundary bites during the burst.
          //   Both Broadcast taps sit on the CLIENT side of the boundary:
          //   flow counts = attempts the client sends (even if the network later
          //   drops them); the SDK sees boundary timeouts (needed for retries).
          val workloadG   = b.add(WorkloadGraph(aw, allWorkloads, alertsWorkloadRng, config.simulationTicks))
          val sdkClient   = b.add(SdkClientStage.componentOf(
                                    strategy            = config.sdkRetryStrategy,
                                    tickDurationSeconds = 1.0,
                                    rng                 = alertsSdkClientRng
                                  ))
          val boundary    = b.add(SystemBoundaryStage.componentOf[
                                    DynamoDBRequest, DynamoDBResponse, CrossRegionTransferEvent](
                                    DynamoDbBoundaryProtocol,
                                    SystemBoundaryStage.Config(),
                                    alertsBoundaryRng
                                  ))
          val alertsTable = b.add(DynamoDbTable.componentOf(alertsTableCfg))
          val reqBcast    = b.add(Broadcast[TimedElement[DynamoDBRequest]](2))
          val respBcast   = b.add(Broadcast[TimedElement[DynamoDBResponse]](3))

          workloadG.requestOut ~> sdkClient.in0
          sdkClient.out        ~> reqBcast.in
          reqBcast.out(0)      ~> boundary.requestIn
          reqBcast.out(1)      ~> flowCountSinkShape
          boundary.requestOut  ~> alertsTable.in

          alertsTable.out0     ~> boundary.responseIn
          boundary.responseOut ~> respBcast.in
          respBcast.out(0)     ~> sdkClient.in1
          respBcast.out(1)     ~> workloadG.responseIn
          respBcast.out(2)     ~> throttleSinkShape

          boundary.consumptionOut ~> b.add(Sink.ignore)   // metering unused until 5.2/slice 6

          alertsTable.out1     ~> alertsConsSinkShape
          alertsTable.out2     ~> b.add(Sink.ignore)

          // ── UAS sub-graph (open-loop) ────────────────────────────────────
          val uasSource = b.add(
            Source.fromIterator(() =>
              stochastacy.workload.WorkloadRequestStream(uw, uasWorkloadRng, config.simulationTicks)
            )
          )
          val uasTable = b.add(DynamoDbTable.componentOf(uasTableCfg))

          uasSource.out ~> uasTable.in
          uasTable.out0 ~> b.add(Sink.ignore)
          uasTable.out1 ~> uasConsSinkShape
          uasTable.out2 ~> b.add(Sink.ignore)

          ClosedShape
      }
    ).run()

    val rates = DynamoDbPricingRates.phase1Default.standard

    for
      alertsCons  <- alertsConsF
      uasCons     <- uasConsF
      throttleMap <- throttleF
      flowCounts  <- flowCountF
    yield
      val alertsCostByTick = cumulativeCostByTick(alertsCons, config.simulationTicks, rates)
      val uasCostByTick    = cumulativeCostByTick(uasCons,    config.simulationTicks, rates)

      val zeroCons = (BigDecimal(0), BigDecimal(0), 0L)
      val timeSeries = (1L to config.simulationTicks).flatMap { tick =>
        val alertsCost = alertsCostByTick.getOrElse(tick, BigDecimal(0))
        val uasCost    = uasCostByTick.getOrElse(tick,    BigDecimal(0))
        val totalCost  = alertsCost + uasCost
        val (alertsRcu, alertsWcu, _) = alertsCons.getOrElse(tick, zeroCons)
        val (uasRcu,    uasWcu,    _) = uasCons.getOrElse(tick,    zeroCons)
        Vector(
          SimulationTimeSeriesPoint(tick, DemoMetric.TableCumulativeEstimatedCost("alerts"),             alertsCost),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableCumulativeEstimatedCost("user-alert-status"),  uasCost),
          SimulationTimeSeriesPoint(tick, DemoMetric.CumulativeEstimatedCost,                            totalCost),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableReadCapacityUnits("alerts"),                   alertsRcu),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableWriteCapacityUnits("alerts"),                  alertsWcu),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableReadCapacityUnits("user-alert-status"),        uasRcu),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableWriteCapacityUnits("user-alert-status"),       uasWcu),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableThrottleCount("alerts"),
            BigDecimal(throttleMap.getOrElse(tick, 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-poll",  attempt = 0),
            BigDecimal(flowCounts.getOrElse((tick, "a1-poll",  0), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-poll",  attempt = 1),
            BigDecimal(flowCounts.getOrElse((tick, "a1-poll",  1), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-poll",  attempt = 2),
            BigDecimal(flowCounts.getOrElse((tick, "a1-poll",  2), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a3-write", attempt = 0),
            BigDecimal(flowCounts.getOrElse((tick, "a3-write", 0), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a2-fetch", attempt = 0),
            BigDecimal(flowCounts.getOrElse((tick, "a2-fetch", 0), 0L)))
        )
      }.toVector

      val finalAlertsCost = alertsCostByTick.getOrElse(config.simulationTicks, BigDecimal(0))
      val finalUasCost    = uasCostByTick.getOrElse(config.simulationTicks,    BigDecimal(0))
      val summary = Vector(
        TrialSummaryValue(DemoMetric.TotalEstimatedCost,                               finalAlertsCost + finalUasCost),
        TrialSummaryValue(DemoMetric.TableTotalEstimatedCost("alerts"),                finalAlertsCost),
        TrialSummaryValue(DemoMetric.TableTotalEstimatedCost("user-alert-status"),     finalUasCost),
        TrialSummaryValue(DemoMetric.TableThrottleCount("alerts"),
          BigDecimal(throttleMap.values.sum))
      )

      TrialResult(
        scenarioId = config.scenarioId,
        trialId    = run.trialId,
        timeSeries = timeSeries,
        summary    = summary
      )


private object EasSingleTrialRunner:

  // ── Accumulator types ───────────────────────────────────────────────────────

  /** Per-tick (readUnits, writeUnits, storageBytesDelta). */
  type ConsPerTick = Map[Long, (BigDecimal, BigDecimal, Long)]

  /** Per-tick count of ThrottledResponses. */
  type ThrottlePerTick = Map[Long, Long]

  /** Per (tick, flowId, clientAttempt) request count. */
  type FlowCountPerTick = Map[(Long, String, Int), Long]

  // ── Fold functions ──────────────────────────────────────────────────────────

  def foldCons(acc: ConsPerTick, elem: TimedElement[DynamoDbConsumptionEvent]): ConsPerTick =
    elem match
      case e: DynamoDbConsumptionEvent.ReadCapacityConsumed =>
        val t = e.eventTime.ticks
        val (r, w, s) = acc.getOrElse(t, (BigDecimal(0), BigDecimal(0), 0L))
        acc.updated(t, (r + e.units, w, s))
      case e: DynamoDbConsumptionEvent.WriteCapacityConsumed =>
        val t = e.eventTime.ticks
        val (r, w, s) = acc.getOrElse(t, (BigDecimal(0), BigDecimal(0), 0L))
        acc.updated(t, (r, w + e.units, s))
      case e: DynamoDbConsumptionEvent.StorageBytesDelta =>
        val t = e.eventTime.ticks
        val (r, w, s) = acc.getOrElse(t, (BigDecimal(0), BigDecimal(0), 0L))
        acc.updated(t, (r, w, s + e.bytesDelta))
      case _ => acc

  def foldThrottle(acc: ThrottlePerTick, elem: TimedElement[DynamoDBResponse]): ThrottlePerTick =
    elem match
      case t: ThrottledResponse =>
        val tick = t.eventTime.ticks
        acc.updated(tick, acc.getOrElse(tick, 0L) + 1L)
      case _ => acc

  def foldFlowCount(acc: FlowCountPerTick, elem: TimedElement[DynamoDBRequest]): FlowCountPerTick =
    elem match
      case req: DynamoDBRequest =>
        req.flowId match
          case Some(fid) =>
            val tick = req.eventTime.ticks
            val key  = (tick, fid, req.clientAttempt)
            acc.updated(key, acc.getOrElse(key, 0L) + 1L)
          case None => acc
      case _ => acc

  // ── Cost computation ────────────────────────────────────────────────────────

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  def cumulativeCostByTick(
    consPerTick:     ConsPerTick,
    simulationTicks: Long,
    rates:           DynamoDbPricingRates.RateSet
  ): Map[Long, BigDecimal] =
    var cumRead      = BigDecimal(0)
    var cumWrite     = BigDecimal(0)
    var cumStorage   = 0L
    var cumByteTicks = BigInt(0)
    (1L to simulationTicks).map { tick =>
      val (r, w, s) = consPerTick.getOrElse(tick, (BigDecimal(0), BigDecimal(0), 0L))
      cumRead      += r
      cumWrite     += w
      cumStorage   += s
      cumByteTicks += BigInt(math.max(0L, cumStorage))
      val cost =
        cumRead  * rates.readCapacityUnitPrice  +
        cumWrite * rates.writeCapacityUnitPrice +
        BigDecimal(cumByteTicks) * rates.storagePricePerGiBSecond / bytesPerGiB
      tick -> cost
    }.toMap
