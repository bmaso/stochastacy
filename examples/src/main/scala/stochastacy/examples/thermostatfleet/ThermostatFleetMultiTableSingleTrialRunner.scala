package stochastacy.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates}
import stochastacy.aws.dynamodb.table.*
import stochastacy.demo.*
import stochastacy.sim.TimedElement
import stochastacy.workload.WorkloadRequestStream

import scala.concurrent.{ExecutionContext, Future}

final class ThermostatFleetMultiTableSingleTrialRunner()(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[MultiTableScenarioConfig]:
  import ThermostatFleetSingleTrialRunner.{PerRegionAcc, updatePerRegionAcc, extractTimeBasedUsage}

  // Access package-private buildTableConfig and generateRequestsForRegion via a helper instance.
  private val helper = ThermostatFleetSingleTrialRunner()

  private val bytesPerGiB = BigDecimal(1024).pow(3)

  override def runTrial(config: MultiTableScenarioConfig, run: TrialRunConfig): Future[TrialResult] =
    val n = config.tables.size
    type TaggedCons = (String, TimedElement[DynamoDbConsumptionEvent])

    val consFold = Sink.fold[Map[String, PerRegionAcc], TaggedCons](Map.empty) {
      case (acc, (name, evt)) =>
        acc.updated(name, updatePerRegionAcc(acc.getOrElse(name, PerRegionAcc()), evt))
    }

    val consAccF = RunnableGraph.fromGraph(
      GraphDSL.createGraph(consFold) { implicit b => consSink =>
        import GraphDSL.Implicits.*
        val merge = b.add(Merge[TaggedCons](n))

        config.tables.zipWithIndex.foreach { case (entry, i) =>
          val masterRng = RandomSource.KISS.create(run.seed ^ (i.toLong * 0x9E3779B97F4A7C15L))
          val reqRng    = RandomSource.KISS.create(masterRng.nextLong())
          val bhvRng    = RandomSource.KISS.create(masterRng.nextLong())
          val region    = entry.config.regions.head
          val state     = SummaryTableState(0L, 0L)
          val behavior  = ThermostatFleetBehavior(entry.config, bhvRng,
                            region.initialDeviceCount, region.deviceGrowthPerTick)
          val behaviors: Map[Any, UseCaseSampler[TableState]] =
            Map(entry.config.scenarioId -> behavior)
          val tableComp = b.add(DynamoDbTable.componentOf(helper.buildTableConfig(entry.config, state, behaviors)))
          val tagFlow   = b.add(Flow[TimedElement[DynamoDbConsumptionEvent]].map(e => (entry.tableName, e)))

          Source.fromIterator(() => WorkloadRequestStream(entry.config.toWorkloadDefinition(region), reqRng, entry.config.simulationTicks)) ~> tableComp.in
          tableComp.out0 ~> b.add(Sink.ignore)
          tableComp.out1 ~> tagFlow ~> merge.in(i)
          tableComp.out2 ~> b.add(Sink.ignore)
        }

        merge.out ~> consSink
        ClosedShape
      }
    ).run()

    consAccF.map { consAcc =>
      val timeSeries = config.tables.toVector.flatMap { entry =>
        val acc   = consAcc.getOrElse(entry.tableName, PerRegionAcc())
        val rates = entry.config.pricingSchedule.ratesAt(
                      entry.config.regions.head.regionName, config.simulationTicks)
        buildPerTableTimeSeries(acc, rates, entry.config.tableClass, entry.tableName)
      }
      val summary = config.tables.toVector.flatMap { entry =>
        val acc   = consAcc.getOrElse(entry.tableName, PerRegionAcc())
        val rates = entry.config.pricingSchedule.ratesAt(
                      entry.config.regions.head.regionName, config.simulationTicks)
        val tb    = extractTimeBasedUsage(acc)
        val cost  = DynamoDbCostBreakdown.price(
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
      TrialResult(config.scenarioId, run.trialId, timeSeries, summary)
    }

  private def buildPerTableTimeSeries(
    acc: PerRegionAcc,
    pricingRates: DynamoDbPricingRates,
    tableClass: DynamoDbTable.TableClass,
    tableName: String
  ): Vector[SimulationTimeSeriesPoint] =
    val sortedTicks = acc.perTickBuckets.keys.toVector.sorted
    var cumRead = BigDecimal(0); var cumWrite = BigDecimal(0)
    var cumStorage = 0L; var cumByteTicks = BigInt(0)
    sortedTicks.flatMap { tick =>
      val bkt       = acc.perTickBuckets(tick)
      cumRead      += bkt.readUnits
      cumWrite     += bkt.writeUnits
      cumStorage   += bkt.storageByteDelta
      cumByteTicks += BigInt(math.max(0L, cumStorage))
      val r    = pricingRates.forClass(tableClass)
      val cost = (cumRead * r.readCapacityUnitPrice) +
                 (cumWrite * r.writeCapacityUnitPrice) +
                 (BigDecimal(cumByteTicks) * r.storagePricePerGiBSecond / bytesPerGiB)
      Vector(
        SimulationTimeSeriesPoint(tick, DemoMetric.TableReadCapacityUnits(tableName), bkt.readUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableWriteCapacityUnits(tableName), bkt.writeUnits),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableStorageBytes(tableName),
          BigDecimal(math.max(0L, cumStorage))),
        SimulationTimeSeriesPoint(tick, DemoMetric.TableCumulativeEstimatedCost(tableName), cost)
      )
    }.toVector
