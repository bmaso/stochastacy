package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{DiscreteDistribution, LogNormalDistribution, PoissonDistribution}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates, PricingSchedule}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.usage.{DynamoDbTargetTimeBasedUsageTotals, DynamoDbTargetUsageTotals, DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}
import stochastacy.aws.transfer.{CrossRegionTransferCostBreakdown, CrossRegionTransferEvent, CrossRegionTransferUsageTotals}
import stochastacy.demo.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.concurrent.{ExecutionContext, Future}

final class ThermostatFleetSingleTrialRunner()(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[ThermostatFleetScenarioConfig]:

  // ── Private accumulator types ────────────────────────────────────────────

  private case class TSSBucket(
    tick: Long,
    readUnits: BigDecimal = BigDecimal(0),
    writeUnits: BigDecimal = BigDecimal(0),
    replicatedWriteUnits: BigDecimal = BigDecimal(0),
    gsiReadUnits: Map[String, BigDecimal] = Map.empty,
    gsiWriteUnits: Map[String, BigDecimal] = Map.empty,
    storageByteDelta: Long = 0L
  )

  /** Per-region streaming fold accumulator. Uses perTickBuckets keyed by eventTime.ticks so that
   *  reads from GSI branches (which carry no Tick events) are attributed to their correct tick
   *  regardless of arrival order in the merged stream. */
  private case class PerRegionAcc(
    usageTotals: DynamoDbUsageTotals = DynamoDbUsageTotals(),
    tbByteTicksByTarget: Map[DynamoDbTarget, BigInt] = Map.empty,
    tbCurrentBytesByTarget: Map[DynamoDbTarget, Long] = Map.empty,
    tbHasSeenTick: Boolean = false,
    perTickBuckets: Map[Long, TSSBucket] = Map.empty,
    currentStorageBytes: Long = 0L
  )

  private case class TickBucketAgg(
    readUnits: BigDecimal = BigDecimal(0),
    writeUnits: BigDecimal = BigDecimal(0),
    replWriteUnits: BigDecimal = BigDecimal(0),
    storageDelta: Long = 0L,
    gsiReadByName: Map[String, BigDecimal] = Map.empty,
    gsiWriteByName: Map[String, BigDecimal] = Map.empty
  )

  private case class MultiRegionFoldAcc(
    perRegion: Map[String, PerRegionAcc] = Map.empty,
    aggByTick: Map[Long, TickBucketAgg] = Map.empty
  )

  private case class TransferFoldAcc(
    totals: CrossRegionTransferUsageTotals = CrossRegionTransferUsageTotals(),
    byTickAndLink: Map[(Long, (String, String)), Long] = Map.empty
  )

  // (operation-string, tick) -> cumulative count
  private type RetItemAcc = Map[(String, Long), Long]

  private def foldMetricEvent(acc: RetItemAcc, evt: TimedElement[TableMetricEvent]): RetItemAcc =
    evt match
      case StorageMetricEvent.ReturnedItemCount(et, _, op, count) =>
        val key = (op.toString, et.ticks)
        acc.updated(key, acc.getOrElse(key, 0L) + count)
      case _ => acc

  // tick -> max observed lagMs for a single destination region
  private type LatencyAcc = Map[Long, Double]

  private def foldLatencyEvent(acc: LatencyAcc, evt: TimedElement[TableMetricEvent]): LatencyAcc =
    evt match
      case lat: ReplicationMetricEvent.ReplicationLatency =>
        val prev = acc.getOrElse(lat.eventTime.ticks, 0.0)
        acc.updated(lat.eventTime.ticks, math.max(prev, lat.lagMs))
      case _ => acc

  // (operation-string, tick) -> raw latency samples (ms) collected within the tick
  private type LatencySampleAcc = Map[(String, Long), Vector[Double]]

  private def foldLatencySampleEvent(acc: LatencySampleAcc, evt: TimedElement[TableMetricEvent]): LatencySampleAcc =
    evt match
      case lat: StorageMetricEvent.SuccessfulRequestLatency =>
        val key = (lat.operation.toString, lat.eventTime.ticks)
        acc.updated(key, acc.getOrElse(key, Vector.empty) :+ lat.latencyMs)
      case _ => acc

  private def latencyPercentileTimeSeries(latSampleAcc: LatencySampleAcc): Vector[SimulationTimeSeriesPoint] =
    latSampleAcc.flatMap { case ((op, tick), samples) =>
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

  // ── Fold helpers ─────────────────────────────────────────────────────────

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  /** Build per-region time series points from perTickBuckets, iterating in tick order to compute
   *  cumulative cost. All consumption events are keyed by eventTime.ticks so reads from GSI branches
   *  are correctly attributed regardless of arrival order in the merged stream. */
  private def buildPerRegionTimeSeries(
    acc: PerRegionAcc,
    gsiNames: Vector[String],
    pricingRates: DynamoDbPricingRates,
    tableClass: DynamoDbTable.TableClass,
    regionName: Option[String]
  ): Vector[SimulationTimeSeriesPoint] =
    val sortedTicks = acc.perTickBuckets.keys.toVector.sorted
    var cumRead = BigDecimal(0)
    var cumWrite = BigDecimal(0)
    var cumReplWrite = BigDecimal(0)
    var cumStorage = 0L
    var cumByteTicks = BigInt(0)

    val (readMetric, writeMetric, storageMetric, costMetric, maybeReplMetric) =
      regionName match
        case None =>
          (DemoMetric.ReadCapacityUnits, DemoMetric.WriteCapacityUnits,
           DemoMetric.StorageBytes, DemoMetric.CumulativeEstimatedCost, None)
        case Some(r) =>
          (DemoMetric.RegionReadCapacityUnits(r), DemoMetric.RegionWriteCapacityUnits(r),
           DemoMetric.RegionStorageBytes(r), DemoMetric.RegionCumulativeEstimatedCost(r),
           Some(DemoMetric.RegionReplicatedWriteCapacityUnits(r)))

    sortedTicks.flatMap { tick =>
      val b = acc.perTickBuckets(tick)
      cumRead += b.readUnits
      cumWrite += b.writeUnits
      cumReplWrite += b.replicatedWriteUnits
      cumStorage += b.storageByteDelta
      cumByteTicks += BigInt(math.max(0L, cumStorage))
      val r = pricingRates.forClass(tableClass)
      val cumulativeCost =
        (cumRead * r.readCapacityUnitPrice) +
          (cumWrite * r.writeCapacityUnitPrice) +
          (cumReplWrite * r.replicatedWriteCapacityUnitPrice) +
          (BigDecimal(cumByteTicks) * r.storagePricePerGiBSecond / bytesPerGiB)

      val basePoints = Vector(
        SimulationTimeSeriesPoint(tick, readMetric, b.readUnits),
        SimulationTimeSeriesPoint(tick, writeMetric, b.writeUnits)
      ) ++ maybeReplMetric.map(m => SimulationTimeSeriesPoint(tick, m, b.replicatedWriteUnits)).toVector

      val gsiPoints = gsiNames.sorted.flatMap { indexName =>
        Vector(
          SimulationTimeSeriesPoint(tick, DemoMetric.GsiReadCapacityUnits(indexName),
            b.gsiReadUnits.getOrElse(indexName, BigDecimal(0))),
          SimulationTimeSeriesPoint(tick, DemoMetric.GsiWriteCapacityUnits(indexName),
            b.gsiWriteUnits.getOrElse(indexName, BigDecimal(0)))
        )
      }

      basePoints ++ gsiPoints ++ Vector(
        SimulationTimeSeriesPoint(tick, storageMetric, BigDecimal(math.max(0L, cumStorage))),
        SimulationTimeSeriesPoint(tick, costMetric, cumulativeCost)
      )
    }

  private def updatePerRegionAcc(
    acc: PerRegionAcc,
    evt: TimedElement[DynamoDbConsumptionEvent]
  ): PerRegionAcc =
    evt match
      case tick: TimedControlEvent.Tick =>
        val tbUpdated =
          if acc.tbHasSeenTick then
            val nextByteTicks = acc.tbCurrentBytesByTarget.foldLeft(acc.tbByteTicksByTarget) {
              case (m, (target, bytes)) => m.updated(target, m.getOrElse(target, BigInt(0)) + BigInt(bytes))
            }
            acc.copy(tbByteTicksByTarget = nextByteTicks)
          else acc
        tbUpdated.copy(tbHasSeenTick = true)

      case cons: DynamoDbConsumptionEvent =>
        val acc1 = acc.copy(usageTotals = DynamoDbUsageTotals.accumulate(acc.usageTotals, cons))
        val t = cons.eventTime.ticks
        val bucket = acc1.perTickBuckets.getOrElse(t, TSSBucket(tick = t))
        cons match
          case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, target, units, _) =>
            val updatedBucket = target match
              case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                bucket.copy(readUnits = bucket.readUnits + units,
                  gsiReadUnits = bucket.gsiReadUnits.updated(indexName, bucket.gsiReadUnits.getOrElse(indexName, BigDecimal(0)) + units))
              case _ => bucket.copy(readUnits = bucket.readUnits + units)
            acc1.copy(perTickBuckets = acc1.perTickBuckets.updated(t, updatedBucket))
          case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, target, units) =>
            val updatedBucket = target match
              case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                bucket.copy(writeUnits = bucket.writeUnits + units,
                  gsiWriteUnits = bucket.gsiWriteUnits.updated(indexName, bucket.gsiWriteUnits.getOrElse(indexName, BigDecimal(0)) + units))
              case _ => bucket.copy(writeUnits = bucket.writeUnits + units)
            acc1.copy(perTickBuckets = acc1.perTickBuckets.updated(t, updatedBucket))
          case DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed(_, _, _, units) =>
            acc1.copy(perTickBuckets = acc1.perTickBuckets.updated(t,
              bucket.copy(replicatedWriteUnits = bucket.replicatedWriteUnits + units)))
          case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, target, bytesDelta) =>
            acc1.copy(
              currentStorageBytes = acc1.currentStorageBytes + bytesDelta,
              tbCurrentBytesByTarget = acc1.tbCurrentBytesByTarget.updated(
                target, acc1.tbCurrentBytesByTarget.getOrElse(target, 0L) + bytesDelta),
              perTickBuckets = acc1.perTickBuckets.updated(t,
                bucket.copy(storageByteDelta = bucket.storageByteDelta + bytesDelta))
            )
          case _ => acc1

      case _ => acc

  private def extractTimeBasedUsage(acc: PerRegionAcc): DynamoDbTimeBasedUsageTotals =
    val allTargets = acc.tbByteTicksByTarget.keySet ++ acc.tbCurrentBytesByTarget.keySet
    val byTarget = allTargets.iterator.map { target =>
      target -> DynamoDbTargetTimeBasedUsageTotals(
        storageByteTicks = acc.tbByteTicksByTarget.getOrElse(target, BigInt(0)),
        endingStorageBytes = acc.tbCurrentBytesByTarget.getOrElse(target, 0L)
      )
    }.toMap
    DynamoDbTimeBasedUsageTotals(
      overallStorageByteTicks = byTarget.valuesIterator.map(_.storageByteTicks).sum,
      endingOverallStorageBytes = byTarget.valuesIterator.map(_.endingStorageBytes).sum,
      byTarget = byTarget
    )

  private def updateTickBucketAgg(
    aggByTick: Map[Long, TickBucketAgg],
    cons: DynamoDbConsumptionEvent
  ): Map[Long, TickBucketAgg] =
    val tick = cons.eventTime.ticks
    val b = aggByTick.getOrElse(tick, TickBucketAgg())
    val updated = cons match
      case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, target, units, _) =>
        target match
          case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
            b.copy(readUnits = b.readUnits + units,
              gsiReadByName = b.gsiReadByName.updated(indexName, b.gsiReadByName.getOrElse(indexName, BigDecimal(0)) + units))
          case _ => b.copy(readUnits = b.readUnits + units)
      case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, target, units) =>
        target match
          case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
            b.copy(writeUnits = b.writeUnits + units,
              gsiWriteByName = b.gsiWriteByName.updated(indexName, b.gsiWriteByName.getOrElse(indexName, BigDecimal(0)) + units))
          case _ => b.copy(writeUnits = b.writeUnits + units)
      case DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed(_, _, _, units) =>
        b.copy(replWriteUnits = b.replWriteUnits + units)
      case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, _, bytesDelta) =>
        b.copy(storageDelta = b.storageDelta + bytesDelta)
      case _ => b
    aggByTick.updated(tick, updated)

  private def buildAggTimeSeries(
    aggByTick: Map[Long, TickBucketAgg],
    gsiNames: Vector[String],
    pricingRates: DynamoDbPricingRates,
    tableClass: DynamoDbTable.TableClass
  ): Vector[SimulationTimeSeriesPoint] =
    val sortedTicks = aggByTick.keys.toVector.sorted
    var cumRead = BigDecimal(0)
    var cumWrite = BigDecimal(0)
    var cumReplWrite = BigDecimal(0)
    var cumStorage = 0L
    var cumByteTicks = BigInt(0)
    sortedTicks.flatMap { tick =>
      val agg = aggByTick(tick)
      cumRead += agg.readUnits
      cumWrite += agg.writeUnits
      cumReplWrite += agg.replWriteUnits
      cumStorage += agg.storageDelta
      cumByteTicks += BigInt(math.max(0L, cumStorage))
      val r = pricingRates.forClass(tableClass)
      val cumCost =
        (cumRead * r.readCapacityUnitPrice) +
          (cumWrite * r.writeCapacityUnitPrice) +
          (cumReplWrite * r.replicatedWriteCapacityUnitPrice) +
          (BigDecimal(cumByteTicks) * r.storagePricePerGiBSecond / bytesPerGiB)
      Vector(
        SimulationTimeSeriesPoint(tick, DemoMetric.ReadCapacityUnits, agg.readUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.WriteCapacityUnits, agg.writeUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.StorageBytes, BigDecimal(math.max(0L, cumStorage))),
        SimulationTimeSeriesPoint(tick, DemoMetric.CumulativeEstimatedCost, cumCost)
      ) ++ gsiNames.sorted.flatMap { indexName =>
        Vector(
          SimulationTimeSeriesPoint(tick, DemoMetric.GsiReadCapacityUnits(indexName),
            agg.gsiReadByName.getOrElse(indexName, BigDecimal(0))),
          SimulationTimeSeriesPoint(tick, DemoMetric.GsiWriteCapacityUnits(indexName),
            agg.gsiWriteByName.getOrElse(indexName, BigDecimal(0)))
        )
      }
    }

  // ── Single-region path ──────────────────────────────────────────────────────

  override def runTrial(config: ThermostatFleetScenarioConfig, run: TrialRunConfig): Future[TrialResult] =
    if config.isMultiRegion then runTrialMultiRegion(config, run)
    else runTrialSingleRegion(config, run)

  private def runTrialSingleRegion(
    config: ThermostatFleetScenarioConfig,
    run: TrialRunConfig
  ): Future[TrialResult] =
    val masterRng = RandomSource.KISS.create(run.seed)
    val requestRng = RandomSource.KISS.create(masterRng.nextLong())
    val behaviorRng = RandomSource.KISS.create(masterRng.nextLong())

    val region = config.regions.head
    val tableState = SummaryTableState(initialItemCount = 0L, initialTotalItemBytes = 0L)
    val behavior = ThermostatFleetBehavior(config, behaviorRng, region.initialDeviceCount, region.deviceGrowthPerTick)
    val behaviors: Map[Any, UseCaseSampler[TableState]] = Map(config.scenarioId -> behavior)
    val requestIterator = generateRequestsForRegion(config, region, requestRng)

    val gsiNames = Vector(
      ThermostatFleetScenarioConfig.CustomerDevicesGsiName,
      ThermostatFleetScenarioConfig.FleetAlertsGsiName,
      ThermostatFleetScenarioConfig.DeviceStatusGsiName
    )
    val schedule = config.reconfigurationSchedule.filter(_.events.nonEmpty)

    val foldSink = Sink.fold[PerRegionAcc, TimedElement[DynamoDbConsumptionEvent]](PerRegionAcc()) {
      (acc, evt) => updatePerRegionAcc(acc, evt)
    }
    val retItemFoldSink = Sink.fold[RetItemAcc, TimedElement[TableMetricEvent]](Map.empty)(foldMetricEvent)
    val latSampleFoldSink = Sink.fold[LatencySampleAcc, TimedElement[TableMetricEvent]](Map.empty)(foldLatencySampleEvent)

    val (accF, retItemAccF, latSampleAccF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(foldSink, retItemFoldSink, latSampleFoldSink)((c, m, l) => (c, m, l)) { implicit b => (consSink, metSink, latSink) =>
        import GraphDSL.Implicits.*
        val metricBcast = b.add(org.apache.pekko.stream.scaladsl.Broadcast[TimedElement[TableMetricEvent]](2))
        schedule match
          case Some(reconfigurationSchedule) =>
            val table = b.add(DynamoDbTable.componentOfManaged(buildTableConfig(config, tableState, behaviors)))
            Source.fromIterator(() => requestIterator) ~> table.requestIn
            Source.fromIterator(() => managementEventsFor(config.simulationTicks, reconfigurationSchedule)) ~> table.managementIn
            table.responseOut ~> b.add(Sink.ignore)
            table.consumptionOut ~> consSink
            table.metricOut ~> metricBcast.in
          case None =>
            val table = b.add(DynamoDbTable.componentOf(buildTableConfig(config, tableState, behaviors)))
            Source.fromIterator(() => requestIterator) ~> table.in
            table.out0 ~> b.add(Sink.ignore)
            table.out1 ~> consSink
            table.out2 ~> metricBcast.in
        metricBcast.out(0) ~> metSink
        metricBcast.out(1) ~> latSink
        ClosedShape
      }
    ).run()

    val rates = config.pricingSchedule.ratesAt(region.regionName, config.simulationTicks)

    for
      rawAcc       <- accF
      retItemAcc   <- retItemAccF
      latSampleAcc <- latSampleAccF
    yield
      val timeBasedTotals = extractTimeBasedUsage(rawAcc)
      val costBreakdown = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(usage = rawAcc.usageTotals, timeBasedUsage = timeBasedTotals),
        rates,
        config.tableClass
      )
      val gsiUsage = gsiNames.map { indexName =>
        indexName -> rawAcc.usageTotals.byTarget.collectFirst {
          case (DynamoDbTarget.GlobalSecondaryIndex(_, `indexName`), totals) => totals
        }.getOrElse(DynamoDbTargetUsageTotals())
      }.toMap
      val points = buildPerRegionTimeSeries(rawAcc, gsiNames, rates, config.tableClass, regionName = None)
      val retItemPoints: Vector[SimulationTimeSeriesPoint] =
        retItemAcc.map { case ((op, tick), count) =>
          SimulationTimeSeriesPoint(tick, DemoMetric.ReturnedItemCount(op), BigDecimal(count))
        }.toVector

      TrialResult(
        scenarioId = config.scenarioId,
        trialId = run.trialId,
        timeSeries = points ++ retItemPoints ++ latencyPercentileTimeSeries(latSampleAcc),
        summary = Vector(
          TrialSummaryValue(DemoMetric.TotalReadCapacityUnits, rawAcc.usageTotals.overall.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, rawAcc.usageTotals.overall.writeCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalStorageByteTicks, BigDecimal(timeBasedTotals.overallStorageByteTicks)),
          TrialSummaryValue(DemoMetric.FinalStorageBytes, BigDecimal(timeBasedTotals.endingOverallStorageBytes)),
          TrialSummaryValue(DemoMetric.TotalEstimatedCost, costBreakdown.totalCost)
        ) ++ gsiNames.flatMap { indexName =>
          val totals = gsiUsage(indexName)
          Vector(
            TrialSummaryValue(DemoMetric.TotalGsiReadCapacityUnits(indexName), totals.readCapacityUnits),
            TrialSummaryValue(DemoMetric.TotalGsiWriteCapacityUnits(indexName), totals.writeCapacityUnits)
          )
        }
      )

  // ── Multi-region path ───────────────────────────────────────────────────────

  private def runTrialMultiRegion(
    config: ThermostatFleetScenarioConfig,
    run: TrialRunConfig
  ): Future[TrialResult] =
    val sortedRegions = config.regions.sortBy(_.regionName)

    val regionRngs: Map[String, UniformRandomProvider] = sortedRegions.map { region =>
      val seed = run.seed ^ (region.regionName.hashCode.toLong * 0x9E3779B97F4A7C15L)
      region.regionName -> RandomSource.KISS.create(seed)
    }.toMap

    val regionBehaviorRngs: Map[String, UniformRandomProvider] = sortedRegions.map { region =>
      val seed = run.seed ^ (region.regionName.hashCode.toLong * 0xBF58476D1CE4E5B9L)
      region.regionName -> RandomSource.KISS.create(seed)
    }.toMap

    val requestStreamIterators: Map[String, Iterator[TimedElement[DynamoDBRequest]]] =
      sortedRegions.map { region =>
        region.regionName -> generateRequestsForRegion(config, region, regionRngs(region.regionName))
      }.toMap

    val behaviors: Map[String, Map[Any, UseCaseSampler[TableState]]] =
      sortedRegions.map { region =>
        region.regionName -> Map[Any, UseCaseSampler[TableState]](
          config.scenarioId -> ThermostatFleetBehavior(config, regionBehaviorRngs(region.regionName), region.initialDeviceCount, region.deviceGrowthPerTick)
        )
      }.toMap

    val tableState = SummaryTableState(0L, 0L)
    val perRegionTableConfig: Map[String, DynamoDbTable.Config] =
      sortedRegions.map { region =>
        region.regionName -> buildTableConfig(config, tableState, behaviors(region.regionName))
      }.toMap

    val replicationModel = config.replicationModel.getOrElse(buildDefaultReplicationModel(run.seed))
    val globalConfig = DynamoDbGlobalTable.Config(
      regions = perRegionTableConfig,
      replicationModel = replicationModel
    )
    val schedule = config.reconfigurationSchedule.filter(_.events.nonEmpty)

    val gsiNames = Vector(
      ThermostatFleetScenarioConfig.CustomerDevicesGsiName,
      ThermostatFleetScenarioConfig.FleetAlertsGsiName,
      ThermostatFleetScenarioConfig.DeviceStatusGsiName
    )

    type TaggedConsEvent = (String, TimedElement[DynamoDbConsumptionEvent])

    val taggedConsSink = Sink.fold[MultiRegionFoldAcc, TaggedConsEvent](MultiRegionFoldAcc()) {
      (acc, taggedEvt) =>
        val (region, evt) = taggedEvt
        val regionAcc = acc.perRegion.getOrElse(region, PerRegionAcc())
        val updatedRegion = updatePerRegionAcc(regionAcc, evt)
        val updatedAgg = evt match
          case cons: DynamoDbConsumptionEvent => updateTickBucketAgg(acc.aggByTick, cons)
          case _ => acc.aggByTick
        acc.copy(
          perRegion = acc.perRegion.updated(region, updatedRegion),
          aggByTick = updatedAgg
        )
    }

    val transferSink = Sink.fold[TransferFoldAcc, TimedElement[CrossRegionTransferEvent]](TransferFoldAcc()) {
      (acc, evt) =>
        evt match
          case e: CrossRegionTransferEvent =>
            val key = (e.eventTime.ticks, (e.sourceRegion, e.destinationRegion))
            acc.copy(
              totals = CrossRegionTransferUsageTotals.accumulate(acc.totals, e),
              byTickAndLink = acc.byTickAndLink.updated(key, acc.byTickAndLink.getOrElse(key, 0L) + e.bytes)
            )
          case _ => acc
    }

    type TaggedMetricEvent = (String, TimedElement[TableMetricEvent])
    val taggedMetricFoldSink = Sink.fold[Map[String, RetItemAcc], TaggedMetricEvent](Map.empty) {
      (acc, tagged) =>
        val (region, evt) = tagged
        acc.updated(region, foldMetricEvent(acc.getOrElse(region, Map.empty), evt))
    }

    val taggedLatencyFoldSink = Sink.fold[Map[String, LatencyAcc], TaggedMetricEvent](Map.empty) {
      (acc, tagged) =>
        val (region, evt) = tagged
        acc.updated(region, foldLatencyEvent(acc.getOrElse(region, Map.empty), evt))
    }

    val taggedLatencySampleFoldSink = Sink.fold[Map[String, LatencySampleAcc], TaggedMetricEvent](Map.empty) {
      (acc, tagged) =>
        val (region, evt) = tagged
        acc.updated(region, foldLatencySampleEvent(acc.getOrElse(region, Map.empty), evt))
    }

    val (mrAccF, transferAccF, retItemRegionAccF, latencyRegionAccF, latencySampleRegionAccF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(taggedConsSink, transferSink, taggedMetricFoldSink, taggedLatencyFoldSink, taggedLatencySampleFoldSink)(
        (mc, tf, mi, la, ls) => (mc, tf, mi, la, ls)
      ) {
        implicit b => (taggedConsSinkShape, transfersSinkShape, metricSinkShape, latencySinkShape, latencySampleSinkShape) =>
          import GraphDSL.Implicits.*
          val metricMerge = b.add(Merge[TaggedMetricEvent](sortedRegions.size))
          schedule match
            case Some(reconfigurationSchedule) =>
              val globalTable = b.add(DynamoDbGlobalTable.componentOfManaged(globalConfig))
              val consumptionMerge = b.add(Merge[TaggedConsEvent](sortedRegions.size))

              sortedRegions.zipWithIndex.foreach { case (region, idx) =>
                val r = region.regionName
                Source.fromIterator(() => requestStreamIterators(r)) ~> globalTable.regionRequestInlets(r)
                globalTable.regionResponseOutlets(r) ~> b.add(Sink.ignore)
                val consTagger = b.add(Flow[TimedElement[DynamoDbConsumptionEvent]].map(e => (r, e)))
                globalTable.regionConsumptionOutlets(r) ~> consTagger.in
                consTagger.out ~> consumptionMerge.in(idx)
                val metricTagger = b.add(Flow[TimedElement[TableMetricEvent]].map(e => (r, e)))
                globalTable.regionMetricOutlets(r) ~> metricTagger.in
                metricTagger.out ~> metricMerge.in(idx)
              }

              Source.fromIterator(() => managementEventsFor(config.simulationTicks, reconfigurationSchedule)) ~> globalTable.managementIn
              consumptionMerge.out ~> taggedConsSinkShape
              globalTable.transferEventsOutlet ~> transfersSinkShape

            case None =>
              val globalTable = b.add(DynamoDbGlobalTable.componentOf(globalConfig))
              val consumptionMerge = b.add(Merge[TaggedConsEvent](sortedRegions.size))

              sortedRegions.zipWithIndex.foreach { case (region, idx) =>
                val r = region.regionName
                Source.fromIterator(() => requestStreamIterators(r)) ~> globalTable.regionRequestInlets(r)
                globalTable.regionResponseOutlets(r) ~> b.add(Sink.ignore)
                val consTagger = b.add(Flow[TimedElement[DynamoDbConsumptionEvent]].map(e => (r, e)))
                globalTable.regionConsumptionOutlets(r) ~> consTagger.in
                consTagger.out ~> consumptionMerge.in(idx)
                val metricTagger = b.add(Flow[TimedElement[TableMetricEvent]].map(e => (r, e)))
                globalTable.regionMetricOutlets(r) ~> metricTagger.in
                metricTagger.out ~> metricMerge.in(idx)
              }

              consumptionMerge.out ~> taggedConsSinkShape
              globalTable.transferEventsOutlet ~> transfersSinkShape
          val metricBcast = b.add(org.apache.pekko.stream.scaladsl.Broadcast[TaggedMetricEvent](3))
          metricMerge.out ~> metricBcast.in
          metricBcast.out(0) ~> metricSinkShape
          metricBcast.out(1) ~> latencySinkShape
          metricBcast.out(2) ~> latencySampleSinkShape
          ClosedShape
      }
    ).run()

    val regionRates: Map[String, DynamoDbPricingRates] =
      sortedRegions.map(r => r.regionName ->
        config.pricingSchedule.ratesAt(r.regionName, config.simulationTicks)
      ).toMap

    for
      mrAcc                 <- mrAccF
      transferAcc           <- transferAccF
      retItemRegionAcc      <- retItemRegionAccF
      latencyRegionAcc      <- latencyRegionAccF
      latencySampleRegionAcc <- latencySampleRegionAccF
    yield
      val regions = sortedRegions.map(_.regionName)

      val regionUsage = mrAcc.perRegion.map { case (r, acc) => r -> acc.usageTotals }
      val regionTimeBasedUsage = mrAcc.perRegion.map { case (r, acc) => r -> extractTimeBasedUsage(acc) }
      val regionCost = regions.map { r =>
        r -> DynamoDbCostBreakdown.price(
          DynamoDbPricingInputs(
            usage = regionUsage.getOrElse(r, DynamoDbUsageTotals()),
            timeBasedUsage = regionTimeBasedUsage.getOrElse(r, DynamoDbTimeBasedUsageTotals())
          ),
          regionRates(r),
          config.tableClass
        )
      }.toMap

      val transferTotals = transferAcc.totals
      val transferCostBreakdown = CrossRegionTransferCostBreakdown.price(transferTotals, config.transferPricingRates)
      val transferTimeSeries = transferAcc.byTickAndLink.map { case ((tick, (src, dst)), bytes) =>
        SimulationTimeSeriesPoint(tick, DemoMetric.CrossRegionTransferBytes(src, dst), BigDecimal(bytes))
      }.toVector

      val transferCostTimeSeries: Vector[SimulationTimeSeriesPoint] = {
        val sortedTicks = transferAcc.byTickAndLink.keys.map(_._1).toVector.sorted.distinct
        var cumulativeTotals = CrossRegionTransferUsageTotals()
        sortedTicks.map { tick =>
          transferAcc.byTickAndLink.foreach { case ((t, (src, dst)), bytes) =>
            if t == tick then
              cumulativeTotals = CrossRegionTransferUsageTotals.accumulate(
                cumulativeTotals,
                CrossRegionTransferEvent(SimTime.of(tick), "cost-ts", src, dst, "DynamoDB", bytes)
              )
          }
          val cost = CrossRegionTransferCostBreakdown.price(cumulativeTotals, config.transferPricingRates).totalCost
          SimulationTimeSeriesPoint(tick, DemoMetric.CumulativeCrossRegionTransferCost, cost)
        }
      }

      val overallUsage = mergeUsageTotals(regionUsage.values.toVector)
      val overallTimeBasedUsage = mergeTimeBasedUsageTotals(regionTimeBasedUsage.values.toVector)
      val overallCost = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(usage = overallUsage, timeBasedUsage = overallTimeBasedUsage),
        config.pricingSchedule.defaultRates,
        config.tableClass
      )

      val aggregateTimeSeries = buildAggTimeSeries(mrAcc.aggByTick, gsiNames, config.pricingSchedule.defaultRates, config.tableClass)
      val perRegionTimeSeries = regions.flatMap { r =>
        mrAcc.perRegion.get(r).map(acc =>
          buildPerRegionTimeSeries(acc, Vector.empty, regionRates(r), config.tableClass, Some(r))
        ).getOrElse(Vector.empty)
      }

      val gsiAggUsage: Map[String, DynamoDbTargetUsageTotals] = gsiNames.map { indexName =>
        indexName -> overallUsage.byTarget.collectFirst {
          case (DynamoDbTarget.GlobalSecondaryIndex(_, `indexName`), totals) => totals
        }.getOrElse(DynamoDbTargetUsageTotals())
      }.toMap

      val overallSummary = Vector(
        TrialSummaryValue(DemoMetric.TotalReadCapacityUnits, overallUsage.overall.readCapacityUnits),
        TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, overallUsage.overall.writeCapacityUnits),
        TrialSummaryValue(DemoMetric.TotalStorageByteTicks, BigDecimal(overallTimeBasedUsage.overallStorageByteTicks)),
        TrialSummaryValue(DemoMetric.FinalStorageBytes, BigDecimal(overallTimeBasedUsage.endingOverallStorageBytes)),
        TrialSummaryValue(DemoMetric.TotalEstimatedCost, overallCost.totalCost),
        TrialSummaryValue(DemoMetric.TotalCrossRegionTransferBytes, BigDecimal(transferTotals.overall.totalBytes)),
        TrialSummaryValue(DemoMetric.TotalCrossRegionTransferCost, transferCostBreakdown.totalCost)
      ) ++ gsiNames.flatMap { indexName =>
        val totals = gsiAggUsage(indexName)
        Vector(
          TrialSummaryValue(DemoMetric.TotalGsiReadCapacityUnits(indexName), totals.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalGsiWriteCapacityUnits(indexName), totals.writeCapacityUnits)
        )
      }

      val regionTransferCost: Map[String, BigDecimal] =
        regions.map { r =>
          val c = transferCostBreakdown.costByDirectionalPair
            .collect { case ((src, _), cost) if src == r => cost }
            .foldLeft(BigDecimal(0))(_ + _)
          r -> c
        }.toMap

      val perRegionSummary = regions.flatMap { r =>
        val usage = regionUsage.getOrElse(r, DynamoDbUsageTotals())
        val timeBased = regionTimeBasedUsage.getOrElse(r, DynamoDbTimeBasedUsageTotals())
        val cost = regionCost.getOrElse(r, DynamoDbCostBreakdown(BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0), BigDecimal(0)))
        Vector(
          TrialSummaryValue(DemoMetric.TotalRegionReadCapacityUnits(r), usage.overall.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalRegionWriteCapacityUnits(r), usage.overall.writeCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalRegionReplicatedWriteCapacityUnits(r), usage.overall.replicatedWriteCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalRegionStorageByteTicks(r), BigDecimal(timeBased.overallStorageByteTicks)),
          TrialSummaryValue(DemoMetric.TotalRegionFinalStorageBytes(r), BigDecimal(timeBased.endingOverallStorageBytes)),
          TrialSummaryValue(DemoMetric.TotalRegionEstimatedCost(r), cost.totalCost),
          TrialSummaryValue(DemoMetric.TotalRegionWriteCapacityCost(r), cost.writeCapacityCost),
          TrialSummaryValue(DemoMetric.TotalRegionReplicatedWriteCapacityCost(r), cost.replicatedWriteCapacityCost),
          TrialSummaryValue(DemoMetric.TotalRegionTransferCost(r), regionTransferCost.getOrElse(r, BigDecimal(0)))
        )
      }

      val perLinkSummary: Vector[TrialSummaryValue] =
        transferAcc.byTickAndLink
          .foldLeft(Map.empty[(String, String), Long]) {
            case (m, ((_, link), bytes)) => m.updated(link, m.getOrElse(link, 0L) + bytes)
          }
          .toVector
          .sortBy { case ((src, dst), _) => s"$src:$dst" }
          .map { case ((src, dst), bytes) =>
            TrialSummaryValue(DemoMetric.CrossRegionTransferBytes(src, dst), BigDecimal(bytes))
          }

      val aggregateRetItemAcc: RetItemAcc =
        retItemRegionAcc.values.foldLeft(Map.empty: RetItemAcc) { (agg, regionAcc) =>
          regionAcc.foldLeft(agg) { case (a, (key, count)) =>
            a.updated(key, a.getOrElse(key, 0L) + count)
          }
        }
      val retItemPoints: Vector[SimulationTimeSeriesPoint] =
        aggregateRetItemAcc.map { case ((op, tick), count) =>
          SimulationTimeSeriesPoint(tick, DemoMetric.ReturnedItemCount(op), BigDecimal(count))
        }.toVector

      val latencyPoints: Vector[SimulationTimeSeriesPoint] =
        regions.flatMap { r =>
          latencyRegionAcc.getOrElse(r, Map.empty).map { case (tick, maxLagMs) =>
            SimulationTimeSeriesPoint(tick, DemoMetric.ReplicationLatency(r), BigDecimal(maxLagMs))
          }.toVector
        }

      val aggregateLatencySampleAcc: LatencySampleAcc =
        latencySampleRegionAcc.values.foldLeft(Map.empty: LatencySampleAcc) { (agg, regionAcc) =>
          regionAcc.foldLeft(agg) { case (a, (key, samples)) =>
            a.updated(key, a.getOrElse(key, Vector.empty) ++ samples)
          }
        }
      val requestLatencyPoints = latencyPercentileTimeSeries(aggregateLatencySampleAcc)

      TrialResult(
        scenarioId = config.scenarioId,
        trialId = run.trialId,
        timeSeries = (aggregateTimeSeries ++ perRegionTimeSeries ++ transferTimeSeries ++ transferCostTimeSeries ++ retItemPoints ++ latencyPoints ++ requestLatencyPoints)
          .filter(_.tick <= config.simulationTicks),
        summary = overallSummary ++ perRegionSummary ++ perLinkSummary
      )

  // ── Merge helpers ───────────────────────────────────────────────────────────

  private def mergeUsageTotals(allUsage: Vector[DynamoDbUsageTotals]): DynamoDbUsageTotals =
    allUsage.foldLeft(DynamoDbUsageTotals()) { (acc, u) =>
      DynamoDbUsageTotals(
        overall = DynamoDbTargetUsageTotals(
          readCapacityUnits = acc.overall.readCapacityUnits + u.overall.readCapacityUnits,
          writeCapacityUnits = acc.overall.writeCapacityUnits + u.overall.writeCapacityUnits,
          replicatedWriteCapacityUnits = acc.overall.replicatedWriteCapacityUnits + u.overall.replicatedWriteCapacityUnits
        ),
        byTarget = mergeByTarget(acc.byTarget, u.byTarget)
      )
    }

  private def mergeTimeBasedUsageTotals(all: Vector[DynamoDbTimeBasedUsageTotals]): DynamoDbTimeBasedUsageTotals =
    all.foldLeft(DynamoDbTimeBasedUsageTotals()) { (acc, t) =>
      DynamoDbTimeBasedUsageTotals(
        overallStorageByteTicks = acc.overallStorageByteTicks + t.overallStorageByteTicks,
        endingOverallStorageBytes = acc.endingOverallStorageBytes + t.endingOverallStorageBytes
      )
    }

  private def mergeByTarget(
    a: Map[DynamoDbTarget, DynamoDbTargetUsageTotals],
    b: Map[DynamoDbTarget, DynamoDbTargetUsageTotals]
  ): Map[DynamoDbTarget, DynamoDbTargetUsageTotals] =
    (a.keySet ++ b.keySet).map { target =>
      val aT = a.getOrElse(target, DynamoDbTargetUsageTotals())
      val bT = b.getOrElse(target, DynamoDbTargetUsageTotals())
      target -> DynamoDbTargetUsageTotals(
        readCapacityUnits = aT.readCapacityUnits + bT.readCapacityUnits,
        writeCapacityUnits = aT.writeCapacityUnits + bT.writeCapacityUnits,
        replicatedWriteCapacityUnits = aT.replicatedWriteCapacityUnits + bT.replicatedWriteCapacityUnits
      )
    }.toMap

  private[thermostatfleet] def managementEventsFor(
                                                    simulationTicks: Long,
                                                    schedule: ReconfigurationSchedule
                                                  ): Iterator[TimedElement[DynamoDbManagementEvent]] =
    val eventsByTick = schedule.events.map(event => event.eventTime.ticks -> event).toMap
    (1L to simulationTicks).iterator.flatMap { tick =>
      Iterator.single(TimedControlEvent.Tick(SimTime.of(tick)): TimedElement[DynamoDbManagementEvent]) ++
        eventsByTick.get(tick).iterator
    } ++ Iterator.single(TimedControlEvent.Tick(SimTime.of(simulationTicks + 1L)): TimedElement[DynamoDbManagementEvent])

  // ── Request generation ──────────────────────────────────────────────────────

  private[thermostatfleet] def generateRequestsForRegion(
    config: ThermostatFleetScenarioConfig,
    region: RegionFleetConfig,
    rng: UniformRandomProvider
  ): Iterator[TimedElement[DynamoDBRequest]] =
    val telemetryRng = RandomSource.KISS.create(rng.nextLong())
    val queryRng = RandomSource.KISS.create(rng.nextLong())
    val scanRng = RandomSource.KISS.create(rng.nextLong())
    val stormRng = RandomSource.KISS.create(rng.nextLong())

    val baseQuerySampler = poissonSampler(config.customerSupportQueryRatePerTick, queryRng)
    val baseScanSampler = poissonSampler(config.fleetDashboardScanRatePerTick, scanRng)

    var alertStormTicksRemaining = 0

    (1L to config.simulationTicks).iterator.flatMap { tick =>
      val isAlertStorm =
        if alertStormTicksRemaining > 0 then
          alertStormTicksRemaining -= 1
          true
        else if stormRng.nextDouble() < config.alertStormProbabilityPerTick then
          alertStormTicksRemaining = config.alertStormDurationTicks - 1
          true
        else
          false

      val fleetSize = math.max(1L, region.initialDeviceCount + (region.deviceGrowthPerTick * tick).toLong)
      val spikeMultiplier = computeSpikeMultiplier(tick, config)
      val effectiveRate = config.telemetryReportsPerDevicePerTick * spikeMultiplier * fleetSize.toDouble
      val telemetrySampler = poissonSampler(effectiveRate, telemetryRng)

      val stormCount =
        if isAlertStorm then
          poissonSampler(
            config.telemetryReportsPerDevicePerTick * (config.alertStormWriteMultiplier - 1.0) * fleetSize.toDouble,
            telemetryRng
          )()
        else 0

      val telemetryCount = telemetrySampler() + stormCount

      Iterator.single(TimedControlEvent.Tick(SimTime.of(tick)): TimedElement[DynamoDBRequest]) ++
        Iterator.fill(telemetryCount) {
          PutItemRequest(eventTime = SimTime.of(tick), usecase = config.scenarioId, itemBytes = config.telemetryItemMeanBytes): TimedElement[DynamoDBRequest]
        } ++
        Iterator.fill(baseQuerySampler()) {
          QueryRequest(
            eventTime = SimTime.of(tick),
            usecase = config.scenarioId,
            target = DynamoDbReadTarget.GlobalSecondaryIndex(
              config.tableName,
              ThermostatFleetScenarioConfig.CustomerDevicesGsiName
            ),
            readConsistency = config.readConsistency
          ): TimedElement[DynamoDBRequest]
        } ++
        Iterator.fill(baseScanSampler()) {
          ScanRequest(
            eventTime = SimTime.of(tick),
            usecase = config.scenarioId,
            target = DynamoDbReadTarget.GlobalSecondaryIndex(
              config.tableName,
              ThermostatFleetScenarioConfig.FleetAlertsGsiName
            ),
            readConsistency = config.readConsistency
          ): TimedElement[DynamoDBRequest]
        }
    } ++ Iterator.single(TimedControlEvent.Tick(SimTime.of(config.simulationTicks + 1L)))

  private def computeSpikeMultiplier(tick: Long, config: ThermostatFleetScenarioConfig): Double =
    val (morningStart, morningEnd) = config.morningSpikePeakTickRange
    val (eveningStart, eveningEnd) = config.eveningSpikePeakTickRange

    def triangularPeak(start: Long, end: Long, multiplier: Double): Double =
      if tick < start || tick > end then 1.0
      else if start == end then multiplier
      else
        val mid = (start + end) / 2.0
        val halfWidth = (end - start) / 2.0
        1.0 + (multiplier - 1.0) * (1.0 - math.abs(tick.toDouble - mid) / halfWidth)

    math.max(
      triangularPeak(morningStart, morningEnd, config.morningSpikePeakMultiplier),
      triangularPeak(eveningStart, eveningEnd, config.eveningSpikePeakMultiplier)
    )

  // ── Table config builder ────────────────────────────────────────────────────

  private def buildTableConfig(
    config: ThermostatFleetScenarioConfig,
    tableState: TableState,
    behaviors: Map[Any, UseCaseSampler[TableState]]
  ): DynamoDbTable.Config =
    DynamoDbTable.Config(
      tableName = config.tableName,
      stateModel = tableState,
      useCaseBehaviors = behaviors,
      readConsistency = config.readConsistency,
      globalSecondaryIndexes = Vector(
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName = ThermostatFleetScenarioConfig.CustomerDevicesGsiName,
          projection = config.customerDevicesGsiProjection
        ),
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName = ThermostatFleetScenarioConfig.FleetAlertsGsiName,
          projection = DynamoDbTable.IndexProjection.Include(config.fleetAlertsGsiProjectedNonKeyBytes)
        ),
        DynamoDbTable.GlobalSecondaryIndexDefinition(
          indexName = ThermostatFleetScenarioConfig.DeviceStatusGsiName,
          projection = config.deviceStatusGsiProjection
        )
      ),
      localSecondaryIndexes = Vector(
        DynamoDbTable.LocalSecondaryIndexDefinition(
          indexName = ThermostatFleetScenarioConfig.ReadingTypeHistoryLsiName,
          projection = config.readingTypeHistoryLsiProjection
        )
      ),
      itemCollectionSizeLimitBytes = config.itemCollectionSizeLimitBytes,
      billingMode = config.billingMode,
      hotPartitionModel = config.hotPartitionModel,
      burstCapacityModel = config.burstCapacityModel,
      adaptiveCapacityModel = config.adaptiveCapacityModel,
      dynamicPartitionTopologyModel = config.dynamicPartitionTopologyModel,
      tableClass = config.tableClass
    )

  private def buildDefaultReplicationModel(seed: Long): ReplicationModel =
    val rng = RandomSource.XO_RO_SHI_RO_128_PP.create(seed ^ 0xCAFEBABEDEADBEEFL)
    ReplicationModel(
      defaultLagDistribution = Some(LogNormalDistribution.of(0.0, 1.0)),
      rng = rng
    )

  private def poissonSampler(mean: Double, rng: UniformRandomProvider): () => Int =
    if mean <= 0.0 then () => 0
    else
      val sampler: DiscreteDistribution.Sampler = PoissonDistribution.of(mean).createSampler(rng)
      () => sampler.sample()
