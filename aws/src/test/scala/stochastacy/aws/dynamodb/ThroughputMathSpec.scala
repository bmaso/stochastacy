package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class ThroughputMathSpec extends AnyWordSpec with should.Matchers:

  "ThroughputMath.readCapacityUnits" should {
    "charge one RCU for a strongly-consistent read within a single 4 KB chunk" in {
      ThroughputMath.readCapacityUnits(Some(768L), ReadConsistency.StronglyConsistent) shouldBe BigDecimal(1)
    }

    "charge half an RCU for the same read eventually-consistent" in {
      ThroughputMath.readCapacityUnits(Some(768L), ReadConsistency.EventuallyConsistent) shouldBe BigDecimal("0.5")
    }

    "treat exactly 4096 bytes as one chunk and 4097 as two (strong)" in {
      ThroughputMath.readCapacityUnits(Some(4096L), ReadConsistency.StronglyConsistent) shouldBe BigDecimal(1)
      ThroughputMath.readCapacityUnits(Some(4097L), ReadConsistency.StronglyConsistent) shouldBe BigDecimal(2)
    }

    "charge the one-chunk minimum for a miss" in {
      ThroughputMath.readCapacityUnits(None, ReadConsistency.StronglyConsistent) shouldBe BigDecimal(1)
      ThroughputMath.readCapacityUnits(None, ReadConsistency.EventuallyConsistent) shouldBe BigDecimal("0.5")
    }
  }

  "ThroughputMath.writeCapacityUnits" should {
    "charge one WCU within a single 1 KB chunk" in {
      ThroughputMath.writeCapacityUnits(768L)  shouldBe BigDecimal(1)
      ThroughputMath.writeCapacityUnits(1024L) shouldBe BigDecimal(1)
    }

    "roll over to a second chunk at 1025 bytes" in {
      ThroughputMath.writeCapacityUnits(1025L) shouldBe BigDecimal(2)
    }

    "charge the one-chunk minimum for zero bytes" in {
      ThroughputMath.writeCapacityUnits(0L) shouldBe BigDecimal(1)
    }
  }
