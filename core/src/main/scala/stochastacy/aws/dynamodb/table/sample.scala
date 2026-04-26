package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.table.LogicalPartitionAccess.*

enum ProjectionSatisfaction:
  case FullySatisfiedByIndex
  case PartiallySatisfiedByIndexWithBaseTableFetch
  case LimitedToProjectedAttributes

final case class GetItemSample(
                                itemBytes: Option[Long],
                                logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default")
                              ):
  require(
    logicalPartitionAccess.isInstanceOf[SingleLogicalPartitionKey],
    s"GetItemSample requires SingleLogicalPartitionKey, got ${logicalPartitionAccess.getClass.getSimpleName}"
  )

final case class QuerySample(
                              evaluatedItemCount: Long,
                              evaluatedBytes: Long,
                              returnedItemCount: Long,
                              returnedBytes: Long,
                              projectedBytesReturned: Long = 0L,
                              baseTableFetchBytes: Long = 0L,
                              baseTableFetchItemCount: Long = 0L,
                              projectionSatisfaction: ProjectionSatisfaction = ProjectionSatisfaction.FullySatisfiedByIndex,
                              logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default")
                            ):
  require(evaluatedItemCount >= 0L, s"evaluatedItemCount must be non-negative, got $evaluatedItemCount")
  require(evaluatedBytes >= 0L, s"evaluatedBytes must be non-negative, got $evaluatedBytes")
  require(returnedItemCount >= 0L, s"returnedItemCount must be non-negative, got $returnedItemCount")
  require(returnedBytes >= 0L, s"returnedBytes must be non-negative, got $returnedBytes")
  require(projectedBytesReturned >= 0L, s"projectedBytesReturned must be non-negative, got $projectedBytesReturned")
  require(baseTableFetchBytes >= 0L, s"baseTableFetchBytes must be non-negative, got $baseTableFetchBytes")
  require(baseTableFetchItemCount >= 0L, s"baseTableFetchItemCount must be non-negative, got $baseTableFetchItemCount")
  require(
    returnedItemCount <= evaluatedItemCount,
    s"returnedItemCount ($returnedItemCount) must be <= evaluatedItemCount ($evaluatedItemCount)"
  )
  require(
    returnedBytes <= evaluatedBytes,
    s"returnedBytes ($returnedBytes) must be <= evaluatedBytes ($evaluatedBytes)"
  )
  require(
    projectedBytesReturned <= returnedBytes,
    s"projectedBytesReturned ($projectedBytesReturned) must be <= returnedBytes ($returnedBytes)"
  )
  require(
    baseTableFetchBytes <= returnedBytes,
    s"baseTableFetchBytes ($baseTableFetchBytes) must be <= returnedBytes ($returnedBytes)"
  )
  projectionSatisfaction match
    case ProjectionSatisfaction.FullySatisfiedByIndex =>
      require(baseTableFetchBytes == 0L, "FullySatisfiedByIndex requires baseTableFetchBytes == 0")
      require(baseTableFetchItemCount == 0L, "FullySatisfiedByIndex requires baseTableFetchItemCount == 0")
    case ProjectionSatisfaction.LimitedToProjectedAttributes =>
      require(baseTableFetchBytes == 0L, "LimitedToProjectedAttributes requires baseTableFetchBytes == 0")
      require(baseTableFetchItemCount == 0L, "LimitedToProjectedAttributes requires baseTableFetchItemCount == 0")
    case ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch =>
      require(baseTableFetchBytes > 0L, "PartiallySatisfiedByIndexWithBaseTableFetch requires baseTableFetchBytes > 0")
      require(
        baseTableFetchItemCount > 0L,
        "PartiallySatisfiedByIndexWithBaseTableFetch requires baseTableFetchItemCount > 0"
      )
  require(
    logicalPartitionAccess.isInstanceOf[SingleLogicalPartitionKey] ||
      logicalPartitionAccess.isInstanceOf[MultipleLogicalPartitionKeys],
    s"QuerySample requires SingleLogicalPartitionKey or MultipleLogicalPartitionKeys, got ${logicalPartitionAccess.getClass.getSimpleName}"
  )

final case class ScanSample(
                             evaluatedItemCount: Long,
                             evaluatedBytes: Long,
                             returnedItemCount: Long,
                             returnedBytes: Long,
                             projectedBytesReturned: Long = 0L,
                             baseTableFetchBytes: Long = 0L,
                             baseTableFetchItemCount: Long = 0L,
                             projectionSatisfaction: ProjectionSatisfaction = ProjectionSatisfaction.FullySatisfiedByIndex,
                             logicalPartitionAccess: LogicalPartitionAccess = AllPartitions
                           ):
  require(evaluatedItemCount >= 0L, s"evaluatedItemCount must be non-negative, got $evaluatedItemCount")
  require(evaluatedBytes >= 0L, s"evaluatedBytes must be non-negative, got $evaluatedBytes")
  require(returnedItemCount >= 0L, s"returnedItemCount must be non-negative, got $returnedItemCount")
  require(returnedBytes >= 0L, s"returnedBytes must be non-negative, got $returnedBytes")
  require(projectedBytesReturned >= 0L, s"projectedBytesReturned must be non-negative, got $projectedBytesReturned")
  require(baseTableFetchBytes >= 0L, s"baseTableFetchBytes must be non-negative, got $baseTableFetchBytes")
  require(baseTableFetchItemCount >= 0L, s"baseTableFetchItemCount must be non-negative, got $baseTableFetchItemCount")
  require(
    returnedItemCount <= evaluatedItemCount,
    s"returnedItemCount ($returnedItemCount) must be <= evaluatedItemCount ($evaluatedItemCount)"
  )
  require(
    returnedBytes <= evaluatedBytes,
    s"returnedBytes ($returnedBytes) must be <= evaluatedBytes ($evaluatedBytes)"
  )
  require(
    projectedBytesReturned <= returnedBytes,
    s"projectedBytesReturned ($projectedBytesReturned) must be <= returnedBytes ($returnedBytes)"
  )
  require(
    baseTableFetchBytes <= returnedBytes,
    s"baseTableFetchBytes ($baseTableFetchBytes) must be <= returnedBytes ($returnedBytes)"
  )
  projectionSatisfaction match
    case ProjectionSatisfaction.FullySatisfiedByIndex =>
      require(baseTableFetchBytes == 0L, "FullySatisfiedByIndex requires baseTableFetchBytes == 0")
      require(baseTableFetchItemCount == 0L, "FullySatisfiedByIndex requires baseTableFetchItemCount == 0")
    case ProjectionSatisfaction.LimitedToProjectedAttributes =>
      require(baseTableFetchBytes == 0L, "LimitedToProjectedAttributes requires baseTableFetchBytes == 0")
      require(baseTableFetchItemCount == 0L, "LimitedToProjectedAttributes requires baseTableFetchItemCount == 0")
    case ProjectionSatisfaction.PartiallySatisfiedByIndexWithBaseTableFetch =>
      require(baseTableFetchBytes > 0L, "PartiallySatisfiedByIndexWithBaseTableFetch requires baseTableFetchBytes > 0")
      require(
        baseTableFetchItemCount > 0L,
        "PartiallySatisfiedByIndexWithBaseTableFetch requires baseTableFetchItemCount > 0"
      )
  require(
    logicalPartitionAccess == AllPartitions,
    s"ScanSample requires AllPartitions logical access, got ${logicalPartitionAccess.getClass.getSimpleName}"
  )

trait WriteItemSample:
  def writtenItemBytes: Long
  def previousItemBytes: Option[Long]
  def logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default")

  /**
   * The sampler's stochastic estimate of the size in bytes of the LSI-backed item
   * collection (base item + sum of LSI projected entries) that this write's partition
   * key falls into, BEFORE this write is applied. Default `0L` means "trivially under
   * any limit" — fixtures that don't model item collections preserve their behavior.
   */
  def currentItemCollectionBytes: Long = 0L

  def createdNewItem: Boolean = previousItemBytes.isEmpty

  def storageBytesDelta: Long = writtenItemBytes - previousItemBytes.getOrElse(0L)

  def itemCountDelta: Long = if createdNewItem then 1L else 0L

trait PutItemSample extends WriteItemSample

trait UpdateItemSample extends WriteItemSample

trait DeleteItemSample:
  def deletedItemBytes: Option[Long]
  def logicalPartitionAccess: LogicalPartitionAccess = SingleLogicalPartitionKey("default")

  /**
   * The sampler's stochastic estimate of the size in bytes of the LSI-backed item
   * collection (base item + sum of LSI projected entries) that this delete's partition
   * key falls into, BEFORE the delete is applied. Default `0L` means "trivially under
   * any limit" — fixtures that don't model item collections preserve their behavior.
   */
  def currentItemCollectionBytes: Long = 0L

  def deletedExistingItem: Boolean = deletedItemBytes.isDefined

  def storageBytesDelta: Long = -deletedItemBytes.getOrElse(0L)

  def itemCountDelta: Long = if deletedExistingItem then -1L else 0L
