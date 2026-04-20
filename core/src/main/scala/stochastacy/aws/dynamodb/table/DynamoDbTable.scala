package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*

object DynamoDbTable:

  final case class GlobalSecondaryIndexDefinition(indexName: String)

  final case class LocalSecondaryIndexDefinition(indexName: String)

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

  private def routeFor(config: Config, request: DynamoDBRequest): RouteBranch =
    request match
      case _: GetItemRequest | _: PutItemRequest | _: UpdateItemRequest | _: DeleteItemRequest | _: PartiQLQueryRequest =>
        RouteBranch.BaseTable

      case QueryRequest(_, _, target) => routeForReadTarget(config, target)
      case ScanRequest(_, _, target) => routeForReadTarget(config, target)

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

    if globalSecondaryIndexes.isEmpty && localSecondaryIndexes.isEmpty then
      baseTableGraph
    else
      val branchCount = 1 + globalSecondaryIndexes.size + localSecondaryIndexes.size

      GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits.*

        val validationFlow = b.add(
          Flow[TimedElement[DynamoDBRequest]].map[TimedElement[DynamoDBRequest]] {
            case request: DynamoDBRequest =>
              routeFor(config, request)
              request

            case t: TimedControlEvent => t
          }
        )

        val requestBroadcast = b.add(Broadcast[TimedElement[DynamoDBRequest]](branchCount))

        val responseMerge = b.add(Merge[TimedElement[DynamoDBResponse]](branchCount))
        val consumptionMerge = b.add(Merge[TimedElement[DynamoDbConsumptionEvent]](branchCount))
        val metricMerge = b.add(Merge[TimedElement[Stage4MetricEvent]](branchCount))

        val baseRequestFilter = b.add(
          Flow[TimedElement[DynamoDBRequest]].collect[TimedElement[DynamoDBRequest]] {
            case t: TimedControlEvent => t
            case request: DynamoDBRequest if routeFor(config, request) == RouteBranch.BaseTable => request
          }
        )

        val baseTable = b.add(baseTableGraph)

        validationFlow.out ~> requestBroadcast.in
        requestBroadcast.out(0) ~> baseRequestFilter ~> baseTable.in
        baseTable.out0 ~> responseMerge.in(0)
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

          val placeholderStage = b.add(
            UnsupportedIndexStage.componentOf(
              queryUnsupportedMessage =
                s"Query is not yet supported for global secondary index '${indexDefinition.indexName}'",
              scanUnsupportedMessage =
                s"Scan is not yet supported for global secondary index '${indexDefinition.indexName}'",
              unexpectedRequestDescription =
                s"global secondary index '${indexDefinition.indexName}' placeholder stage"
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> placeholderStage.in
          placeholderStage.out0 ~> responseMerge.in(mergeInputIndex)
          placeholderStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          placeholderStage.out2 ~> metricMerge.in(mergeInputIndex)

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

          val placeholderStage = b.add(
            UnsupportedIndexStage.componentOf(
              queryUnsupportedMessage =
                s"Query is not yet supported for local secondary index '${indexDefinition.indexName}'",
              scanUnsupportedMessage =
                s"Scan is not yet supported for local secondary index '${indexDefinition.indexName}'",
              unexpectedRequestDescription =
                s"local secondary index '${indexDefinition.indexName}' placeholder stage"
            )
          )

          requestBroadcast.out(mergeInputIndex) ~> requestFilter ~> placeholderStage.in
          placeholderStage.out0 ~> responseMerge.in(mergeInputIndex)
          placeholderStage.out1 ~> consumptionMerge.in(mergeInputIndex)
          placeholderStage.out2 ~> metricMerge.in(mergeInputIndex)

          mergeInputIndex = mergeInputIndex + 1
        }

        new FanOutShape3(
          validationFlow.in,
          responseMerge.out,
          consumptionMerge.out,
          metricMerge.out
        )
      }
