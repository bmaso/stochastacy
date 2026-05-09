package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.autoscaling.DynamoDbAutoScaler
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates}
import stochastacy.aws.dynamodb.table.*
import stochastacy.demo.*
import stochastacy.sim.{TimedElement, ticks}

import scala.concurrent.{ExecutionContext, Future}

final class ThermostatFleetCapstoneSingleTrialRunner()(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[MultiTableScenarioConfig]:
  import ThermostatFleetSingleTrialRunner.{PerRegionAcc, LatencySampleAcc, updatePerRegionAcc, extractTimeBasedUsage, foldLatencySampleEvent, latencyPercentileTimeSeries}

  private val helper = ThermostatFleetSingleTrialRunner()

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  private case class CapstoneMetricBucket(
    throttleCount: Int = 0,
    provisionedRcu: Option[Long] = None,
    provisionedWcu: Option[Long] = None,
    estimatedItemCount: Option[Long] = None,
    systemErrorCount: Int = 0
  )

  // (tableName, tick) -> bucket
  private type CapstoneMetricAcc = Map[(String, Long), CapstoneMetricBucket]

  private def updateCapstoneMetricAcc(
    acc: CapstoneMetricAcc,
    tableName: String,
    evt: TimedElement[TableMetricEvent]
  ): CapstoneMetricAcc =
    evt match
      case util: AdmissionMetricEvent.ProvisionedCapacityUtilization =>
        val key = (tableName, util.eventTime.ticks)
        val old = acc.getOrElse(key, CapstoneMetricBucket())
        acc.updated(key, old.copy(
          provisionedRcu = Some(util.provisionedReadCapacityUnits),
          provisionedWcu = Some(util.provisionedWriteCapacityUnits)
        ))
      case throttled: AdmissionMetricEvent.RequestThrottled =>
        val key = (tableName, throttled.eventTime.ticks)
        val old = acc.getOrElse(key, CapstoneMetricBucket())
        acc.updated(key, old.copy(throttleCount = old.throttleCount + 1))
      case item: StorageMetricEvent.EstimatedItemCount =>
        val key = (tableName, item.eventTime.ticks)
        val old = acc.getOrElse(key, CapstoneMetricBucket())
        acc.updated(key, old.copy(estimatedItemCount = Some(item.count)))
      case err: StorageMetricEvent.SystemError =>
        val key = (tableName, err.eventTime.ticks)
        val old = acc.getOrElse(key, CapstoneMetricBucket())
        acc.updated(key, old.copy(systemErrorCount = old.systemErrorCount + 1))
      case _ => acc

  override def runTrial(config: MultiTableScenarioConfig, run: TrialRunConfig): Future[TrialResult] =
    val n = config.tables.size

    val autoScalers: Vector[Option[DynamoDbAutoScaler]] = config.tables.map { entry =>
      entry.config.autoScalerPolicy.flatMap { policy =>
        entry.config.billingMode match
          case p: DynamoDbTable.BillingMode.Provisioned => Some(new DynamoDbAutoScaler(policy, p))
          case _                                        => None
      }
    }

    type TaggedCons   = (String, TimedElement[DynamoDbConsumptionEvent])
    type TaggedMetric = (String, TimedElement[TableMetricEvent])

    val consFold = Sink.fold[Map[String, PerRegionAcc], TaggedCons](Map.empty) {
      case (acc, (name, evt)) =>
        acc.updated(name, updatePerRegionAcc(acc.getOrElse(name, PerRegionAcc()), evt))
    }

    val metricFold = Sink.fold[CapstoneMetricAcc, TaggedMetric](Map.empty) {
      case (acc, (name, evt)) => updateCapstoneMetricAcc(acc, name, evt)
    }

    val latSampleFold = Sink.fold[LatencySampleAcc, TaggedMetric](Map.empty) {
      case (acc, (_, evt)) => foldLatencySampleEvent(acc, evt)
    }

    val (consAccF, metricAccF, latSampleAccF) = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consFold, metricFold, latSampleFold)((c, m, l) => (c, m, l)) {
        implicit b => (consSink, metricSink, latSampleSink) =>
          import GraphDSL.Implicits.*
          val consMerge   = b.add(Merge[TaggedCons](n))
          val metricMerge = b.add(Merge[TaggedMetric](n))

          config.tables.zipWithIndex.foreach { case (entry, i) =>
            val masterRng   = RandomSource.KISS.create(run.seed ^ (i.toLong * 0x9E3779B97F4A7C15L))
            val reqRng      = RandomSource.KISS.create(masterRng.nextLong())
            val bhvRng      = RandomSource.KISS.create(masterRng.nextLong())
            val region      = entry.config.regions.head
            val state       = SummaryTableState(0L, 0L)
            val behavior    = ThermostatFleetBehavior(entry.config, bhvRng,
                                region.initialDeviceCount, region.deviceGrowthPerTick)
            val behaviors: Map[Any, UseCaseSampler[TableState]] =
              Map(entry.config.scenarioId -> behavior)
            val tableConfig     = helper.buildTableConfig(entry.config, state, behaviors)
            val reqSrc          = Source.fromIterator(() => helper.generateRequestsForRegion(entry.config, region, reqRng))
            val consTagFlow     = b.add(Flow[TimedElement[DynamoDbConsumptionEvent]].map(e => (entry.tableName, e)))
            val metricTagFlow   = b.add(Flow[TimedElement[TableMetricEvent]].map(e => (entry.tableName, e)))
            val autoScalerOpt   = autoScalers(i)
            val scheduleOpt     = entry.config.reconfigurationSchedule.filter(_.events.nonEmpty)

            (autoScalerOpt, scheduleOpt) match
              case (Some(autoScaler), _) =>
                val table = b.add(DynamoDbTable.componentOfManaged(tableConfig))
                val bcast = b.add(Broadcast[TimedElement[TableMetricEvent]](2))
                reqSrc                             ~> table.requestIn
                b.add(autoScaler.managementSource) ~> table.managementIn
                table.responseOut                  ~> b.add(Sink.ignore)
                table.consumptionOut               ~> consTagFlow ~> consMerge.in(i)
                table.metricOut                    ~> bcast.in
                bcast.out(0)                       ~> metricTagFlow ~> metricMerge.in(i)
                bcast.out(1)                       ~> b.add(autoScaler.metricSink)

              case (None, Some(schedule)) =>
                val table   = b.add(DynamoDbTable.componentOfManaged(tableConfig))
                val mgmtSrc = Source.fromIterator(() => helper.managementEventsFor(entry.config.simulationTicks, schedule))
                reqSrc   ~> table.requestIn
                mgmtSrc  ~> table.managementIn
                table.responseOut    ~> b.add(Sink.ignore)
                table.consumptionOut ~> consTagFlow   ~> consMerge.in(i)
                table.metricOut      ~> metricTagFlow ~> metricMerge.in(i)

              case (None, None) =>
                val table = b.add(DynamoDbTable.componentOf(tableConfig))
                reqSrc     ~> table.in
                table.out0 ~> b.add(Sink.ignore)
                table.out1 ~> consTagFlow   ~> consMerge.in(i)
                table.out2 ~> metricTagFlow ~> metricMerge.in(i)
          } // end foreach

          val metricBcast = b.add(Broadcast[TaggedMetric](2))
          consMerge.out      ~> consSink
          metricMerge.out    ~> metricBcast.in
          metricBcast.out(0) ~> metricSink
          metricBcast.out(1) ~> latSampleSink
          ClosedShape
      }
    ).run()

    for
      consAcc      <- consAccF
      metricAcc    <- metricAccF
      latSampleAcc <- latSampleAccF
    yield
      autoScalers.flatten.foreach(_.stop())

      val timeSeries = config.tables.toVector.flatMap { entry =>
        val acc   = consAcc.getOrElse(entry.tableName, PerRegionAcc())
        val rates = entry.config.pricingSchedule.ratesAt(
                      entry.config.regions.head.regionName, config.simulationTicks)
        buildCapstoneTimeSeries(acc, rates, entry.config.tableClass, entry.tableName, metricAcc)
      }

      val summary = config.tables.toVector.flatMap { entry =>
        val acc  = consAcc.getOrElse(entry.tableName, PerRegionAcc())
        val rates = entry.config.pricingSchedule.ratesAt(
                      entry.config.regions.head.regionName, config.simulationTicks)
        val tb   = extractTimeBasedUsage(acc)
        val cost = DynamoDbCostBreakdown.price(
                     DynamoDbPricingInputs(usage = acc.usageTotals, timeBasedUsage = tb),
                     rates, entry.config.tableClass)
        Vector(
          TrialSummaryValue(DemoMetric.TableTotalReadCapacityUnits(entry.tableName),
            acc.usageTotals.overall.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TableTotalWriteCapacityUnits(entry.tableName),
            acc.usageTotals.overall.writeCapacityUnits),
          TrialSummaryValue(DemoMetric.TableTotalStorageByteTicks(entry.tableName),
            BigDecimal(tb.overallStorageByteTicks)),
          TrialSummaryValue(DemoMetric.TableFinalStorageBytes(entry.tableName),
            BigDecimal(tb.endingOverallStorageBytes)),
          TrialSummaryValue(DemoMetric.TableTotalEstimatedCost(entry.tableName),
            cost.totalCost)
        )
      }

      TrialResult(config.scenarioId, run.trialId, timeSeries ++ latencyPercentileTimeSeries(latSampleAcc), summary)

  private def buildCapstoneTimeSeries(
    acc: PerRegionAcc,
    pricingRates: DynamoDbPricingRates,
    tableClass: DynamoDbTable.TableClass,
    tableName: String,
    metricAcc: CapstoneMetricAcc
  ): Vector[SimulationTimeSeriesPoint] =
    val sortedTicks  = acc.perTickBuckets.keys.toVector.sorted
    var cumRead      = BigDecimal(0)
    var cumWrite     = BigDecimal(0)
    var cumStorage   = 0L
    var cumByteTicks = BigInt(0)

    sortedTicks.flatMap { tick =>
      val bkt       = acc.perTickBuckets(tick)
      cumRead      += bkt.readUnits
      cumWrite     += bkt.writeUnits
      cumStorage   += bkt.storageByteDelta
      cumByteTicks += BigInt(math.max(0L, cumStorage))
      val r    = pricingRates.forClass(tableClass)
      val cost = (cumRead  * r.readCapacityUnitPrice) +
                 (cumWrite * r.writeCapacityUnitPrice) +
                 (BigDecimal(cumByteTicks) * r.storagePricePerGiBSecond / bytesPerGiB)

      val base = Vector(
        SimulationTimeSeriesPoint(tick, DemoMetric.TableReadCapacityUnits(tableName), bkt.readUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableWriteCapacityUnits(tableName), bkt.writeUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableStorageBytes(tableName),
          BigDecimal(math.max(0L, cumStorage))),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableCumulativeEstimatedCost(tableName), cost)
      )

      val metricBucket = metricAcc.getOrElse((tableName, tick), CapstoneMetricBucket())
      val throttlePt   = SimulationTimeSeriesPoint(tick, DemoMetric.TableThrottleCount(tableName),
                           BigDecimal(metricBucket.throttleCount))
      val provPts      = metricBucket.provisionedRcu.map { rcu =>
        Vector(
          SimulationTimeSeriesPoint(tick, DemoMetric.TableProvisionedReadCapacityUnits(tableName),
            BigDecimal(rcu)),
          SimulationTimeSeriesPoint(tick, DemoMetric.TableProvisionedWriteCapacityUnits(tableName),
            BigDecimal(metricBucket.provisionedWcu.getOrElse(0L)))
        )
      }.getOrElse(Vector.empty)
      val estItemPts   = metricBucket.estimatedItemCount.map { count =>
        SimulationTimeSeriesPoint(tick, DemoMetric.TableEstimatedItemCount(tableName), BigDecimal(count))
      }.toVector

      val sysErrPt = SimulationTimeSeriesPoint(tick, DemoMetric.TableSystemErrorCount(tableName),
                       BigDecimal(metricBucket.systemErrorCount))

      base ++ Vector(throttlePt, sysErrPt) ++ provPts ++ estItemPts
    }.toVector
