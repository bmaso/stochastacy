package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{DiscreteDistribution, LogNormalDistribution, PoissonDistribution}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates}
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
    gsiWriteUnits: Map[String, BigDecimal] = Map.empty
  )

  /** Per-region streaming fold accumulator. Combines time-series, usage-totals, and time-based-usage
   *  accumulation in a single pass — avoids collecting all events into memory via Sink.seq. */
  private case class PerRegionAcc(
    usageTotals: DynamoDbUsageTotals = DynamoDbUsageTotals(),
    tbByteTicksByTarget: Map[DynamoDbTarget, BigInt] = Map.empty,
    tbCurrentBytesByTarget: Map[DynamoDbTarget, Long] = Map.empty,
    tbHasSeenTick: Boolean = false,
    activeBucket: Option[TSSBucket] = None,
    currentStorageBytes: Long = 0L,
    cumReadUnits: BigDecimal = BigDecimal(0),
    cumWriteUnits: BigDecimal = BigDecimal(0),
    cumReplWriteUnits: BigDecimal = BigDecimal(0),
    cumStorageByteTicks: BigInt = BigInt(0),
    points: Vector[SimulationTimeSeriesPoint] = Vector.empty
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

  // ── Fold helpers ─────────────────────────────────────────────────────────

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  private def finalizePerRegionBucket(
    acc: PerRegionAcc,
    gsiNames: Vector[String],
    pricingRates: DynamoDbPricingRates,
    regionName: Option[String]
  ): PerRegionAcc =
    acc.activeBucket match
      case None => acc
      case Some(bucket) =>
        val nextRead = acc.cumReadUnits + bucket.readUnits
        val nextWrite = acc.cumWriteUnits + bucket.writeUnits
        val nextReplWrite = acc.cumReplWriteUnits + bucket.replicatedWriteUnits
        val nextByteTicks = acc.cumStorageByteTicks + BigInt(math.max(0L, acc.currentStorageBytes))
        val cumulativeCost =
          (nextRead * pricingRates.readCapacityUnitPrice) +
            (nextWrite * pricingRates.writeCapacityUnitPrice) +
            (nextReplWrite * pricingRates.replicatedWriteCapacityUnitPrice) +
            (BigDecimal(nextByteTicks) * pricingRates.storagePricePerGiBSecond / bytesPerGiB)

        val (readMetric, writeMetric, storageMetric, costMetric, maybeReplMetric) =
          regionName match
            case None =>
              (DemoMetric.ReadCapacityUnits, DemoMetric.WriteCapacityUnits,
               DemoMetric.StorageBytes, DemoMetric.CumulativeEstimatedCost, None)
            case Some(r) =>
              (DemoMetric.RegionReadCapacityUnits(r), DemoMetric.RegionWriteCapacityUnits(r),
               DemoMetric.RegionStorageBytes(r), DemoMetric.RegionCumulativeEstimatedCost(r),
               Some(DemoMetric.RegionReplicatedWriteCapacityUnits(r)))

        val basePoints = Vector(
          SimulationTimeSeriesPoint(bucket.tick, readMetric, bucket.readUnits),
          SimulationTimeSeriesPoint(bucket.tick, writeMetric, bucket.writeUnits)
        ) ++ maybeReplMetric.map(m => SimulationTimeSeriesPoint(bucket.tick, m, bucket.replicatedWriteUnits)).toVector

        val gsiPoints = gsiNames.sorted.flatMap { indexName =>
          Vector(
            SimulationTimeSeriesPoint(bucket.tick, DemoMetric.GsiReadCapacityUnits(indexName),
              bucket.gsiReadUnits.getOrElse(indexName, BigDecimal(0))),
            SimulationTimeSeriesPoint(bucket.tick, DemoMetric.GsiWriteCapacityUnits(indexName),
              bucket.gsiWriteUnits.getOrElse(indexName, BigDecimal(0)))
          )
        }

        acc.copy(
          activeBucket = None,
          cumReadUnits = nextRead,
          cumWriteUnits = nextWrite,
          cumReplWriteUnits = nextReplWrite,
          cumStorageByteTicks = nextByteTicks,
          points = acc.points ++ basePoints ++ gsiPoints ++ Vector(
            SimulationTimeSeriesPoint(bucket.tick, storageMetric, BigDecimal(math.max(0L, acc.currentStorageBytes))),
            SimulationTimeSeriesPoint(bucket.tick, costMetric, cumulativeCost)
          )
        )

  private def updatePerRegionAcc(
    acc: PerRegionAcc,
    evt: TimedElement[DynamoDbConsumptionEvent],
    gsiNames: Vector[String],
    pricingRates: DynamoDbPricingRates,
    regionName: Option[String]
  ): PerRegionAcc =
    evt match
      case tick: TimedControlEvent.Tick =>
        val withFinalized = finalizePerRegionBucket(acc, gsiNames, pricingRates, regionName)
        val tbUpdated =
          if withFinalized.tbHasSeenTick then
            val nextByteTicks = withFinalized.tbCurrentBytesByTarget.foldLeft(withFinalized.tbByteTicksByTarget) {
              case (m, (target, bytes)) => m.updated(target, m.getOrElse(target, BigInt(0)) + BigInt(bytes))
            }
            withFinalized.copy(tbByteTicksByTarget = nextByteTicks)
          else withFinalized
        tbUpdated.copy(
          activeBucket = Some(TSSBucket(tick = tick.eventTime.ticks)),
          tbHasSeenTick = true
        )

      case cons: DynamoDbConsumptionEvent =>
        val acc1 = acc.copy(usageTotals = DynamoDbUsageTotals.accumulate(acc.usageTotals, cons))
        cons match
          case DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, target, units, _) =>
            acc1.copy(activeBucket = acc1.activeBucket.map { b =>
              target match
                case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                  b.copy(readUnits = b.readUnits + units,
                    gsiReadUnits = b.gsiReadUnits.updated(indexName, b.gsiReadUnits.getOrElse(indexName, BigDecimal(0)) + units))
                case _ => b.copy(readUnits = b.readUnits + units)
            })
          case DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, target, units) =>
            acc1.copy(activeBucket = acc1.activeBucket.map { b =>
              target match
                case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                  b.copy(writeUnits = b.writeUnits + units,
                    gsiWriteUnits = b.gsiWriteUnits.updated(indexName, b.gsiWriteUnits.getOrElse(indexName, BigDecimal(0)) + units))
                case _ => b.copy(writeUnits = b.writeUnits + units)
            })
          case DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed(_, _, _, units) =>
            acc1.copy(activeBucket = acc1.activeBucket.map(b => b.copy(replicatedWriteUnits = b.replicatedWriteUnits + units)))
          case DynamoDbConsumptionEvent.StorageBytesDelta(_, _, target, bytesDelta) =>
            acc1.copy(
              currentStorageBytes = acc1.currentStorageBytes + bytesDelta,
              tbCurrentBytesByTarget = acc1.tbCurrentBytesByTarget.updated(
                target, acc1.tbCurrentBytesByTarget.getOrElse(target, 0L) + bytesDelta)
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
    pricingRates: DynamoDbPricingRates
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
      val cumCost =
        (cumRead * pricingRates.readCapacityUnitPrice) +
          (cumWrite * pricingRates.writeCapacityUnitPrice) +
          (cumReplWrite * pricingRates.replicatedWriteCapacityUnitPrice) +
          (BigDecimal(cumByteTicks) * pricingRates.storagePricePerGiBSecond / bytesPerGiB)
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

    val foldSink = Sink.fold[PerRegionAcc, TimedElement[DynamoDbConsumptionEvent]](PerRegionAcc()) {
      (acc, evt) => updatePerRegionAcc(acc, evt, gsiNames, config.pricingRates, regionName = None)
    }

    val accF = RunnableGraph.fromGraph(
      GraphDSL.createGraph(foldSink) { implicit b => sink =>
        import GraphDSL.Implicits.*
        val table = b.add(DynamoDbTable.componentOf(buildTableConfig(config, tableState, behaviors)))
        Source.fromIterator(() => requestIterator) ~> table.in
        table.out0 ~> b.add(Sink.ignore)
        table.out1 ~> sink
        table.out2 ~> b.add(Sink.ignore)
        ClosedShape
      }
    ).run()

    accF.map { rawAcc =>
      val acc = finalizePerRegionBucket(rawAcc, gsiNames, config.pricingRates, regionName = None)
      val timeBasedTotals = extractTimeBasedUsage(acc)
      val costBreakdown = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(usage = acc.usageTotals, timeBasedUsage = timeBasedTotals),
        config.pricingRates
      )
      val gsiUsage = gsiNames.map { indexName =>
        indexName -> acc.usageTotals.byTarget.collectFirst {
          case (DynamoDbTarget.GlobalSecondaryIndex(_, `indexName`), totals) => totals
        }.getOrElse(DynamoDbTargetUsageTotals())
      }.toMap

      TrialResult(
        scenarioId = config.scenarioId,
        trialId = run.trialId,
        timeSeries = acc.points,
        summary = Vector(
          TrialSummaryValue(DemoMetric.TotalReadCapacityUnits, acc.usageTotals.overall.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, acc.usageTotals.overall.writeCapacityUnits),
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
    }

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
        val updatedRegion = updatePerRegionAcc(regionAcc, evt, Vector.empty, config.pricingRates, Some(region))
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

    val (mrAccF, transferAccF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(taggedConsSink, transferSink)((mc, tf) => (mc, tf)) {
        implicit b => (taggedConsSinkShape, transfersSinkShape) =>
          import GraphDSL.Implicits.*
          val globalTable = b.add(DynamoDbGlobalTable.componentOf(globalConfig))
          val consumptionMerge = b.add(Merge[TaggedConsEvent](sortedRegions.size))

          sortedRegions.zipWithIndex.foreach { case (region, idx) =>
            val r = region.regionName
            Source.fromIterator(() => requestStreamIterators(r)) ~> globalTable.regionRequestInlets(r)
            globalTable.regionResponseOutlets(r) ~> b.add(Sink.ignore)
            globalTable.regionMetricOutlets(r) ~> b.add(Sink.ignore)
            val tagger = b.add(Flow[TimedElement[DynamoDbConsumptionEvent]].map(e => (r, e)))
            globalTable.regionConsumptionOutlets(r) ~> tagger.in
            tagger.out ~> consumptionMerge.in(idx)
          }

          consumptionMerge.out ~> taggedConsSinkShape
          globalTable.transferEventsOutlet ~> transfersSinkShape
          ClosedShape
      }
    ).run()

    for
      mrAcc <- mrAccF
      transferAcc <- transferAccF
    yield
      val regions = sortedRegions.map(_.regionName)

      val finalPerRegion = mrAcc.perRegion.map { case (r, acc) =>
        r -> finalizePerRegionBucket(acc, Vector.empty, config.pricingRates, Some(r))
      }

      val regionUsage = finalPerRegion.map { case (r, acc) => r -> acc.usageTotals }
      val regionTimeBasedUsage = finalPerRegion.map { case (r, acc) => r -> extractTimeBasedUsage(acc) }
      val regionCost = regions.map { r =>
        r -> DynamoDbCostBreakdown.price(
          DynamoDbPricingInputs(
            usage = regionUsage.getOrElse(r, DynamoDbUsageTotals()),
            timeBasedUsage = regionTimeBasedUsage.getOrElse(r, DynamoDbTimeBasedUsageTotals())
          ),
          config.pricingRates
        )
      }.toMap

      val transferTotals = transferAcc.totals
      val transferCostBreakdown = CrossRegionTransferCostBreakdown.price(transferTotals, config.transferPricingRates)
      val transferTimeSeries = transferAcc.byTickAndLink.map { case ((tick, (src, dst)), bytes) =>
        SimulationTimeSeriesPoint(tick, DemoMetric.CrossRegionTransferBytes(src, dst), BigDecimal(bytes))
      }.toVector

      val overallUsage = mergeUsageTotals(regionUsage.values.toVector)
      val overallTimeBasedUsage = mergeTimeBasedUsageTotals(regionTimeBasedUsage.values.toVector)
      val overallCost = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(usage = overallUsage, timeBasedUsage = overallTimeBasedUsage),
        config.pricingRates
      )

      val aggregateTimeSeries = buildAggTimeSeries(mrAcc.aggByTick, gsiNames, config.pricingRates)
      val perRegionTimeSeries = regions.flatMap { r =>
        finalPerRegion.get(r).map(_.points).getOrElse(Vector.empty)
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
          TrialSummaryValue(DemoMetric.TotalRegionEstimatedCost(r), cost.totalCost)
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

      TrialResult(
        scenarioId = config.scenarioId,
        trialId = run.trialId,
        timeSeries = aggregateTimeSeries ++ perRegionTimeSeries ++ transferTimeSeries,
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
      dynamicPartitionTopologyModel = config.dynamicPartitionTopologyModel
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
