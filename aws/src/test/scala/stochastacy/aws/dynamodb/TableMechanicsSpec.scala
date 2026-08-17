package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

class TableMechanicsSpec extends AnyWordSpec with should.Matchers:

  private val strong = ReadConsistency.StronglyConsistent
  private val start  = TableSummaryState.initial(itemCount = 10L, averageItemBytes = 768L)

  "TableMechanics.resolve — Get" should {
    "return the item, charge one RCU, and leave storage untouched on a hit" in {
      val r = TableMechanics.resolve(OperationOutcome.Get(Some(768L)), strong, start)
      r.response    shouldBe GetItemResponse(itemFound = true, itemBytes = Some(768L))
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(1), strong))
      r.state       shouldBe start
    }

    "report not-found and charge the minimum RCU on a miss" in {
      val r = TableMechanics.resolve(OperationOutcome.Get(None), strong, start)
      r.response    shouldBe GetItemResponse(itemFound = false, itemBytes = None)
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(1), strong))
      r.state       shouldBe start
    }
  }

  "TableMechanics.resolve — Put" should {
    "insert a new item, charge WCU, and emit a positive storage delta" in {
      val r = TableMechanics.resolve(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None), strong, start)
      r.response    shouldBe PutItemResponse(storedItemBytes = 800L, createdNewItem = true, previousItemBytes = None)
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1)), StorageBytesDelta(800L))
      r.state       shouldBe TableSummaryState(11L, 8480L)
    }
  }

  "TableMechanics.resolve — Update" should {
    "replace in place with a difference-sized storage delta" in {
      val r = TableMechanics.resolve(OperationOutcome.Update(writtenItemBytes = 900L, previousItemBytes = Some(768L)), strong, start)
      r.response    shouldBe UpdateItemResponse(storedItemBytes = 900L, createdNewItem = false, previousItemBytes = Some(768L))
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1)), StorageBytesDelta(900L - 768L))
      r.state       shouldBe TableSummaryState(10L, 7680L - 768L + 900L)
    }

    "upsert as a new item when nothing existed" in {
      val r = TableMechanics.resolve(OperationOutcome.Update(writtenItemBytes = 700L, previousItemBytes = None), strong, start)
      r.response shouldBe UpdateItemResponse(storedItemBytes = 700L, createdNewItem = true, previousItemBytes = None)
      r.state    shouldBe TableSummaryState(11L, 8380L)
    }
  }

  "TableMechanics.resolve — Delete" should {
    "remove an existing item, charge WCU, and emit a negative storage delta" in {
      val r = TableMechanics.resolve(OperationOutcome.Delete(Some(768L)), strong, start)
      r.response    shouldBe DeleteItemResponse(deletedItemBytes = Some(768L))
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1)), StorageBytesDelta(-768L))
      r.state       shouldBe TableSummaryState(9L, 6912L)
    }

    "still charge one WCU but emit no storage delta when the item was absent" in {
      val r = TableMechanics.resolve(OperationOutcome.Delete(None), strong, start)
      r.response    shouldBe DeleteItemResponse(deletedItemBytes = None)
      r.consumption shouldBe List(WriteCapacityConsumed(BigDecimal(1)))
      r.state       shouldBe start
    }
  }
