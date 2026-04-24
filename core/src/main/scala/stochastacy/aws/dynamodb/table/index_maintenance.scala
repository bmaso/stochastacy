package stochastacy.aws.dynamodb.table

enum IndexMaintenanceAction:
  case NoOp
  case InsertEntry
  case ReplaceEntry
  case DeleteEntry

final case class IndexMaintenanceSummary(
                                          target: DynamoDbTarget,
                                          action: IndexMaintenanceAction,
                                          throughputDemand: BigDecimal,
                                          storageBytesDelta: Long
                                        )

private[table] final case class IndexMaintenancePlan(
                                                      target: DynamoDbTarget,
                                                      action: IndexMaintenanceAction,
                                                      throughputDemand: BigDecimal,
                                                      logicalPartitionAccess: LogicalPartitionAccess,
                                                      resolvedPartitionFootprint: ResolvedPartitionFootprint,
                                                      newIndexEntryBytes: Option[Long],
                                                      previousIndexEntryBytes: Option[Long],
                                                      storageBytesDelta: Long
                                                    ):
  def summary: IndexMaintenanceSummary =
    IndexMaintenanceSummary(
      target = target,
      action = action,
      throughputDemand = throughputDemand,
      storageBytesDelta = storageBytesDelta
    )

private[table] object IndexMaintenanceMath:
  private[table] val IndexKeyBytesPerEntry = 128L

  def projectedEntryBytes(
                           itemBytes: Option[Long],
                           projection: DynamoDbTable.IndexProjection
                         ): Option[Long] =
    itemBytes.map { bytes =>
      projection match
        case DynamoDbTable.IndexProjection.All =>
          bytes
        case DynamoDbTable.IndexProjection.KeysOnly =>
          bytes.min(IndexKeyBytesPerEntry)
        case DynamoDbTable.IndexProjection.Include(projectedNonKeyBytesPerItem) =>
          bytes.min(IndexKeyBytesPerEntry + projectedNonKeyBytesPerItem)
    }

  def derivePlan(
                  target: DynamoDbTarget,
                  projection: DynamoDbTable.IndexProjection,
                  logicalPartitionAccess: LogicalPartitionAccess,
                  newBaseItemBytes: Option[Long],
                  previousBaseItemBytes: Option[Long],
                  topology: PartitionTopologySnapshot
                ): IndexMaintenancePlan =
    val newIndexEntryBytes = projectedEntryBytes(newBaseItemBytes, projection)
    val previousIndexEntryBytes = projectedEntryBytes(previousBaseItemBytes, projection)

    val action =
      (newIndexEntryBytes, previousIndexEntryBytes) match
        case (None, None) => IndexMaintenanceAction.NoOp
        case (Some(_), None) => IndexMaintenanceAction.InsertEntry
        case (None, Some(_)) => IndexMaintenanceAction.DeleteEntry
        case (Some(newBytes), Some(previousBytes)) if newBytes == previousBytes =>
          IndexMaintenanceAction.NoOp
        case (Some(_), Some(_)) =>
          IndexMaintenanceAction.ReplaceEntry

    val throughputDemand =
      action match
        case IndexMaintenanceAction.NoOp =>
          BigDecimal(0)
        case IndexMaintenanceAction.InsertEntry | IndexMaintenanceAction.ReplaceEntry =>
          TableThroughputMath.writeCapacityUnitsFor(newIndexEntryBytes.getOrElse(0L))
        case IndexMaintenanceAction.DeleteEntry =>
          TableThroughputMath.writeCapacityUnitsFor(previousIndexEntryBytes.getOrElse(0L))

    val storageBytesDelta = newIndexEntryBytes.getOrElse(0L) - previousIndexEntryBytes.getOrElse(0L)

    val resolvedPartitionFootprint =
      PartitionAccessResolver.resolve(
        access = logicalPartitionAccess,
        throughputDemand = throughputDemand,
        topology = topology
      )

    IndexMaintenancePlan(
      target = target,
      action = action,
      throughputDemand = throughputDemand,
      logicalPartitionAccess = logicalPartitionAccess,
      resolvedPartitionFootprint = resolvedPartitionFootprint,
      newIndexEntryBytes = newIndexEntryBytes,
      previousIndexEntryBytes = previousIndexEntryBytes,
      storageBytesDelta = storageBytesDelta
    )
