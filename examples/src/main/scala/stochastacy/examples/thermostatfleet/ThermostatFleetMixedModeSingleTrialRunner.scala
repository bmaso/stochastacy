package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.ClosedShape
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates, ProvisionedCapacityData}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.usage.{DynamoDbTargetTimeBasedUsageTotals, DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}
import stochastacy.demo.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.concurrent.{ExecutionContext, Future}

final class ThermostatFleetMixedModeSingleTrialRunner()(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[ThermostatFleetMixedModeConfig]:

  // ── Consumption accumulator ───────────────────────────────────────────────

  private case class TickBucket(
    tick: Long,
    readUnits: BigDecimal = BigDecimal(0),
    writeUnits: BigDecimal = BigDecimal(0)
  )

  private case class ConsAcc(
    usageTotals: DynamoDbUsageTotals = DynamoDbUsageTotals(),
    currentStorageBytes: Long = 0L,
    cumStorageByteTicks: BigInt = BigInt(0),
    activeBucket: Option[TickBucket] = None,
    cumReadUnits: BigDecimal = BigDecimal(0),
    cumWriteUnits: BigDecimal = BigDecimal(0),
    points: Vector[SimulationTimeSeriesPoint] = Vector.empty
  )

  // ── Metric accumulator ────────────────────────────────────────────────────

  private case class MetricTickData(
    provisionedRcu: Option[Long] = None,
    provisionedWcu: Option[Long] = None,
    billingModeCode: Option[Int] = None,
    throttleCount: Int = 0,
    admittedCount: Int = 0,
    consumedReadUnits: BigDecimal = BigDecimal(0),
    consumedWriteUnits: BigDecimal = BigDecimal(0)
  )

  private case class MetricAcc(
    byTick: Map[Long, MetricTickData] = Map.empty,
    retItemByOpAndTick: Map[(String, Long), Long] = Map.empty,
    latencySamplesByOpAndTick: Map[(String, Long), Vector[Double]] = Map.empty,
    totalProvisionedRcuTicks: BigInt = BigInt(0),
    totalProvisionedWcuTicks: BigInt = BigInt(0)
  )

  // ── Fold helpers ──────────────────────────────────────────────────────────

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  private def finalizeBucket(acc: ConsAcc, rates: DynamoDbPricingRates, tableClass: DynamoDbTable.TableClass): ConsAcc =
    acc.activeBucket match
      case None => acc
      case Some(bucket) =>
        val nextRead  = acc.cumReadUnits  + bucket.readUnits
        val nextWrite = acc.cumWriteUnits + bucket.writeUnits
        val nextByteTicks = acc.cumStorageByteTicks + BigInt(math.max(0L, acc.currentStorageBytes))
        val r = rates.forClass(tableClass)
        val cumulativeCost =
          (nextRead  * r.readCapacityUnitPrice) +
          (nextWrite * r.writeCapacityUnitPrice) +
          (BigDecimal(nextByteTicks) * r.storagePricePerGiBSecond / bytesPerGiB)
        acc.copy(
          activeBucket      = None,
          cumReadUnits      = nextRead,
          cumWriteUnits     = nextWrite,
          cumStorageByteTicks = nextByteTicks,
          points = acc.points ++ Vector(
            SimulationTimeSeriesPoint(bucket.tick, DemoMetric.StorageBytes,               BigDecimal(math.max(0L, acc.currentStorageBytes))),
            SimulationTimeSeriesPoint(bucket.tick, DemoMetric.CumulativeEstimatedCost,    cumulativeCost)
          )
        )

  private def updateConsAcc(
    acc: ConsAcc,
    evt: TimedElement[DynamoDbConsumptionEvent],
    rates: DynamoDbPricingRates,
    tableClass: DynamoDbTable.TableClass
  ): ConsAcc =
    evt match
      case tick: TimedControlEvent.Tick =>
        val finalized = finalizeBucket(acc, rates, tableClass)
        finalized.copy(activeBucket = Some(TickBucket(tick = tick.eventTime.ticks)))

      case cons: DynamoDbConsumptionEvent =>
        val acc1 = acc.copy(usageTotals = DynamoDbUsageTotals.accumulate(acc.usageTotals, cons))
        cons match
          case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, _, units, _) =>
            acc1.copy(activeBucket = acc1.activeBucket.map(b => b.copy(readUnits = b.readUnits + units)))
          case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, _, units) =>
            acc1.copy(activeBucket = acc1.activeBucket.map(b => b.copy(writeUnits = b.writeUnits + units)))
          case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, _, delta) =>
            acc1.copy(currentStorageBytes = acc1.currentStorageBytes + delta)
          case _ => acc1

      case _ => acc

  private def updateMetricAcc(acc: MetricAcc, evt: TimedElement[TableMetricEvent]): MetricAcc =
    evt match
      case util: AdmissionMetricEvent.ProvisionedCapacityUtilization =>
        val t   = util.eventTime.ticks
        val old = acc.byTick.getOrElse(t, MetricTickData())
        acc.copy(
          byTick = acc.byTick.updated(t, old.copy(
            provisionedRcu = Some(util.provisionedReadCapacityUnits),
            provisionedWcu = Some(util.provisionedWriteCapacityUnits)
          )),
          totalProvisionedRcuTicks = acc.totalProvisionedRcuTicks + BigInt(util.provisionedReadCapacityUnits),
          totalProvisionedWcuTicks = acc.totalProvisionedWcuTicks + BigInt(util.provisionedWriteCapacityUnits)
        )
      case snap: AdmissionMetricEvent.BillingModeSnapshot =>
        val t   = snap.eventTime.ticks
        val old = acc.byTick.getOrElse(t, MetricTickData())
        acc.copy(byTick = acc.byTick.updated(t, old.copy(billingModeCode = Some(snap.billingModeCode))))
      case throttled: AdmissionMetricEvent.RequestThrottled =>
        val t   = throttled.eventTime.ticks
        val old = acc.byTick.getOrElse(t, MetricTickData())
        acc.copy(byTick = acc.byTick.updated(t, old.copy(throttleCount = old.throttleCount + 1)))
      case admitted: AdmissionMetricEvent.RequestAdmitted =>
        val t   = admitted.eventTime.ticks
        val old = acc.byTick.getOrElse(t, MetricTickData())
        acc.copy(byTick = acc.byTick.updated(t, old.copy(admittedCount = old.admittedCount + 1)))
      case snap: AdmissionMetricEvent.ConsumedCapacitySnapshot =>
        val t   = snap.eventTime.ticks
        val old = acc.byTick.getOrElse(t, MetricTickData())
        acc.copy(byTick = acc.byTick.updated(t, old.copy(
          consumedReadUnits  = old.consumedReadUnits  + snap.consumedReadUnits,
          consumedWriteUnits = old.consumedWriteUnits + snap.consumedWriteUnits
        )))
      case ric: StorageMetricEvent.ReturnedItemCount =>
        val key = (ric.operation.toString, ric.eventTime.ticks)
        acc.copy(retItemByOpAndTick = acc.retItemByOpAndTick.updated(
          key, acc.retItemByOpAndTick.getOrElse(key, 0L) + ric.count
        ))
      case lat: StorageMetricEvent.SuccessfulRequestLatency =>
        val key = (lat.operation.toString, lat.eventTime.ticks)
        acc.copy(latencySamplesByOpAndTick = acc.latencySamplesByOpAndTick.updated(
          key, acc.latencySamplesByOpAndTick.getOrElse(key, Vector.empty) :+ lat.latencyMs
        ))
      case _ => acc

  // ── Run trial ─────────────────────────────────────────────────────────────

  override def runTrial(config: ThermostatFleetMixedModeConfig, run: TrialRunConfig): Future[TrialResult] =
    val scenarioConfig = config.toScenarioConfig
    val schedule = scenarioConfig.reconfigurationSchedule.get

    val masterRng  = RandomSource.KISS.create(run.seed)
    val requestRng = RandomSource.KISS.create(masterRng.nextLong())
    val behaviorRng = RandomSource.KISS.create(masterRng.nextLong())

    val region = scenarioConfig.regions.head
    val tableState = SummaryTableState(initialItemCount = 0L, initialTotalItemBytes = 0L)
    val behavior   = ThermostatFleetBehavior(scenarioConfig, behaviorRng, region.initialDeviceCount, region.deviceGrowthPerTick)
    val behaviors: Map[Any, UseCaseSampler[TableState]] = Map(scenarioConfig.scenarioId -> behavior)

    val tableConfig = buildTableConfig(scenarioConfig, tableState, behaviors)
    val requestIterator    = delegate.generateRequestsForRegion(scenarioConfig, region, requestRng)
    val managementIterator = delegate.managementEventsFor(scenarioConfig.simulationTicks, schedule)

    val consFoldSink: Sink[TimedElement[DynamoDbConsumptionEvent], Future[ConsAcc]] =
      Sink.fold(ConsAcc()) { (acc, evt) => updateConsAcc(acc, evt, scenarioConfig.pricingRates, scenarioConfig.tableClass) }

    val metricFoldSink: Sink[TimedElement[TableMetricEvent], Future[MetricAcc]] =
      Sink.fold(MetricAcc()) { (acc, evt) => updateMetricAcc(acc, evt) }

    val (consAccF, metricAccF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consFoldSink, metricFoldSink)((a, b) => (a, b)) {
        implicit b => (consSinkShape, metricSinkShape) =>
          import GraphDSL.Implicits.*
          val table = b.add(DynamoDbTable.componentOfManaged(tableConfig))
          Source.fromIterator(() => requestIterator)    ~> table.requestIn
          Source.fromIterator(() => managementIterator) ~> table.managementIn
          table.responseOut ~> b.add(Sink.ignore)
          table.consumptionOut ~> consSinkShape
          table.metricOut      ~> metricSinkShape
          ClosedShape
      }
    ).run()

    for
      rawConsAcc  <- consAccF
      metricAcc   <- metricAccF
    yield
      val consAcc = finalizeBucket(rawConsAcc, scenarioConfig.pricingRates, scenarioConfig.tableClass)

      val timeBasedUsage = DynamoDbTimeBasedUsageTotals(
        overallStorageByteTicks    = consAcc.cumStorageByteTicks,
        endingOverallStorageBytes  = math.max(0L, consAcc.currentStorageBytes)
      )
      val provisionedCapacity =
        Option.when(metricAcc.totalProvisionedRcuTicks > 0 || metricAcc.totalProvisionedWcuTicks > 0)(
          ProvisionedCapacityData(
            totalProvisionedReadCapacityUnitTicks  = metricAcc.totalProvisionedRcuTicks,
            totalProvisionedWriteCapacityUnitTicks = metricAcc.totalProvisionedWcuTicks
          )
        )
      val costBreakdown = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(
          usage = consAcc.usageTotals,
          timeBasedUsage = timeBasedUsage,
          provisionedCapacity = provisionedCapacity
        ),
        scenarioConfig.pricingRates,
        scenarioConfig.tableClass
      )

      val metricTimeSeries: Vector[SimulationTimeSeriesPoint] =
        metricAcc.byTick.toVector.sortBy(_._1).flatMap { case (tick, data) =>
          data.provisionedRcu.map(v => SimulationTimeSeriesPoint(tick, DemoMetric.ProvisionedReadCapacityUnits,  BigDecimal(v))).toVector ++
          data.provisionedWcu.map(v => SimulationTimeSeriesPoint(tick, DemoMetric.ProvisionedWriteCapacityUnits, BigDecimal(v))).toVector ++
          Vector(
            SimulationTimeSeriesPoint(tick, DemoMetric.ReadCapacityUnits,     data.consumedReadUnits),
            SimulationTimeSeriesPoint(tick, DemoMetric.WriteCapacityUnits,    data.consumedWriteUnits),
            SimulationTimeSeriesPoint(tick, DemoMetric.ThrottleCount,         BigDecimal(data.throttleCount)),
            SimulationTimeSeriesPoint(tick, DemoMetric.AdmittedRequestCount,  BigDecimal(data.admittedCount))
          )
        }

      // Build billing mode time series from the config schedule rather than from stream events.
      // The management stream races ahead in Pekko's fused graph (returning Nil for ticks causes
      // it to drain before the request stream processes tick 2), making stream-observed mode codes
      // unreliable for the on-demand phase.
      val billingModeTimeSeries: Vector[SimulationTimeSeriesPoint] =
        (1L to scenarioConfig.simulationTicks).map { tick =>
          val modeCode = if tick <= config.modeSwitchTick then 0 else 1
          SimulationTimeSeriesPoint(tick, DemoMetric.BillingModeIndicator, BigDecimal(modeCode))
        }.toVector

      val retItemTimeSeries: Vector[SimulationTimeSeriesPoint] =
        metricAcc.retItemByOpAndTick.map { case ((op, tick), count) =>
          SimulationTimeSeriesPoint(tick, DemoMetric.ReturnedItemCount(op), BigDecimal(count))
        }.toVector

      val latencyTimeSeries: Vector[SimulationTimeSeriesPoint] =
        metricAcc.latencySamplesByOpAndTick.flatMap { case ((op, tick), samples) =>
          val sorted = samples.sorted
          val p50 = sorted((sorted.size * 0.50).toInt.min(sorted.size - 1))
          val p95 = sorted((sorted.size * 0.95).toInt.min(sorted.size - 1))
          val p99 = sorted((sorted.size * 0.99).toInt.min(sorted.size - 1))
          Vector(
            SimulationTimeSeriesPoint(tick, DemoMetric.LatencyP50(op), BigDecimal.decimal(p50)),
            SimulationTimeSeriesPoint(tick, DemoMetric.LatencyP95(op), BigDecimal.decimal(p95)),
            SimulationTimeSeriesPoint(tick, DemoMetric.LatencyP99(op), BigDecimal.decimal(p99))
          )
        }.toVector

      TrialResult(
        scenarioId = scenarioConfig.scenarioId,
        trialId    = run.trialId,
        timeSeries = consAcc.points ++ metricTimeSeries ++ billingModeTimeSeries ++ retItemTimeSeries ++ latencyTimeSeries,
        summary = Vector(
          TrialSummaryValue(DemoMetric.TotalReadCapacityUnits,  consAcc.usageTotals.overall.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, consAcc.usageTotals.overall.writeCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalStorageByteTicks,   BigDecimal(timeBasedUsage.overallStorageByteTicks)),
          TrialSummaryValue(DemoMetric.FinalStorageBytes,       BigDecimal(timeBasedUsage.endingOverallStorageBytes)),
          TrialSummaryValue(DemoMetric.TotalEstimatedCost,      costBreakdown.totalCost)
        )
      )

  // ── Helpers ───────────────────────────────────────────────────────────────

  private val delegate = ThermostatFleetSingleTrialRunner()

  private def buildTableConfig(
    config: ThermostatFleetScenarioConfig,
    tableState: TableState,
    behaviors: Map[Any, UseCaseSampler[TableState]]
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName    = config.tableName,
      stateModel   = tableState,
      useCaseBehaviors = behaviors,
      readConsistency  = config.readConsistency,
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName  = ThermostatFleetScenarioConfig.CustomerDevicesGsiName,
          projection = config.customerDevicesGsiProjection
        ),
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName  = ThermostatFleetScenarioConfig.FleetAlertsGsiName,
          projection = DynamoDbTable.IndexProjection.Include(config.fleetAlertsGsiProjectedNonKeyBytes)
        ),
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName  = ThermostatFleetScenarioConfig.DeviceStatusGsiName,
          projection = config.deviceStatusGsiProjection
        )
      ),
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition(
          indexName  = ThermostatFleetScenarioConfig.ReadingTypeHistoryLsiName,
          projection = config.readingTypeHistoryLsiProjection
        )
      ),
      itemCollectionSizeLimitBytes    = config.itemCollectionSizeLimitBytes,
      billingMode                     = config.billingMode,
      hotPartitionModel               = config.hotPartitionModel,
      burstCapacityModel              = config.burstCapacityModel,
      adaptiveCapacityModel           = config.adaptiveCapacityModel,
      dynamicPartitionTopologyModel   = config.dynamicPartitionTopologyModel
    )
