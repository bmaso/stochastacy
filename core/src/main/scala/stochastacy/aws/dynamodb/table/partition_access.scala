package stochastacy.aws.dynamodb.table

import scala.collection.immutable.SortedMap
import scala.util.hashing.MurmurHash3

sealed trait LogicalPartitionAccess

object LogicalPartitionAccess:
  final case class SingleLogicalPartitionKey(keyToken: String) extends LogicalPartitionAccess:
    require(keyToken.nonEmpty, "SingleLogicalPartitionKey keyToken must be non-empty")

  final case class MultipleLogicalPartitionKeys(keyTokens: Vector[String]) extends LogicalPartitionAccess:
    require(keyTokens.nonEmpty, "MultipleLogicalPartitionKeys keyTokens must be non-empty")
    require(keyTokens.forall(_.nonEmpty), "MultipleLogicalPartitionKeys keyTokens must all be non-empty")

  case object AllPartitions extends LogicalPartitionAccess

final case class ResolvedPartitionFootprint(
                                             totalPartitionCount: Int,
                                             partitionDemandById: SortedMap[Int, BigDecimal]
                                           ):
  require(totalPartitionCount > 0, s"totalPartitionCount must be positive, got $totalPartitionCount")
  require(partitionDemandById.nonEmpty, "partitionDemandById must be non-empty")
  require(
    partitionDemandById.keys.forall(partitionId => partitionId >= 0 && partitionId < totalPartitionCount),
    s"partition ids must fall within [0, ${totalPartitionCount - 1}]"
  )
  require(
    partitionDemandById.values.forall(_ >= 0),
    "partition demand values must be non-negative"
  )

final case class PartitionTopologySnapshot(
                                            partitionCount: Int,
                                            version: Long,
                                            effectiveFromTick: Long
                                          ):
  require(partitionCount > 0, s"partitionCount must be positive, got $partitionCount")
  require(version >= 0L, s"version must be non-negative, got $version")
  require(effectiveFromTick >= 0L, s"effectiveFromTick must be non-negative, got $effectiveFromTick")

private[table] object PartitionAccessResolver:

  def validateOperationAccess(request: stochastacy.aws.dynamodb.DynamoDBRequest, access: LogicalPartitionAccess): Unit =
    import LogicalPartitionAccess.*

    request match
      case _: stochastacy.aws.dynamodb.GetItemRequest |
           _: stochastacy.aws.dynamodb.PutItemRequest |
           _: stochastacy.aws.dynamodb.UpdateItemRequest |
           _: stochastacy.aws.dynamodb.DeleteItemRequest =>
        access match
          case _: SingleLogicalPartitionKey => ()
          case other =>
            throw new IllegalArgumentException(
              s"${request.getClass.getSimpleName} requires SingleLogicalPartitionKey, got ${other.getClass.getSimpleName}"
            )

      case _: stochastacy.aws.dynamodb.QueryRequest =>
        access match
          case _: SingleLogicalPartitionKey | _: MultipleLogicalPartitionKeys => ()
          case other =>
            throw new IllegalArgumentException(
              s"QueryRequest requires SingleLogicalPartitionKey or MultipleLogicalPartitionKeys, got ${other.getClass.getSimpleName}"
            )

      case _: stochastacy.aws.dynamodb.ScanRequest =>
        access match
          case AllPartitions => ()
          case other =>
            throw new IllegalArgumentException(
              s"ScanRequest requires AllPartitions logical access, got ${other.getClass.getSimpleName}"
            )

      case _: stochastacy.aws.dynamodb.PartiQLQueryRequest =>
        ()

      case _: stochastacy.aws.dynamodb.TransactWriteItemsRequest |
           _: stochastacy.aws.dynamodb.TransactGetItemsRequest =>
        ()

  def resolve(
               access: LogicalPartitionAccess,
               throughputDemand: BigDecimal,
               partitionCount: Int
             ): ResolvedPartitionFootprint =
    resolve(
      access = access,
      throughputDemand = throughputDemand,
      topology = PartitionTopologySnapshot(
        partitionCount = partitionCount,
        version = 0L,
        effectiveFromTick = 0L
      )
    )

  def resolve(
               access: LogicalPartitionAccess,
               throughputDemand: BigDecimal,
               topology: PartitionTopologySnapshot
             ): ResolvedPartitionFootprint =
    import LogicalPartitionAccess.*

    val partitionCount = topology.partitionCount
    require(partitionCount > 0, s"partitionCount must be positive, got $partitionCount")

    val partitionDemandById: SortedMap[Int, BigDecimal] =
      access match
        case SingleLogicalPartitionKey(keyToken) =>
          SortedMap(resolvePartitionId(keyToken, partitionCount) -> throughputDemand)

        case MultipleLogicalPartitionKeys(keyTokens) =>
          val share = splitDemand(throughputDemand, keyTokens.size)
          keyTokens.foldLeft(SortedMap.empty[Int, BigDecimal].withDefaultValue(BigDecimal(0))) { (acc, keyToken) =>
            val partitionId = resolvePartitionId(keyToken, partitionCount)
            acc.updated(partitionId, acc(partitionId) + share)
          }

        case AllPartitions =>
          val share = splitDemand(throughputDemand, partitionCount)
          SortedMap((0 until partitionCount).map(partitionId => partitionId -> share)*)

    ResolvedPartitionFootprint(
      totalPartitionCount = partitionCount,
      partitionDemandById = partitionDemandById
    )

  private def resolvePartitionId(keyToken: String, partitionCount: Int): Int =
    Math.floorMod(MurmurHash3.stringHash(keyToken), partitionCount)

  private def splitDemand(totalDemand: BigDecimal, partCount: Int): BigDecimal =
    require(partCount > 0, s"partCount must be positive, got $partCount")
    BigDecimal(totalDemand.bigDecimal.divide(BigDecimal(partCount).bigDecimal, java.math.MathContext.DECIMAL128))
