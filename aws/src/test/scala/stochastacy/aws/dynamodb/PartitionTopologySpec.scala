package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class PartitionTopologySpec extends AnyWordSpec with should.Matchers:

  "PartitionTopology.derive" should {

    "return one partition for a small provisioned table" in {
      PartitionTopology.derive(readCapacityUnits = 100, writeCapacityUnits = 100, storageBytes = 0L) shouldBe 1
    }

    "grow the count from capacity (3000 RCU / 1000 WCU per partition)" in {
      PartitionTopology.derive(readCapacityUnits = 0, writeCapacityUnits = 5000, storageBytes = 0L) shouldBe 5   // ceil(5000/1000)
      PartitionTopology.derive(readCapacityUnits = 6000, writeCapacityUnits = 0, storageBytes = 0L) shouldBe 2   // ceil(6000/3000)
      PartitionTopology.derive(readCapacityUnits = 3000, writeCapacityUnits = 4000, storageBytes = 0L) shouldBe 5 // ceil(1 + 4)
    }

    "grow the count from storage (10 GiB per partition) and take the greater dimension" in {
      val gib = 1024L * 1024L * 1024L
      PartitionTopology.derive(readCapacityUnits = 100, writeCapacityUnits = 100, storageBytes = 25L * gib) shouldBe 3 // ceil(25/10)
      // storage needs 3 partitions, capacity needs 5 → the greater wins
      PartitionTopology.derive(readCapacityUnits = 0, writeCapacityUnits = 5000, storageBytes = 25L * gib) shouldBe 5
    }
  }

  "PartitionTopology.partitionOf" should {
    "map a key deterministically into [0, count)" in {
      val count = 8
      all((0 until 50).map(i => PartitionTopology.partitionOf(s"device-$i", count))) should (be >= 0 and be < count)
      PartitionTopology.partitionOf("device-7", count) shouldBe PartitionTopology.partitionOf("device-7", count)
    }
    "spread distinct keys across more than one partition" in {
      (0 until 100).map(i => PartitionTopology.partitionOf(s"k$i", 8)).distinct.size should be > 1
    }
  }
