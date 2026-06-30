package stochastacy.examples.eas

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse, ThrottledResponse}
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTable}
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

    // Derive four independent sub-RNGs from the trial seed.
    val masterRng         = RandomSource.KISS.create(run.seed)
    val alertsWorkloadRng = RandomSource.KISS.create(masterRng.nextLong())
    val alertsSamplerRng  = RandomSource.KISS.create(masterRng.nextLong())
    val uasWorkloadRng    = RandomSource.KISS.create(masterRng.nextLong())
    val uasSamplerRng     = RandomSource.KISS.create(masterRng.nextLong())

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
          val workloadG   = b.add(WorkloadGraph(aw, allWorkloads, alertsWorkloadRng, config.simulationTicks))
          val alertsTable = b.add(DynamoDbTable.componentOf(alertsTableCfg))
          val reqBcast    = b.add(Broadcast[TimedElement[DynamoDBRequest]](2))
          val respBcast   = b.add(Broadcast[TimedElement[DynamoDBResponse]](2))

          workloadG.requestOut ~> reqBcast.in
          reqBcast.out(0)      ~> alertsTable.in
          reqBcast.out(1)      ~> flowCountSinkShape

          alertsTable.out0     ~> respBcast.in
          respBcast.out(0)     ~> workloadG.responseIn
          respBcast.out(1)     ~> throttleSinkShape

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
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-poll"),
            BigDecimal(flowCounts.getOrElse((tick, "a1-poll"),    0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-retry-1"),
            BigDecimal(flowCounts.getOrElse((tick, "a1-retry-1"), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-retry-2"),
            BigDecimal(flowCounts.getOrElse((tick, "a1-retry-2"), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a1-retry-3"),
            BigDecimal(flowCounts.getOrElse((tick, "a1-retry-3"), 0L))),
          SimulationTimeSeriesPoint(tick, DemoMetric.FlowArrivals("a2-fetch"),
            BigDecimal(flowCounts.getOrElse((tick, "a2-fetch"),   0L)))
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

  /** Per (tick, flowId) request count. */
  type FlowCountPerTick = Map[(Long, String), Long]

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
            val key  = (tick, fid)
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
