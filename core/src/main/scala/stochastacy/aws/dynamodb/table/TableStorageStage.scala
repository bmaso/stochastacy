package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey

/**
 * A table is implemented as a multi-stage Pekko component graph. the TableStorageStage of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStorageStage:

  private final case class EffectiveReadSample(
                                                returnedBytes: Long,
                                                baseTableFetchBytes: Long,
                                                baseTableFetchItemCount: Long,
                                                usedIndexOnly: Boolean
                                              )

  private def effectiveReadSample(
                                   executionTarget: DynamoDbTarget,
                                   projectedBytesReturned: Long,
                                   returnedBytes: Long,
                                   baseTableFetchBytes: Long,
                                   baseTableFetchItemCount: Long,
                                   projectionSatisfaction: ProjectionSatisfaction,
                                   indexProjection: Option[DynamoDbTable.IndexProjection]
                                 ): EffectiveReadSample =
    executionTarget match
      case _: DynamoDbTarget.GlobalSecondaryIndex =>
        val limitedBytes =
          if projectionSatisfaction == ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch then
            projectedBytesReturned
          else returnedBytes
        EffectiveReadSample(
          returnedBytes = limitedBytes,
          baseTableFetchBytes = 0L,
          baseTableFetchItemCount = 0L,
          usedIndexOnly = true
        )

      case _: DynamoDbTarget.LocalSecondaryIndex =>
        indexProjection match
          case Some(DynamoDbTable.IndexProjection.All) =>
            EffectiveReadSample(
              returnedBytes = returnedBytes,
              baseTableFetchBytes = 0L,
              baseTableFetchItemCount = 0L,
              usedIndexOnly = true
            )
          case _ =>
            EffectiveReadSample(
              returnedBytes = returnedBytes,
              baseTableFetchBytes = baseTableFetchBytes,
              baseTableFetchItemCount = baseTableFetchItemCount,
              usedIndexOnly = baseTableFetchBytes == 0L && baseTableFetchItemCount == 0L
            )

      case _: DynamoDbTarget.Table =>
        EffectiveReadSample(
          returnedBytes = returnedBytes,
          baseTableFetchBytes = 0L,
          baseTableFetchItemCount = 0L,
          usedIndexOnly = false
        )

  private[table] def componentOfAdmitted(
                                          stateModel: TableState,
                                          indexProjection: Option[DynamoDbTable.IndexProjection] = None
                                        ): Graph[
    FanOutShape3[
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[StorageMetricEvent]
    ],
    NotUsed
  ] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val broadcast = b.add(Broadcast[TimedElement[AdmittedRequestSample]](3))

      val stateMutationFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].map[TimedElement[AdmittedRequestSample]] {
          case sample: AdmittedPutItemSample =>
            stateModel.recordSuccessfulPut(sample.sample.writtenItemBytes, sample.sample.previousItemBytes)
            sample

          case sample: AdmittedUpdateItemSample =>
            stateModel.recordSuccessfulUpdate(sample.sample.writtenItemBytes, sample.sample.previousItemBytes)
            sample

          case sample: AdmittedDeleteItemSample =>
            stateModel.recordSuccessfulDelete(sample.sample.deletedItemBytes)
            sample

          case other => other
        }
      )

      val responseFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].map[TimedElement[DynamoDBResponse]] {
          case t: TimedControlEvent => t

          case AdmittedGetItemSample(r, _, _, _, s, _, _) =>
            GetItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              itemFound = s.itemBytes.isDefined,
              itemBytes = s.itemBytes
            )

          case AdmittedQuerySample(r, executionTarget, _, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            QueryResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              target = r.target,
              readConsistency = r.readConsistency,
              evaluatedItemCount = s.evaluatedItemCount,
              evaluatedBytes = s.evaluatedBytes,
              returnedItemCount = s.returnedItemCount,
              returnedBytes = effectiveSample.returnedBytes
            )

          case AdmittedScanSample(r, executionTarget, _, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            ScanResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              target = r.target,
              readConsistency = r.readConsistency,
              evaluatedItemCount = s.evaluatedItemCount,
              evaluatedBytes = s.evaluatedBytes,
              returnedItemCount = s.returnedItemCount,
              returnedBytes = effectiveSample.returnedBytes
            )

          case AdmittedPutItemSample(r, _, _, s, _, _, _) =>
            PutItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              storedItemBytes = s.writtenItemBytes,
              createdNewItem = s.createdNewItem,
              previousItemBytes = s.previousItemBytes
            )

          case AdmittedUpdateItemSample(r, _, _, s, _, _, _) =>
            UpdateItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              storedItemBytes = s.writtenItemBytes,
              createdNewItem = s.createdNewItem,
              previousItemBytes = s.previousItemBytes
            )

          case AdmittedDeleteItemSample(r, _, _, s, _, _, _) =>
            DeleteItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              deletedItemBytes = s.deletedItemBytes
            )
        }
      )

      val metricFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].mapConcat[TimedElement[StorageMetricEvent]] {
          case t: TimedControlEvent => List(t)

          case AdmittedGetItemSample(r, _, _, _, s, _, _) =>
            val returnedEvents =
              s.itemBytes.toList.map { itemBytes =>
                StorageMetricEvent.GetItemReturned(r.eventTime, r.usecase, itemBytes)
              }
            List(
              StorageMetricEvent.GetItemObserved(r.eventTime, r.usecase)
            ) ++ returnedEvents

          case AdmittedQuerySample(r, executionTarget, _, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            val returnedEvents =
              if s.returnedItemCount > 0L || effectiveSample.returnedBytes > 0L then
                List(
                  StorageMetricEvent.QueryReturned(r.eventTime, r.usecase, r.target, s.returnedItemCount, effectiveSample.returnedBytes)
                )
              else Nil
            val projectionEvents =
              executionTarget match
                case _: DynamoDbTarget.GlobalSecondaryIndex | _: DynamoDbTarget.LocalSecondaryIndex =>
                  if effectiveSample.usedIndexOnly then
                    List(StorageMetricEvent.QueryUsedIndexOnly(r.eventTime, r.usecase, r.target))
                  else if effectiveSample.baseTableFetchBytes > 0L || effectiveSample.baseTableFetchItemCount > 0L then
                    List(
                      StorageMetricEvent.QueryFetchedFromBaseTable(
                        r.eventTime,
                        r.usecase,
                        r.target,
                        effectiveSample.baseTableFetchItemCount,
                        effectiveSample.baseTableFetchBytes
                      )
                    )
                  else Nil
                case _: DynamoDbTarget.Table => Nil
            List(
              StorageMetricEvent.QueryObserved(r.eventTime, r.usecase, r.target),
              StorageMetricEvent.QueryEvaluated(r.eventTime, r.usecase, r.target, s.evaluatedItemCount, s.evaluatedBytes)
            ) ++ returnedEvents ++ projectionEvents

          case AdmittedScanSample(r, executionTarget, _, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            val returnedEvents =
              if s.returnedItemCount > 0L || effectiveSample.returnedBytes > 0L then
                List(
                  StorageMetricEvent.ScanReturned(r.eventTime, r.usecase, r.target, s.returnedItemCount, effectiveSample.returnedBytes)
                )
              else Nil
            val projectionEvents =
              executionTarget match
                case _: DynamoDbTarget.GlobalSecondaryIndex | _: DynamoDbTarget.LocalSecondaryIndex =>
                  if effectiveSample.usedIndexOnly then
                    List(StorageMetricEvent.ScanUsedIndexOnly(r.eventTime, r.usecase, r.target))
                  else if effectiveSample.baseTableFetchBytes > 0L || effectiveSample.baseTableFetchItemCount > 0L then
                    List(
                      StorageMetricEvent.ScanFetchedFromBaseTable(
                        r.eventTime,
                        r.usecase,
                        r.target,
                        effectiveSample.baseTableFetchItemCount,
                        effectiveSample.baseTableFetchBytes
                      )
                    )
                  else Nil
                case _: DynamoDbTarget.Table => Nil
            List(
              StorageMetricEvent.ScanObserved(r.eventTime, r.usecase, r.target),
              StorageMetricEvent.ScanEvaluated(r.eventTime, r.usecase, r.target, s.evaluatedItemCount, s.evaluatedBytes)
            ) ++ returnedEvents ++ projectionEvents

          case AdmittedPutItemSample(r, _, _, s, _, _, _) =>
            List(
              StorageMetricEvent.PutItemObserved(r.eventTime, r.usecase),
              StorageMetricEvent.PutItemStored(r.eventTime, r.usecase, s.writtenItemBytes, s.createdNewItem),
              StorageMetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              StorageMetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
            )

          case AdmittedUpdateItemSample(r, _, _, s, _, _, _) =>
            List(
              StorageMetricEvent.UpdateItemObserved(r.eventTime, r.usecase),
              StorageMetricEvent.UpdateItemStored(r.eventTime, r.usecase, s.writtenItemBytes, s.createdNewItem),
              StorageMetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              StorageMetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
            )

          case AdmittedDeleteItemSample(r, _, _, s, _, _, _) =>
            val deleteEvents =
              s.deletedItemBytes.toList.map { bytes =>
                StorageMetricEvent.DeleteItemDeleted(r.eventTime, r.usecase, bytes)
              }
            List(
              StorageMetricEvent.DeleteItemObserved(r.eventTime, r.usecase)
            ) ++ deleteEvents ++ List(
              StorageMetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              StorageMetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
            )
        }
      )

      val consumptionFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].mapConcat[TimedElement[DynamoDbConsumptionEvent]] {
          case t: TimedControlEvent => List(t)

          case AdmittedGetItemSample(r, executionTarget, _, readConsistency, s, _, _) =>
            val bytesReadEvents =
              s.itemBytes.toList.map { itemBytes =>
                DynamoDbConsumptionEvent.StorageBytesRead(
                  eventTime = r.eventTime,
                  usecase = r.usecase,
                  target = executionTarget,
                  bytes = itemBytes
                )
              }

            List(
              DynamoDbConsumptionEvent.ReadCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.readCapacityUnitsFor(s.itemBytes, readConsistency),
                consistency = readConsistency
              )
            ) ++ bytesReadEvents

          case AdmittedQuerySample(r, executionTarget, admissionTarget, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            val bytesReadEvents =
              if s.evaluatedBytes > 0L then
                List(
                  DynamoDbConsumptionEvent.StorageBytesRead(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = executionTarget,
                    bytes = s.evaluatedBytes
                  )
                )
              else Nil
            val baseTableFetchEvents =
              if effectiveSample.baseTableFetchBytes > 0L then
                List(
                  DynamoDbConsumptionEvent.ReadCapacityConsumed(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = admissionTarget,
                    units = TableThroughputMath.readCapacityUnitsFor(Some(effectiveSample.baseTableFetchBytes), r.readConsistency),
                    consistency = r.readConsistency
                  ),
                  DynamoDbConsumptionEvent.StorageBytesRead(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = admissionTarget,
                    bytes = effectiveSample.baseTableFetchBytes
                  )
                )
              else Nil

            List(
              DynamoDbConsumptionEvent.ReadCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                consistency = r.readConsistency
              )
            ) ++ bytesReadEvents ++ baseTableFetchEvents

          case AdmittedScanSample(r, executionTarget, admissionTarget, s, _, _) =>
            val effectiveSample =
              effectiveReadSample(
                executionTarget = executionTarget,
                projectedBytesReturned = s.projectedBytesReturned,
                returnedBytes = s.returnedBytes,
                baseTableFetchBytes = s.baseTableFetchBytes,
                baseTableFetchItemCount = s.baseTableFetchItemCount,
                projectionSatisfaction = s.projectionSatisfaction,
                indexProjection = indexProjection
              )
            val bytesReadEvents =
              if s.evaluatedBytes > 0L then
                List(
                  DynamoDbConsumptionEvent.StorageBytesRead(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = executionTarget,
                    bytes = s.evaluatedBytes
                  )
                )
              else Nil
            val baseTableFetchEvents =
              if effectiveSample.baseTableFetchBytes > 0L then
                List(
                  DynamoDbConsumptionEvent.ReadCapacityConsumed(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = admissionTarget,
                    units = TableThroughputMath.readCapacityUnitsFor(Some(effectiveSample.baseTableFetchBytes), r.readConsistency),
                    consistency = r.readConsistency
                  ),
                  DynamoDbConsumptionEvent.StorageBytesRead(
                    eventTime = r.eventTime,
                    usecase = r.usecase,
                    target = admissionTarget,
                    bytes = effectiveSample.baseTableFetchBytes
                  )
                )
              else Nil

            List(
              DynamoDbConsumptionEvent.ReadCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                consistency = r.readConsistency
              )
            ) ++ bytesReadEvents ++ baseTableFetchEvents

          case AdmittedPutItemSample(r, executionTarget, _, s, _, _, _) =>
            List(
              DynamoDbConsumptionEvent.WriteCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.writeCapacityUnitsFor(s.writtenItemBytes)
              ),
              DynamoDbConsumptionEvent.StorageBytesWritten(r.eventTime, r.usecase, executionTarget, s.writtenItemBytes),
              DynamoDbConsumptionEvent.StorageBytesDelta(r.eventTime, r.usecase, executionTarget, s.storageBytesDelta)
            )

          case AdmittedUpdateItemSample(r, executionTarget, _, s, _, _, _) =>
            List(
              DynamoDbConsumptionEvent.WriteCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.writeCapacityUnitsFor(s.writtenItemBytes)
              ),
              DynamoDbConsumptionEvent.StorageBytesWritten(r.eventTime, r.usecase, executionTarget, s.writtenItemBytes),
              DynamoDbConsumptionEvent.StorageBytesDelta(r.eventTime, r.usecase, executionTarget, s.storageBytesDelta)
            )

          case AdmittedDeleteItemSample(r, executionTarget, _, s, _, _, _) =>
            val deletedBytesEvents =
              s.deletedItemBytes.toList.map { bytes =>
                DynamoDbConsumptionEvent.StorageBytesDeleted(r.eventTime, r.usecase, executionTarget, bytes)
              }
            List(
              DynamoDbConsumptionEvent.WriteCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.writeCapacityUnitsFor(s.deletedItemBytes.getOrElse(0L))
              )
            ) ++ deletedBytesEvents ++ List(
              DynamoDbConsumptionEvent.StorageBytesDelta(r.eventTime, r.usecase, executionTarget, s.storageBytesDelta)
            )
        }
      )

      stateMutationFlow.out ~> broadcast.in
      broadcast.out(0) ~> responseFlow
      broadcast.out(1) ~> consumptionFlow
      broadcast.out(2) ~> metricFlow

      new FanOutShape3(
        stateMutationFlow.in,
        responseFlow.out,
        consumptionFlow.out,
        metricFlow.out
      )
    }

  def componentOf(
                   stateModel: TableState,
                   useCaseBehaviors: Map[Any, UseCaseSampler[TableState]],
                   tableTarget: DynamoDbTarget = DynamoDbTarget.Table("table"),
                   readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
                   indexProjection: Option[DynamoDbTable.IndexProjection] = None
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[StorageMetricEvent]
    ],
    NotUsed
  ] =
    def samplerFor(request: DynamoDBRequest): UseCaseSampler[TableState] =
      useCaseBehaviors.getOrElse(
        request.usecase,
        throw new IllegalArgumentException(s"No table behavior for '${request.usecase}'")
      )

    def executionTargetFor(readTarget: DynamoDbReadTarget): DynamoDbTarget =
      readTarget match
        case DynamoDbReadTarget.Table(tableName) =>
          DynamoDbTarget.Table(tableName)
        case DynamoDbReadTarget.GlobalSecondaryIndex(tableName, indexName) =>
          DynamoDbTarget.GlobalSecondaryIndex(tableName, indexName)
        case DynamoDbReadTarget.LocalSecondaryIndex(tableName, indexName) =>
          DynamoDbTarget.LocalSecondaryIndex(tableName, indexName)

    def admissionTargetFor(readTarget: DynamoDbReadTarget): DynamoDbTarget =
      readTarget match
        case _: DynamoDbReadTarget.LocalSecondaryIndex => tableTarget
        case other => executionTargetFor(other)

    val admittedGraph = componentOfAdmitted(stateModel, indexProjection = indexProjection)

    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits.*

      val rawToAdmitted = b.add(
        Flow[TimedElement[DynamoDBRequest]].map[TimedElement[AdmittedRequestSample]] {
          case r: GetItemRequest =>
            val sample = samplerFor(r).getItem(r, stateModel)
            AdmittedGetItemSample(
              req = r,
              executionTarget = tableTarget,
              admissionTarget = tableTarget,
              readConsistency = readConsistency,
              sample = sample,
              throughputDemand = TableThroughputMath.readCapacityUnitsFor(sample.itemBytes, readConsistency),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.readCapacityUnitsFor(sample.itemBytes, readConsistency),
                partitionCount = 1
              )
            )

          case r: PutItemRequest =>
            val sample = samplerFor(r).putItem(r, stateModel)
            AdmittedPutItemSample(
              req = r,
              executionTarget = tableTarget,
              admissionTarget = tableTarget,
              sample = sample,
              throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes),
                partitionCount = 1
              )
            )

          case r: QueryRequest =>
            val sample = samplerFor(r).query(r, stateModel)
            AdmittedQuerySample(
              req = r,
              executionTarget = executionTargetFor(r.target),
              admissionTarget = admissionTargetFor(r.target),
              sample = sample,
              throughputDemand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency),
                partitionCount = 1
              )
            )

          case r: ScanRequest =>
            val sample = samplerFor(r).scan(r, stateModel)
            AdmittedScanSample(
              req = r,
              executionTarget = executionTargetFor(r.target),
              admissionTarget = admissionTargetFor(r.target),
              sample = sample,
              throughputDemand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.readCapacityUnitsFor(Some(sample.evaluatedBytes), r.readConsistency),
                partitionCount = 1
              )
            )

          case r: UpdateItemRequest =>
            val sample = samplerFor(r).updateItem(r, stateModel)
            AdmittedUpdateItemSample(
              req = r,
              executionTarget = tableTarget,
              admissionTarget = tableTarget,
              sample = sample,
              throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.writtenItemBytes),
                partitionCount = 1
              )
            )

          case r: DeleteItemRequest =>
            val sample = samplerFor(r).deleteItem(r, stateModel)
            AdmittedDeleteItemSample(
              req = r,
              executionTarget = tableTarget,
              admissionTarget = tableTarget,
              sample = sample,
              throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.deletedItemBytes.getOrElse(0L)),
              resolvedPartitionFootprint = PartitionAccessResolver.resolve(
                access = sample.logicalPartitionAccess,
                throughputDemand = TableThroughputMath.writeCapacityUnitsFor(sample.deletedItemBytes.getOrElse(0L)),
                partitionCount = 1
              )
            )

          case _: PartiQLQueryRequest =>
            throw new UnsupportedOperationException("PartiQL query execution is not yet supported")

          case t: TimedControlEvent => t
        }
      )

      val admittedStage = b.add(admittedGraph)

      rawToAdmitted.out ~> admittedStage.in

      new FanOutShape3(
        rawToAdmitted.in,
        admittedStage.out0,
        admittedStage.out1,
        admittedStage.out2
      )
    }
