package stochastacy.aws.dynamodb.table

/**
 * Shared derivation of per-index maintenance plans for an admitted (or about-to-be-admitted)
 * base-table write. Used by `TableSamplingStage` to memorialize the plan into the shaped
 * envelope, and by `TableAdmissionStage` to re-derive the plan if topology evolves at the tick
 * boundary that immediately precedes a request and invalidates the memorialized plan.
 */
private[table] object IndexMaintenancePlanDerivation:

  def derivePlans(
                   indexMaintenanceTargets: Vector[TableAdmissionStage.IndexMaintenanceTargetConfig],
                   gsiWriteScopes: Vector[TableAdmissionStage.GsiWriteScopeConfig],
                   fallbackPartitionCount: Int,
                   logicalPartitionAccess: LogicalPartitionAccess,
                   newBaseItemBytes: Option[Long],
                   previousBaseItemBytes: Option[Long],
                   baseTopologySnapshot: PartitionTopologySnapshot,
                   gsiTopologySnapshots: Map[String, PartitionTopologySnapshot]
                 ): Vector[IndexMaintenancePlan] =
    val maintenanceTargets =
      if indexMaintenanceTargets.nonEmpty then indexMaintenanceTargets
      else
        gsiWriteScopes.map { scope =>
          TableAdmissionStage.IndexMaintenanceTargetConfig(
            target = scope.target,
            projection = DynamoDbTable.IndexProjection.All
          )
        }

    maintenanceTargets.map { targetConfig =>
      val topologySnapshot =
        targetConfig.target match
          case DynamoDbTarget.GlobalSecondaryIndex(_, indexName) =>
            gsiTopologySnapshots.getOrElse(
              indexName,
              PartitionTopologySnapshot(
                partitionCount =
                  gsiWriteScopes
                    .find(_.target.indexName == indexName)
                    .flatMap(_.dynamicPartitionTopologyConfig.map(_.initialPartitionCount))
                    .getOrElse(fallbackPartitionCount),
                version = 0L,
                effectiveFromTick = 0L
              )
            )
          case _: DynamoDbTarget.LocalSecondaryIndex =>
            baseTopologySnapshot
          case _: DynamoDbTarget.Table =>
            baseTopologySnapshot

      IndexMaintenanceMath.derivePlan(
        target = targetConfig.target,
        projection = targetConfig.projection,
        logicalPartitionAccess = logicalPartitionAccess,
        newBaseItemBytes = newBaseItemBytes,
        previousBaseItemBytes = previousBaseItemBytes,
        topology = topologySnapshot
      )
    }
