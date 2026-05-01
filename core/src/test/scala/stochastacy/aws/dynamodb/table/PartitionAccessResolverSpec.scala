package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class PartitionAccessResolverSpec extends AnyWordSpec with should.Matchers:

  import LogicalPartitionAccess.*

  "PartitionAccessResolver" should {
    "resolve a single logical key deterministically to one partition" in {
      val first = PartitionAccessResolver.resolve(SingleLogicalPartitionKey("customer-42"), BigDecimal(3), partitionCount = 8)
      val second = PartitionAccessResolver.resolve(SingleLogicalPartitionKey("customer-42"), BigDecimal(3), partitionCount = 8)

      first shouldBe second
      first.partitionDemandById.values.toVector shouldBe Vector(BigDecimal(3))
    }

    "distribute demand evenly across logical keys before aggregating concrete partitions" in {
      val footprint =
        PartitionAccessResolver.resolve(
          MultipleLogicalPartitionKeys(Vector("a", "b")),
          BigDecimal(4),
          partitionCount = 8
        )

      footprint.partitionDemandById.values.sum shouldBe BigDecimal(4)
      footprint.partitionDemandById.values.forall(_ <= BigDecimal(2)) shouldBe true
    }

    "aggregate colliding logical keys onto the same concrete partition" in {
      val (left, right) = twoKeysForSamePartition(partitionCount = 4)
      val footprint =
        PartitionAccessResolver.resolve(
          MultipleLogicalPartitionKeys(Vector(left, right)),
          BigDecimal(2),
          partitionCount = 4
        )

      footprint.partitionDemandById.size shouldBe 1
      footprint.partitionDemandById.values.toVector shouldBe Vector(BigDecimal(2))
    }

    "distribute all-partitions access across the full topology" in {
      val footprint = PartitionAccessResolver.resolve(AllPartitions, BigDecimal(6), partitionCount = 3)

      footprint.partitionDemandById.keySet shouldBe Set(0, 1, 2)
      footprint.partitionDemandById.values.toSet shouldBe Set(BigDecimal(2))
    }

    "resolve against the active topology snapshot rather than a fixed partition count" in {
      val movedKey =
        (0 until 10_000)
          .map(i => s"moving-key-$i")
          .find { token =>
            PartitionAccessResolver
              .resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount = 2)
              .partitionDemandById
              .head
              ._1 == 1
          }
          .getOrElse(fail("Unable to find key that moves under a larger topology"))

      val before =
        PartitionAccessResolver.resolve(
          SingleLogicalPartitionKey(movedKey),
          BigDecimal(1),
          PartitionTopologySnapshot(partitionCount = 1, version = 0L, effectiveFromTick = 0L)
        )
      val after =
        PartitionAccessResolver.resolve(
          SingleLogicalPartitionKey(movedKey),
          BigDecimal(1),
          PartitionTopologySnapshot(partitionCount = 2, version = 1L, effectiveFromTick = 2L)
        )

      before.totalPartitionCount shouldBe 1
      after.totalPartitionCount shouldBe 2
      before.partitionDemandById.head._1 should not be after.partitionDemandById.head._1
    }
  }

  private def twoKeysForSamePartition(partitionCount: Int): (String, String) =
    val grouped =
      (0 until 10_000)
        .map(i => s"key-$i")
        .groupBy { token =>
          PartitionAccessResolver.resolve(SingleLogicalPartitionKey(token), BigDecimal(1), partitionCount).partitionDemandById.head._1
        }

    grouped.values.collectFirst {
      case tokens if tokens.size >= 2 => (tokens(0), tokens(1))
    }.getOrElse(fail("Unable to find colliding logical partition keys for resolver test"))
