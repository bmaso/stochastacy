package stochastacy.examples.ordertracking

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.commons.statistics.distribution.{DiscreteDistribution, PoissonDistribution}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.pricing.{DynamoDbCostBreakdown, DynamoDbPricingInputs, DynamoDbPricingRates}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.usage.{DynamoDbTargetUsageTotals, DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}
import stochastacy.demo.*
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement, TimedEvent, ticks}

import scala.concurrent.{ExecutionContext, Future}

final class OrderTrackingSingleTrialRunner(
                                            pricingRates: DynamoDbPricingRates = DynamoDbPricingRates.phase1Default
                                          )(using ActorSystem, Materializer, ExecutionContext)
    extends SingleTrialRunner[OrderTrackingScenarioConfig]:

  override def runTrial(
                         config: OrderTrackingScenarioConfig,
                         run: TrialRunConfig
                       ): Future[TrialResult] =
    val rng = RandomSource.KISS.create(run.seed)
    val tableState = SummaryTableState(
      initialItemCount = config.initialItemCount,
      initialTotalItemBytes = config.initialItemCount * config.initialAverageItemBytes
    )
    val behaviors: Map[Any, UseCaseSampler[TableState]] =
      Map(config.scenarioId -> OrderTrackingBehavior(config, rng))

    val requestSource = Source(generateRequests(config, rng))

    val materialized =
      runTable(
        requestSource = requestSource,
        config = config,
        tableState = tableState,
        behaviors = behaviors
      )
    val responseFuture = materialized._1
    val consumptionFuture = materialized._2
    val metricsFuture = materialized._3

    for
      _ <- responseFuture
      timedConsumption <- consumptionFuture
      _ <- metricsFuture
    yield buildTrialResult(
      scenarioId = config.scenarioId,
      trialId = run.trialId,
      configuredGlobalSecondaryIndexes = config.globalSecondaryIndexNames,
      timedConsumption = timedConsumption.collect {
        case evt: DynamoDbConsumptionEvent => evt
        case tick: TimedControlEvent => tick
      }
    )

  private def generateRequests(
                                config: OrderTrackingScenarioConfig,
                                rng: UniformRandomProvider
                              ): Vector[TimedElement[DynamoDBRequest]] =
    val createSampler = poissonSampler(config.createRatePerTick, rng)
    val fetchSampler = poissonSampler(config.fetchRatePerTick, rng)
    val updateSampler = poissonSampler(config.updateRatePerTick, rng)
    val deleteSampler = poissonSampler(config.deleteRatePerTick, rng)
    val tableQuerySampler = poissonSampler(config.tableQueryRatePerTick, rng)
    val tableScanSampler = poissonSampler(config.tableScanRatePerTick, rng)
    val gsiQuerySampler = poissonSampler(config.gsiQueryRatePerTick, rng)
    val gsiScanSampler = poissonSampler(config.gsiScanRatePerTick, rng)

    (1L to config.simulationTicks).foldLeft(Vector.empty[TimedElement[DynamoDBRequest]]) {
      case (acc, tick) =>
        acc ++ Vector(
          TimedControlEvent.Tick(SimTime.of(tick))
        ) ++
          Vector.fill(createSampler()) {
            PutItemRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              itemBytes = sampleBytes(config.newOrderMeanBytes, rng)
            ): TimedElement[DynamoDBRequest]
          } ++
          Vector.fill(fetchSampler()) {
            GetItemRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId
            ): TimedElement[DynamoDBRequest]
          } ++
          Vector.fill(updateSampler()) {
            UpdateItemRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              itemBytes = sampleBytes(config.updatedOrderMeanBytes, rng)
            ): TimedElement[DynamoDBRequest]
          } ++
          Vector.fill(deleteSampler()) {
            DeleteItemRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId
            ): TimedElement[DynamoDBRequest]
          } ++
          (0 until tableQuerySampler()).map { _ =>
            QueryRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              target = DynamoDbReadTarget.Table(config.tableName),
              readConsistency = config.readConsistency
            ): TimedElement[DynamoDBRequest]
          } ++
          (0 until tableScanSampler()).map { _ =>
            ScanRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              target = DynamoDbReadTarget.Table(config.tableName),
              readConsistency = config.readConsistency
            ): TimedElement[DynamoDBRequest]
          } ++
          (0 until gsiQuerySampler()).map { sampleIndex =>
            QueryRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              target = nextGlobalSecondaryIndexTarget(config, tick, sampleIndex),
              readConsistency = ReadConsistency.EventuallyConsistent
            ): TimedElement[DynamoDBRequest]
          } ++
          (0 until gsiScanSampler()).map { sampleIndex =>
            ScanRequest(
              eventTime = SimTime.of(tick),
              usecase = config.scenarioId,
              target = nextGlobalSecondaryIndexTarget(config, tick, sampleIndex + 13),
              readConsistency = ReadConsistency.EventuallyConsistent
            ): TimedElement[DynamoDBRequest]
          }
    } :+ TimedControlEvent.Tick(SimTime.of(config.simulationTicks + 1L))

  private def nextGlobalSecondaryIndexTarget(
                                              config: OrderTrackingScenarioConfig,
                                              tick: Long,
                                              sampleIndex: Int
                                            ): DynamoDbReadTarget =
    val names = config.globalSecondaryIndexNames
    require(names.nonEmpty, "nextGlobalSecondaryIndexTarget requires at least one configured global secondary index")
    val indexName = names(((tick - 1L + sampleIndex.toLong) % names.size).toInt)
    DynamoDbReadTarget.GlobalSecondaryIndex(config.tableName, indexName)

  private def poissonSampler(
                              mean: Double,
                              rng: UniformRandomProvider
                            ): () => Int =
    if mean <= 0.0 then () => 0
    else
      val sampler: DiscreteDistribution.Sampler =
        PoissonDistribution.of(mean).createSampler(rng)
      () => sampler.sample()

  private def sampleBytes(
                           mean: Long,
                           rng: UniformRandomProvider
                         ): Long =
    val scale = BigDecimal(0.75 + (rng.nextDouble() * 0.5))
    math.max(1L, (BigDecimal(mean) * scale).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)

  private def runTable(
                        requestSource: Source[TimedElement[DynamoDBRequest], ?],
                        config: OrderTrackingScenarioConfig,
                        tableState: TableState,
                        behaviors: Map[Any, UseCaseSampler[TableState]]
                      ): (
                        Future[Seq[TimedEvent]],
                        Future[Seq[TimedEvent]],
                        Future[Seq[TimedEvent]]
                      ) =
    val responseSink = Sink.seq[TimedEvent]
    val resourceSink = Sink.seq[TimedEvent]
    val metricsSink = Sink.seq[TimedEvent]

    RunnableGraph.fromGraph(
      GraphDSL.createGraph(responseSink, resourceSink, metricsSink)(
        (r, c, m) => (r, c, m)
      ) { implicit b =>
        (respSink, consSink, metrSink) =>
          import GraphDSL.Implicits._

          val table = b.add(
            DynamoDbTable.componentOf(
              DynamoDbTable.Config(
                tableName = config.tableName,
                stateModel = tableState,
                useCaseBehaviors = behaviors,
                readConsistency = config.readConsistency,
                globalSecondaryIndexes = config.globalSecondaryIndexNames.map { indexName =>
                  DynamoDbTable.GlobalSecondaryIndexDefinition(indexName, tableState)
                },
                localSecondaryIndexes = config.localSecondaryIndexNames.map { indexName =>
                  DynamoDbTable.LocalSecondaryIndexDefinition(indexName, tableState)
                }
              )
            )
          )

          requestSource ~> table.in
          table.out0 ~> respSink
          table.out1 ~> consSink
          table.out2 ~> metrSink

          ClosedShape
      }
    ).run()

  private def buildTrialResult(
                                scenarioId: String,
                                trialId: Int,
                                configuredGlobalSecondaryIndexes: Vector[String],
                                timedConsumption: Seq[TimedElement[DynamoDbConsumptionEvent]]
                              ): TrialResult =
    val usageTotals =
      timedConsumption.collect {
        case evt: DynamoDbConsumptionEvent => evt
      }.foldLeft(DynamoDbUsageTotals())(DynamoDbUsageTotals.accumulate)

    val timeBasedTotals = DynamoDbTimeBasedUsageTotals.fromTimedEvents(timedConsumption)
    val totalCostBreakdown =
      DynamoDbCostBreakdown.price(
        inputs = DynamoDbPricingInputs(
          usage = usageTotals,
          timeBasedUsage = timeBasedTotals
        ),
        rates = pricingRates
      )

    val gsiUsageTotals = globalSecondaryIndexUsageTotals(usageTotals, configuredGlobalSecondaryIndexes)

    TrialResult(
      scenarioId = scenarioId,
      trialId = trialId,
      timeSeries = buildTimeSeries(timedConsumption, configuredGlobalSecondaryIndexes),
      summary = Vector(
        TrialSummaryValue(DemoMetric.TotalReadCapacityUnits, usageTotals.overall.readCapacityUnits),
        TrialSummaryValue(DemoMetric.TotalWriteCapacityUnits, usageTotals.overall.writeCapacityUnits),
        TrialSummaryValue(DemoMetric.TotalStorageByteTicks, BigDecimal(timeBasedTotals.overallStorageByteTicks)),
        TrialSummaryValue(DemoMetric.FinalStorageBytes, BigDecimal(timeBasedTotals.endingOverallStorageBytes)),
        TrialSummaryValue(DemoMetric.TotalEstimatedCost, totalCostBreakdown.totalCost)
      ) ++ configuredGlobalSecondaryIndexes.flatMap { indexName =>
        val totals = gsiUsageTotals(indexName)
        Vector(
          TrialSummaryValue(DemoMetric.TotalGsiReadCapacityUnits(indexName), totals.readCapacityUnits),
          TrialSummaryValue(DemoMetric.TotalGsiWriteCapacityUnits(indexName), totals.writeCapacityUnits)
        )
      }.sortBy(_.metric.sortKey)
    )

  private def globalSecondaryIndexUsageTotals(
                                               usageTotals: DynamoDbUsageTotals,
                                               configuredGlobalSecondaryIndexes: Vector[String]
                                             ): Map[String, DynamoDbTargetUsageTotals] =
    configuredGlobalSecondaryIndexes.sorted.map { indexName =>
      indexName -> usageTotals.byTarget.collectFirst {
        case (DynamoDbTarget.GlobalSecondaryIndex(_, `indexName`), totals) => totals
      }.getOrElse(DynamoDbTargetUsageTotals())
    }.toMap

  private def buildTimeSeries(
                               timedConsumption: Seq[TimedElement[DynamoDbConsumptionEvent]],
                               configuredGlobalSecondaryIndexes: Vector[String]
                             ): Vector[SimulationTimeSeriesPoint] =
    final case class Bucket(
                             tick: Long,
                             readUnits: BigDecimal = BigDecimal(0),
                             writeUnits: BigDecimal = BigDecimal(0),
                             gsiReadUnits: Map[String, BigDecimal] = Map.empty,
                             gsiWriteUnits: Map[String, BigDecimal] = Map.empty
                           )

    final case class State(
                            activeBucket: Option[Bucket] = None,
                            currentStorageBytes: Long = 0L,
                            cumulativeReadUnits: BigDecimal = BigDecimal(0),
                            cumulativeWriteUnits: BigDecimal = BigDecimal(0),
                            cumulativeStorageByteTicks: BigInt = BigInt(0),
                            points: Vector[SimulationTimeSeriesPoint] = Vector.empty
                          )

    def finalizeBucket(state: State): State =
      state.activeBucket match
        case None => state
        case Some(bucket) =>
          val nextRead = state.cumulativeReadUnits + bucket.readUnits
          val nextWrite = state.cumulativeWriteUnits + bucket.writeUnits
          val nextByteTicks = state.cumulativeStorageByteTicks + BigInt(state.currentStorageBytes)
          val cumulativeCost =
            priceTotal(
              readUnits = nextRead,
              writeUnits = nextWrite,
              storageByteTicks = nextByteTicks
            )

          state.copy(
            activeBucket = None,
            cumulativeReadUnits = nextRead,
            cumulativeWriteUnits = nextWrite,
            cumulativeStorageByteTicks = nextByteTicks,
            points = state.points ++ Vector(
              SimulationTimeSeriesPoint(bucket.tick, DemoMetric.ReadCapacityUnits, bucket.readUnits),
              SimulationTimeSeriesPoint(bucket.tick, DemoMetric.WriteCapacityUnits, bucket.writeUnits),
            ) ++ configuredGlobalSecondaryIndexes.sorted.flatMap { indexName =>
              Vector(
                SimulationTimeSeriesPoint(
                  bucket.tick,
                  DemoMetric.GsiReadCapacityUnits(indexName),
                  bucket.gsiReadUnits.getOrElse(indexName, BigDecimal(0))
                ),
                SimulationTimeSeriesPoint(
                  bucket.tick,
                  DemoMetric.GsiWriteCapacityUnits(indexName),
                  bucket.gsiWriteUnits.getOrElse(indexName, BigDecimal(0))
                )
              )
            } ++ Vector(
              SimulationTimeSeriesPoint(bucket.tick, DemoMetric.StorageBytes, BigDecimal(state.currentStorageBytes)),
              SimulationTimeSeriesPoint(bucket.tick, DemoMetric.CumulativeEstimatedCost, cumulativeCost)
            )
          )

    timedConsumption.foldLeft(State()) {
      case (state, tick: TimedControlEvent.Tick) =>
        finalizeBucket(state).copy(activeBucket = Some(Bucket(tick = tick.eventTime.ticks)))

      case (state, DynamoDbConsumptionEvent.ReadCapacityConsumed(_, _, target, units, _)) =>
        state.copy(
          activeBucket = state.activeBucket.map { bucket =>
            target match
              case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                bucket.copy(
                  readUnits = bucket.readUnits + units,
                  gsiReadUnits = bucket.gsiReadUnits.updated(
                    indexName,
                    bucket.gsiReadUnits.getOrElse(indexName, BigDecimal(0)) + units
                  )
                )
              case _ =>
                bucket.copy(readUnits = bucket.readUnits + units)
          }
        )

      case (state, DynamoDbConsumptionEvent.WriteCapacityConsumed(_, _, target, units)) =>
        state.copy(
          activeBucket = state.activeBucket.map { bucket =>
            target match
              case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
                bucket.copy(
                  writeUnits = bucket.writeUnits + units,
                  gsiWriteUnits = bucket.gsiWriteUnits.updated(
                    indexName,
                    bucket.gsiWriteUnits.getOrElse(indexName, BigDecimal(0)) + units
                  )
                )
              case _ =>
                bucket.copy(writeUnits = bucket.writeUnits + units)
          }
        )

      case (state, DynamoDbConsumptionEvent.StorageBytesDelta(_, _, _, bytesDelta)) =>
        state.copy(currentStorageBytes = state.currentStorageBytes + bytesDelta)

      case (state, _) =>
        state
    }.points

  private def priceTotal(
                          readUnits: BigDecimal,
                          writeUnits: BigDecimal,
                          storageByteTicks: BigInt
                        ): BigDecimal =
    val bytesPerGiB = BigDecimal(1024).pow(3)

    (readUnits * pricingRates.readCapacityUnitPrice) +
      (writeUnits * pricingRates.writeCapacityUnitPrice) +
      (BigDecimal(storageByteTicks) * pricingRates.storagePricePerGiBSecond / bytesPerGiB)

  private final case class OrderTrackingBehavior(
                                                  config: OrderTrackingScenarioConfig,
                                                  rng: UniformRandomProvider
                                                ) extends UseCaseSampler[TableState]:

    override def getItem(request: GetItemRequest, state: TableState): Option[GetItemSample] =
      if state.itemCount <= 0L || rng.nextDouble() > config.getHitProbability then None
      else
        Some(FixedGetItemSample(sampleBytes(state.averageItemBytes.getOrElse(config.initialAverageItemBytes), rng)))

    override def query(request: QueryRequest, state: TableState): QuerySample =
      sampleReadShape(request.target, state) { (evaluatedItemCount, evaluatedBytes, returnedItemCount, returnedBytes) =>
        QuerySample(
          evaluatedItemCount = evaluatedItemCount,
          evaluatedBytes = evaluatedBytes,
          returnedItemCount = returnedItemCount,
          returnedBytes = returnedBytes
        )
      }

    override def scan(request: ScanRequest, state: TableState): ScanSample =
      sampleReadShape(request.target, state) { (evaluatedItemCount, evaluatedBytes, returnedItemCount, returnedBytes) =>
        ScanSample(
          evaluatedItemCount = evaluatedItemCount,
          evaluatedBytes = evaluatedBytes,
          returnedItemCount = returnedItemCount,
          returnedBytes = returnedBytes
        )
      }

    override def putItem(request: PutItemRequest, state: TableState): PutItemSample =
      FixedPutItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = None
      )

    override def updateItem(request: UpdateItemRequest, state: TableState): UpdateItemSample =
      val previousItemBytes =
        if state.itemCount > 0L && rng.nextDouble() <= config.updateExistingProbability then state.averageItemBytes
        else None

      FixedUpdateItemSample(
        writtenItemBytes = request.itemBytes,
        previousItemBytes = previousItemBytes
      )

    override def deleteItem(request: DeleteItemRequest, state: TableState): DeleteItemSample =
      val deletedItemBytes =
        if state.itemCount > 0L && rng.nextDouble() <= config.deleteExistingProbability then state.averageItemBytes
        else None

      FixedDeleteItemSample(deletedItemBytes)

    private def sampleReadShape[A](
                                    target: DynamoDbReadTarget,
                                    state: TableState
                                  )(
                                    build: (Long, Long, Long, Long) => A
                                  ): A =
      val averageItemBytes = state.averageItemBytes.getOrElse(config.initialAverageItemBytes)
      val availableItems = state.itemCount

      if availableItems <= 0L then build(0L, 0L, 0L, 0L)
      else
        val (evaluatedBase, returnedFraction) =
          target match
            case _: DynamoDbReadTarget.Table =>
              (math.min(availableItems, 4L), BigDecimal("0.75"))
            case _: DynamoDbReadTarget.GlobalSecondaryIndex =>
              (math.min(availableItems, 6L), BigDecimal("0.65"))
            case _: DynamoDbReadTarget.LocalSecondaryIndex =>
              (math.min(availableItems, 5L), BigDecimal("0.70"))

        val evaluatedItemCount =
          math.max(1L, sampleReadItemCount(evaluatedBase))
        val evaluatedBytes = evaluatedItemCount * averageItemBytes
        val returnedItemCount =
          math.min(
            evaluatedItemCount,
            math.max(
              0L,
              (BigDecimal(evaluatedItemCount) * returnedFraction * BigDecimal(0.7 + (rng.nextDouble() * 0.6)))
                .setScale(0, BigDecimal.RoundingMode.HALF_UP)
                .toLong
            )
          )
        val returnedBytes =
          if returnedItemCount <= 0L then 0L
          else returnedItemCount * averageItemBytes

        build(evaluatedItemCount, evaluatedBytes, returnedItemCount, returnedBytes)

    private def sampleReadItemCount(maxItems: Long): Long =
      if maxItems <= 1L then 1L
      else 1L + rng.nextLong(maxItems)

  private final case class FixedGetItemSample(
                                               override val getItemBytes: Long
                                             ) extends GetItemSample

  private final case class FixedPutItemSample(
                                               override val writtenItemBytes: Long,
                                               override val previousItemBytes: Option[Long]
                                             ) extends PutItemSample

  private final case class FixedUpdateItemSample(
                                                  override val writtenItemBytes: Long,
                                                  override val previousItemBytes: Option[Long]
                                                ) extends UpdateItemSample

  private final case class FixedDeleteItemSample(
                                                  override val deletedItemBytes: Option[Long]
                                                ) extends DeleteItemSample
