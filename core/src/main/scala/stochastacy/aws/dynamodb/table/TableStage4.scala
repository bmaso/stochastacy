package stochastacy.aws.dynamodb.table

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL}
import org.apache.pekko.stream.{FanOutShape3, Graph}
import stochastacy.aws.dynamodb.*
import stochastacy.sim.*
import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.SingleLogicalPartitionKey

/**
 * A table is implemented as a multi-stage Pekko component graph. Stage 4 of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStage4:

  private[table] def componentOfAdmitted(
                                          stateModel: TableState
                                        ): Graph[
    FanOutShape3[
      TimedElement[AdmittedRequestSample],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[Stage4MetricEvent]
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

          case AdmittedQuerySample(r, _, _, s, _, _) =>
            QueryResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              target = r.target,
              readConsistency = r.readConsistency,
              evaluatedItemCount = s.evaluatedItemCount,
              evaluatedBytes = s.evaluatedBytes,
              returnedItemCount = s.returnedItemCount,
              returnedBytes = s.returnedBytes
            )

          case AdmittedScanSample(r, _, _, s, _, _) =>
            ScanResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              target = r.target,
              readConsistency = r.readConsistency,
              evaluatedItemCount = s.evaluatedItemCount,
              evaluatedBytes = s.evaluatedBytes,
              returnedItemCount = s.returnedItemCount,
              returnedBytes = s.returnedBytes
            )

          case AdmittedPutItemSample(r, _, _, s, _, _) =>
            PutItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              storedItemBytes = s.writtenItemBytes,
              createdNewItem = s.createdNewItem,
              previousItemBytes = s.previousItemBytes
            )

          case AdmittedUpdateItemSample(r, _, _, s, _, _) =>
            UpdateItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              storedItemBytes = s.writtenItemBytes,
              createdNewItem = s.createdNewItem,
              previousItemBytes = s.previousItemBytes
            )

          case AdmittedDeleteItemSample(r, _, _, s, _, _) =>
            DeleteItemResponse(
              eventTime = r.eventTime,
              usecase = r.usecase,
              deletedItemBytes = s.deletedItemBytes
            )
        }
      )

      val metricFlow = b.add(
        Flow[TimedElement[AdmittedRequestSample]].mapConcat[TimedElement[Stage4MetricEvent]] {
          case t: TimedControlEvent => List(t)

          case AdmittedGetItemSample(r, _, _, _, s, _, _) =>
            val returnedEvents =
              s.itemBytes.toList.map { itemBytes =>
                Stage4MetricEvent.GetItemReturned(r.eventTime, r.usecase, itemBytes)
              }
            List(
              Stage4MetricEvent.GetItemObserved(r.eventTime, r.usecase)
            ) ++ returnedEvents

          case AdmittedQuerySample(r, _, _, s, _, _) =>
            val returnedEvents =
              if s.returnedItemCount > 0L || s.returnedBytes > 0L then
                List(
                  Stage4MetricEvent.QueryReturned(r.eventTime, r.usecase, r.target, s.returnedItemCount, s.returnedBytes)
                )
              else Nil
            List(
              Stage4MetricEvent.QueryObserved(r.eventTime, r.usecase, r.target),
              Stage4MetricEvent.QueryEvaluated(r.eventTime, r.usecase, r.target, s.evaluatedItemCount, s.evaluatedBytes)
            ) ++ returnedEvents

          case AdmittedScanSample(r, _, _, s, _, _) =>
            val returnedEvents =
              if s.returnedItemCount > 0L || s.returnedBytes > 0L then
                List(
                  Stage4MetricEvent.ScanReturned(r.eventTime, r.usecase, r.target, s.returnedItemCount, s.returnedBytes)
                )
              else Nil
            List(
              Stage4MetricEvent.ScanObserved(r.eventTime, r.usecase, r.target),
              Stage4MetricEvent.ScanEvaluated(r.eventTime, r.usecase, r.target, s.evaluatedItemCount, s.evaluatedBytes)
            ) ++ returnedEvents

          case AdmittedPutItemSample(r, _, _, s, _, _) =>
            List(
              Stage4MetricEvent.PutItemObserved(r.eventTime, r.usecase),
              Stage4MetricEvent.PutItemStored(r.eventTime, r.usecase, s.writtenItemBytes, s.createdNewItem),
              Stage4MetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              Stage4MetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
            )

          case AdmittedUpdateItemSample(r, _, _, s, _, _) =>
            List(
              Stage4MetricEvent.UpdateItemObserved(r.eventTime, r.usecase),
              Stage4MetricEvent.UpdateItemStored(r.eventTime, r.usecase, s.writtenItemBytes, s.createdNewItem),
              Stage4MetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              Stage4MetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
            )

          case AdmittedDeleteItemSample(r, _, _, s, _, _) =>
            val deleteEvents =
              s.deletedItemBytes.toList.map { bytes =>
                Stage4MetricEvent.DeleteItemDeleted(r.eventTime, r.usecase, bytes)
              }
            List(
              Stage4MetricEvent.DeleteItemObserved(r.eventTime, r.usecase)
            ) ++ deleteEvents ++ List(
              Stage4MetricEvent.TableItemCountChanged(r.eventTime, r.usecase, s.itemCountDelta),
              Stage4MetricEvent.TableBytesChanged(r.eventTime, r.usecase, s.storageBytesDelta)
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

          case AdmittedQuerySample(r, executionTarget, _, s, _, _) =>
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

            List(
              DynamoDbConsumptionEvent.ReadCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                consistency = r.readConsistency
              )
            ) ++ bytesReadEvents

          case AdmittedScanSample(r, executionTarget, _, s, _, _) =>
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

            List(
              DynamoDbConsumptionEvent.ReadCapacityConsumed(
                eventTime = r.eventTime,
                usecase = r.usecase,
                target = executionTarget,
                units = TableThroughputMath.readCapacityUnitsFor(Some(s.evaluatedBytes), r.readConsistency),
                consistency = r.readConsistency
              )
            ) ++ bytesReadEvents

          case AdmittedPutItemSample(r, executionTarget, _, s, _, _) =>
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

          case AdmittedUpdateItemSample(r, executionTarget, _, s, _, _) =>
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

          case AdmittedDeleteItemSample(r, executionTarget, _, s, _, _) =>
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
                   readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
                 ): Graph[
    FanOutShape3[
      TimedElement[DynamoDBRequest],
      TimedElement[DynamoDBResponse],
      TimedElement[DynamoDbConsumptionEvent],
      TimedElement[Stage4MetricEvent]
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

    val admittedGraph = componentOfAdmitted(stateModel)

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
              admissionTarget = executionTargetFor(r.target),
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
              admissionTarget = executionTargetFor(r.target),
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
