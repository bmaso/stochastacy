package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class SecondaryIndexMechanicsSpec extends AnyWordSpec with should.Matchers:

  private val gsi   = GlobalSecondaryIndex("customerId-status")                       // All projection
  private val gTgt  = DynamoDbTarget.Gsi("customerId-status")
  private val start = TableSummaryState.initial(itemCount = 10L, averageItemBytes = 768L) // 7680 bytes

  "SecondaryIndexMechanics.projectedEntryBytes" should {
    "pass the full item through for All, cap at the key size for KeysOnly, and at key+extra for Include" in {
      SecondaryIndexMechanics.projectedEntryBytes(Some(800L), IndexProjection.All)          shouldBe Some(800L)
      SecondaryIndexMechanics.projectedEntryBytes(Some(800L), IndexProjection.KeysOnly)     shouldBe Some(128L)
      SecondaryIndexMechanics.projectedEntryBytes(Some(800L), IndexProjection.Include(200)) shouldBe Some(328L)
      SecondaryIndexMechanics.projectedEntryBytes(Some(100L), IndexProjection.KeysOnly)     shouldBe Some(100L) // already under the cap
      SecondaryIndexMechanics.projectedEntryBytes(None, IndexProjection.All)                shouldBe None
    }
  }

  "SecondaryIndexMechanics.maintain — All projection" should {
    "insert an entry for a new base item (WCU + positive storage delta, tagged the index target)" in {
      val m = SecondaryIndexMechanics.maintain(gsi, newBaseItemBytes = Some(800L), previousBaseItemBytes = None, TableSummaryState.empty)
      m.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), gTgt), StorageBytesDelta(800L, gTgt))
      m.state       shouldBe TableSummaryState(1L, 800L)
    }

    "replace an entry whose projected size changed" in {
      val m = SecondaryIndexMechanics.maintain(gsi, Some(900L), Some(768L), start)
      m.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), gTgt), StorageBytesDelta(900L - 768L, gTgt))
      m.state       shouldBe TableSummaryState(10L, 7680L - 768L + 900L)
    }

    "delete an entry for a removed base item (WCU on the old entry, negative storage delta)" in {
      val m = SecondaryIndexMechanics.maintain(gsi, None, Some(768L), start)
      m.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), gTgt), StorageBytesDelta(-768L, gTgt))
      m.state       shouldBe TableSummaryState(9L, 6912L)
    }

    "do nothing when the projected entry is unchanged, or when there is no entry either side" in {
      SecondaryIndexMechanics.maintain(gsi, Some(768L), Some(768L), start) shouldBe SecondaryIndexMechanics.Maintenance(Nil, start)
      SecondaryIndexMechanics.maintain(gsi, None, None, start)             shouldBe SecondaryIndexMechanics.Maintenance(Nil, start)
    }
  }

  "SecondaryIndexMechanics.maintain — projections" should {
    "charge and store only the projected entry size (KeysOnly caps at the key size)" in {
      val keysOnly = GlobalSecondaryIndex("g", IndexProjection.KeysOnly)
      val m = SecondaryIndexMechanics.maintain(keysOnly, Some(4096L), None, TableSummaryState.empty)
      m.consumption should contain theSameElementsAs List(
        WriteCapacityConsumed(BigDecimal(1), DynamoDbTarget.Gsi("g")), StorageBytesDelta(128L, DynamoDbTarget.Gsi("g"))
      )
      m.state shouldBe TableSummaryState(1L, 128L)
    }

    "treat a base change that does not move the projected size as a no-op" in {
      val keysOnly = GlobalSecondaryIndex("g", IndexProjection.KeysOnly)
      // 800 and 900 both project to 128 (KeysOnly) -> no index change
      SecondaryIndexMechanics.maintain(keysOnly, Some(800L), Some(900L), start) shouldBe SecondaryIndexMechanics.Maintenance(Nil, start)
    }
  }
