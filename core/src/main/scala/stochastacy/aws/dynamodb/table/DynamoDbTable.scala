package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*

object DynamoDbTable:

  private val BytesPerWriteCapacityUnitChunk = 1024L

  final case class GlobalSecondaryIndexDefinition(
                                                   indexName: String,
                                                   stateModel: TableState = SummaryTableState(0L, 0L)
                                                 )

  final case class LocalSecondaryIndexDefinition(
                                                  indexName: String,
                                                  stateModel: TableState = SummaryTableState(0L, 0L)
                                                )

  final case class Config(
                           tableName: String,
                           stateModel: TableState,
                           useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                           readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                           globalSecondaryIndexes: Vector[GlobalSecondaryIndexDefinition] = Vector.empty,
                           localSecondaryIndexes: Vector[LocalSecondaryIndexDefinition] = Vector.empty
                         ):
    Config.validate(this)

  object Config:
    private def validate(config: Config): Unit =
      val duplicateNames =
        (config.globalSecondaryIndexes.map(_.indexName) ++ config.localSecondaryIndexes.map(_.indexName))
          .groupBy(identity)
          .collect {
            case (indexName, occurrences) if occurrences.size > 1 => indexName
          }
          .toVector
          .sorted

      require(
        duplicateNames.isEmpty,
        s"Duplicate index names configured for table '${config.tableName}': ${duplicateNames.mkString(", ")}"
      )

  private enum RouteBranch:
    case BaseTable
    case GlobalSecondaryIndex(indexName: String)
    case LocalSecondaryIndex(indexName: String)

  private sealed trait InternalIndexRuntime:
    def indexName: String
    def stateModel: TableState
    def target: DynamoDbTarget

  private object InternalIndexRuntime:
    final case class GlobalSecondaryIndex(
                                           indexName: String,
                                           stateModel: TableState,
                                           target: DynamoDbTarget.GlobalSecondaryIndex
                                         ) extends InternalIndexRuntime

    final case class LocalSecondaryIndex(
                                          indexName: String,
                                          stateModel: TableState,
                                          target: DynamoDbTarget.LocalSecondaryIndex
                                        ) extends InternalIndexRuntime

  private object UnsupportedIndexStage:
    def componentOf(
                     queryUnsupportedMessage: String,
                     scanUnsupportedMessage: String,
                     unexpectedRequestDescription: String
                   ): Graph[
      FanOutShape3[
        TimedElement[DynamoDBRequest],
        TimedElement[DynamoDBResponse],
        TimedElement[DynamoDbConsumptionEvent],
        TimedElement[Stage4MetricEvent]
      ],
      NotUsed
    ] =
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val requestFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]]
            .map[TimedElement[DynamoDBRequest]] {
              case q: QueryRequest => q
              case s: ScanRequest => s
              case t: TimedControlEvent => t
              case other: DynamoDBRequest =>
                throw new IllegalArgumentException(
                  s"Unexpected request kind '${other.getClass.getSimpleName}' reached $unexpectedRequestDescription"
                )
            }
        )

        val broadcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](3))

        val responseFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBResponse]] {
            case t: TimedControlEvent => t
            case _: QueryRequest => throw new UnsupportedOperationException(queryUnsupportedMessage)
            case _: ScanRequest => throw new UnsupportedOperationException(scanUnsupportedMessage)
            case other: DynamoDBRequest =>
              throw new IllegalArgumentException(
                s"Unexpected request kind '${other.getClass.getSimpleName}' reached $unexpectedRequestDescription"
              )
          }
        )

        val consumptionFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDbConsumptionEvent]] {
            case t: TimedControlEvent => t
            case _: QueryRequest => throw new UnsupportedOperationException(queryUnsupportedMessage)
            case _: ScanRequest => throw new UnsupportedOperationException(scanUnsupportedMessage)
            case other: DynamoDBRequest =>
              throw new IllegalArgumentException(
                s"Unexpected request kind '${other.getClass.getSimpleName}' reached $unexpectedRequestDescription"
              )
          }
        )

        val metricFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[Stage4MetricEvent]] {
            case t: TimedControlEvent => t
            case _: QueryRequest => throw new UnsupportedOperationException(queryUnsupportedMessage)
            case _: ScanRequest => throw new UnsupportedOperationException(scanUnsupportedMessage)
            case other: DynamoDBRequest =>
              throw new IllegalArgumentException(
                s"Unexpected request kind '${other.getClass.getSimpleName}' reached $unexpectedRequestDescription"
              )
          }
        )

        requestFlow.out ~> broadcast.in
        broadcast.out(0) ~> responseFlow
        broadcast.out(1) ~> consumptionFlow
        broadcast.out(2) ~> metricFlow

        new FanOutShape3(
          requestFlow.in,
          responseFlow.out,
          consumptionFlow.out,
          metricFlow.out
        )
      }

  private object QueryAndScanEnabledIndexStage:
    def componentOf(
                     stateModel: TableState,
                     useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                     dataPlaneTarget: DynamoDbTarget,
                     unexpectedRequestDescription: String
                   ): Graph[
      FanOutShape3[
        TimedElement[DynamoDBRequest],
        TimedElement[DynamoDBResponse],
        TimedElement[DynamoDbConsumptionEvent],
        TimedElement[Stage4MetricEvent]
      ],
      NotUsed
    ] =
      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val requestFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBRequest]] {
            case q: QueryRequest => q
            case s: ScanRequest => s
            case t: TimedControlEvent => t
            case other: DynamoDBRequest =>
              throw new IllegalArgumentException(
                s"Unexpected request kind '${other.getClass.getSimpleName}' reached $unexpectedRequestDescription"
              )
          }
        )

        val dataPlaneStage = b.add(
          TableStage4.componentOf(
            stateModel = stateModel,
            useCaseBehaviors = useCaseBehaviors,
            tableTarget = dataPlaneTarget,
            readConsistency = ReadConsistency.EventuallyConsistent
          )
        )

        requestFlow.out ~> dataPlaneStage.in

        new FanOutShape3(
          requestFlow.in,
          dataPlaneStage.out0,
          dataPlaneStage.out1,
          dataPlaneStage.out2
        )
      }

  private def routeFor(config: Config, request: DynamoDBRequest): RouteBranch =
    request match
      case _: GetItemRequest | _: PutItemRequest | _: UpdateItemRequest | _: DeleteItemRequest | _: PartiQLQueryRequest =>
        RouteBranch.BaseTable

      case QueryRequest(_, _, target, _) => routeForReadTarget(config, target)
      case ScanRequest(_, _, target, _) => routeForReadTarget(config, target)

  private def validateRequest(config: Config, request: DynamoDBRequest): Unit =
    request match
      case queryRequest: QueryRequest =>
        routeForReadTarget(config, queryRequest.target)
        validateReadConsistency(queryRequest.target, queryRequest.readConsistency, "Query")

      case scanRequest: ScanRequest =>
        routeForReadTarget(config, scanRequest.target)
        validateReadConsistency(scanRequest.target, scanRequest.readConsistency, "Scan")

      case other =>
        routeFor(config, other)

  private def validateReadConsistency(
                                       target: DynamoDbReadTarget,
                                       consistency: ReadConsistency,
                                       operationName: String
                                     ): Unit =
    target match
      case DynamoDbReadTarget.GlobalSecondaryIndex(_, indexName)
          if consistency == ReadConsistency.StronglyConsistent =>
        throw new IllegalArgumentException(
          s"Strongly consistent $operationName is not supported for global secondary index '$indexName'"
        )
      case _ => ()

  private def routeForReadTarget(config: Config, target: DynamoDbReadTarget): RouteBranch =
    val globalIndexNames = config.globalSecondaryIndexes.map(_.indexName).toSet
    val localIndexNames = config.localSecondaryIndexes.map(_.indexName).toSet

    target match
      case DynamoDbReadTarget.Table(tableName) =>
        requireMatchingTableName(config, tableName)
        RouteBranch.BaseTable

      case DynamoDbReadTarget.GlobalSecondaryIndex(tableName, indexName) =>
        requireMatchingTableName(config, tableName)
        if globalIndexNames.contains(indexName) then RouteBranch.GlobalSecondaryIndex(indexName)
        else if localIndexNames.contains(indexName) then
          throw new IllegalArgumentException(
            s"Read target '$indexName' is configured as a local secondary index, not a global secondary index"
          )
        else
          throw new IllegalArgumentException(
            s"Unknown global secondary index '$indexName' for table '${config.tableName}'"
          )

      case DynamoDbReadTarget.LocalSecondaryIndex(tableName, indexName) =>
        requireMatchingTableName(config, tableName)
        if localIndexNames.contains(indexName) then RouteBranch.LocalSecondaryIndex(indexName)
        else if globalIndexNames.contains(indexName) then
          throw new IllegalArgumentException(
            s"Read target '$indexName' is configured as a global secondary index, not a local secondary index"
          )
        else
          throw new IllegalArgumentException(
            s"Unknown local secondary index '$indexName' for table '${config.tableName}'"
          )

  private def requireMatchingTableName(config: Config, targetTableName: String): Unit =
    if targetTableName != config.tableName then
      throw new IllegalArgumentException(
        s"Read target table '$targetTableName' does not match configured table '${config.tableName}'"
      )

  private def writeCapacityUnitsFor(itemBytes: Long): BigDecimal =
    val chunkCount =
      if itemBytes > 0 then ((itemBytes - 1L) / BytesPerWriteCapacityUnitChunk) + 1L
      else 1L
    BigDecimal(chunkCount)

  private def indexRuntimesFor(config: Config): Vector[InternalIndexRuntime] =
    val globalSecondaryIndexes =
      config.globalSecondaryIndexes.map { definition =>
        InternalIndexRuntime.GlobalSecondaryIndex(
          indexName = definition.indexName,
          stateModel = definition.stateModel,
          target = DynamoDbTarget.GlobalSecondaryIndex(config.tableName, definition.indexName)
        )
      }

    val localSecondaryIndexes =
      config.localSecondaryIndexes.map { definition =>
        InternalIndexRuntime.LocalSecondaryIndex(
          indexName = definition.indexName,
          stateModel = definition.stateModel,
          target = DynamoDbTarget.LocalSecondaryIndex(config.tableName, definition.indexName)
        )
      }

    globalSecondaryIndexes ++ localSecondaryIndexes

  def componentOf(config: Config): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[Stage4MetricEvent]
    ],
    NotUsed
  ] =
    val baseTableGraph =
      TableStage4.componentOf(
        stateModel = config.stateModel,
        useCaseBehaviors = config.useCaseBehaviors,
        tableTarget = DynamoDbTarget.Table(config.tableName),
        readConsistency = config.readConsistency
      )

    val globalSecondaryIndexes = config.globalSecondaryIndexes
    val localSecondaryIndexes = config.localSecondaryIndexes
    val indexRuntimes = indexRuntimesFor(config)

    if globalSecondaryIndexes.isEmpty && localSecondaryIndexes.isEmpty then
      baseTableGraph
    else
      val branchCount = 1 + globalSecondaryIndexes.size + localSecondaryIndexes.size

      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val validationFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBRequest]] {
            case request: DynamoDBRequest =>
              validateRequest(config, request)
              request

            case t: TimedControlEvent => t
          }
        )

        val requestBroadcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](branchCount))

        val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](branchCount))
        val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](branchCount + 1))
        val metricMerge = b.add(Merge[TimedElement[Stage4MetricEvent]](branchCount))

        val baseRequestFilter = b.add(
          Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
            case t: TimedControlEvent => t
            case request: DynamoDBRequest if routeFor(config, request) == RouteBranch.BaseTable => request
          }
        )

        val baseTable = b.add(baseTableGraph)
        val baseResponseBroadcast = b.add(Broadcast[TimedElement[DynamoDBResponse]](2))
        val indexPropagationConsumptionFlow = b.add(
          Flow[TimedElement[DynamoDBResponse]].mapConcat[TimedElement[DynamoDbConsumptionEvent]] {
            case _: TimedControlEvent => Nil

            case response: PutItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulPut(
                  response.storedItemBytes,
                  response.previousItemBytes
                )

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = writeCapacityUnitsFor(response.storedItemBytes)
                  ),
                  DynamoDbConsumptionEvent.StorageBytesWritten(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytes = response.storedItemBytes
                  ),
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = response.storedItemBytes - response.previousItemBytes.getOrElse(0L)
                  )
                )
              }

            case response: UpdateItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulUpdate(
                  response.storedItemBytes,
                  response.previousItemBytes
                )

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = writeCapacityUnitsFor(response.storedItemBytes)
                  ),
                  DynamoDbConsumptionEvent.StorageBytesWritten(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytes = response.storedItemBytes
                  ),
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = response.storedItemBytes - response.previousItemBytes.getOrElse(0L)
                  )
                )
              }

            case response: DeleteItemResponse =>
              indexRuntimes.flatMap { indexRuntime =>
                indexRuntime.stateModel.recordSuccessfulDelete(response.deletedItemBytes)

                val deletedEvents =
                  response.deletedItemBytes.toList.map { bytes =>
                    DynamoDbConsumptionEvent.StorageBytesDeleted(
                      eventTime = response.eventTime,
                      usecase = response.usecase,
                      target = indexRuntime.target,
                      bytes = bytes
                    )
                  }

                List(
                  DynamoDbConsumptionEvent.WriteCapacityConsumed(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    units = writeCapacityUnitsFor(response.deletedItemBytes.getOrElse(0L))
                  )
                ) ++ deletedEvents ++ List(
                  DynamoDbConsumptionEvent.StorageBytesDelta(
                    eventTime = response.eventTime,
                    usecase = response.usecase,
                    target = indexRuntime.target,
                    bytesDelta = -response.deletedItemBytes.getOrElse(0L)
                  )
                )
              }

            case _: DynamoDBResponse =>
              Nil
          }
        )

        validationFlow.out ~> requestBroadcast.in
        requestBroadcast.out(0) ~> baseRequestFilter ~> baseTable.in
        baseTable.out0 ~> baseResponseBroadcast.in
        baseResponseBroadcast.out(0) ~> responseMerge.in(0)
        baseResponseBroadcast.out(1) ~> indexPropagationConsumptionFlow ~> consumptionMerge.in(branchCount)
        baseTable.out1 ~> consumptionMerge.in(0)
        baseTable.out2 ~> metricMerge.in(0)

        var mergeInputIndex = 1

        globalSecondaryIndexes.foreach { indexDefinition =>
          val requestFilter = b.add(
            Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
              case request: DynamoDBRequest
                  if routeFor(config, request) == RouteBranch.GlobalSecondaryIndex(indexDefinition.indexName) =>
                request
            }
          )

          val indexRuntime = indexRuntimes.collectFirst {
            case gsi: InternalIndexRuntime.GlobalSecondaryIndex if gsi.indexName == indexDefinition.indexName => gsi
          }.getOrElse(
            throw new IllegalStateException(s"Missing runtime for global secondary index '${indexDefinition.indexName}'")
          )

          val queryAndScanEnabledStage = b.add(
            QueryAndScanEnabledIndexStage.componentOf(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              dataPlaneTarget = indexRuntime.target,
              unexpectedRequestDescription =
                s"global secondary index '${indexDefinition.indexName}' data-plane stage",
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> queryAndScanEnabledStage.in
          queryAndScanEnabledStage.out0 ~> responseMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out2 ~> metricMerge.in(mergeInputIndex)

          mergeInputIndex = mergeInputIndex + 1
        }

        localSecondaryIndexes.foreach { indexDefinition =>
          val requestFilter = b.add(
            Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
              case request: DynamoDBRequest
                  if routeFor(config, request) == RouteBranch.LocalSecondaryIndex(indexDefinition.indexName) =>
                request
            }
          )

          val indexRuntime = indexRuntimes.collectFirst {
            case lsi: InternalIndexRuntime.LocalSecondaryIndex if lsi.indexName == indexDefinition.indexName => lsi
          }.getOrElse(
            throw new IllegalStateException(s"Missing runtime for local secondary index '${indexDefinition.indexName}'")
          )

          val queryAndScanEnabledStage = b.add(
            QueryAndScanEnabledIndexStage.componentOf(
              stateModel = indexRuntime.stateModel,
              useCaseBehaviors = config.useCaseBehaviors,
              dataPlaneTarget = indexRuntime.target,
              unexpectedRequestDescription =
                s"local secondary index '${indexDefinition.indexName}' data-plane stage",
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> queryAndScanEnabledStage.in
          queryAndScanEnabledStage.out0 ~> responseMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          queryAndScanEnabledStage.out2 ~> metricMerge.in(mergeInputIndex)

          mergeInputIndex = mergeInputIndex + 1
        }

        new FanOutShape3(
          validationFlow.in,
          responseMerge.out,
          consumptionMerge.out,
          metricMerge.out
        )
      }
