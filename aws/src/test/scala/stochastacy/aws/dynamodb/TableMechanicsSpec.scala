package stochastacy.aws.dynamodb

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.{OperationOutcome, ReadShape}

class TableMechanicsSpec extends AnyWordSpec with should.Matchers:

  private val strong   = ReadConsistency.StronglyConsistent
  private val eventual = ReadConsistency.EventuallyConsistent
  private val Table    = DynamoDbTarget.Table
  private val start    = TableSummaryState.initial(itemCount = 10L, averageItemBytes = 768L)

  // 20 items x 768 bytes = 15360 evaluated bytes -> ceil(15360 / 4096) = 4 RCU (strong), 2 (eventual)
  private val readShape = ReadShape(evaluatedItemCount = 20L, evaluatedBytes = 20L * 768L, returnedItemCount = 12L, returnedBytes = 12L * 768L)

  "TableMechanics.resolve — Get" should {
    "return the item, charge one RCU on the base table, and leave storage untouched on a hit" in {
      val r = TableMechanics.resolve(OperationOutcome.Get(Some(768L), strong), start)
      r.response    shouldBe GetItemResponse(itemFound = true, itemBytes = Some(768L))
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(1), strong, Table))
      r.state       shouldBe start
    }

    "report not-found and charge the minimum RCU on a miss" in {
      val r = TableMechanics.resolve(OperationOutcome.Get(None, strong), start)
      r.response    shouldBe GetItemResponse(itemFound = false, itemBytes = None)
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(1), strong, Table))
      r.state       shouldBe start
    }
  }

  "TableMechanics.resolve — Put" should {
    "insert a new item, charge WCU, and emit a positive storage delta (all on the base table)" in {
      val r = TableMechanics.resolve(OperationOutcome.Put(writtenItemBytes = 800L, previousItemBytes = None), start)
      r.response    shouldBe PutItemResponse(storedItemBytes = 800L, createdNewItem = true, previousItemBytes = None)
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(800L, Table))
      r.state       shouldBe TableSummaryState(11L, 8480L)
    }
  }

  "TableMechanics.resolve — Update" should {
    "replace in place with a difference-sized storage delta" in {
      val r = TableMechanics.resolve(OperationOutcome.Update(writtenItemBytes = 900L, previousItemBytes = Some(768L)), start)
      r.response    shouldBe UpdateItemResponse(storedItemBytes = 900L, createdNewItem = false, previousItemBytes = Some(768L))
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(900L - 768L, Table))
      r.state       shouldBe TableSummaryState(10L, 7680L - 768L + 900L)
    }

    "upsert as a new item when nothing existed" in {
      val r = TableMechanics.resolve(OperationOutcome.Update(writtenItemBytes = 700L, previousItemBytes = None), start)
      r.response shouldBe UpdateItemResponse(storedItemBytes = 700L, createdNewItem = true, previousItemBytes = None)
      r.state    shouldBe TableSummaryState(11L, 8380L)
    }
  }

  "TableMechanics.resolve — Delete" should {
    "remove an existing item, charge WCU, and emit a negative storage delta" in {
      val r = TableMechanics.resolve(OperationOutcome.Delete(Some(768L)), start)
      r.response    shouldBe DeleteItemResponse(deletedItemBytes = Some(768L))
      r.consumption should contain theSameElementsAs List(WriteCapacityConsumed(BigDecimal(1), Table), StorageBytesDelta(-768L, Table))
      r.state       shouldBe TableSummaryState(9L, 6912L)
    }

    "still charge one WCU but emit no storage delta when the item was absent" in {
      val r = TableMechanics.resolve(OperationOutcome.Delete(None), start)
      r.response    shouldBe DeleteItemResponse(deletedItemBytes = None)
      r.consumption shouldBe List(WriteCapacityConsumed(BigDecimal(1), Table))
      r.state       shouldBe start
    }
  }

  "TableMechanics.resolve — Query / Scan" should {
    "charge RCU from evaluated bytes on the base table and echo the read shape, without changing state" in {
      val r = TableMechanics.resolve(OperationOutcome.Query(Table, strong, readShape), start)
      r.response    shouldBe QueryResponse(20L, 15360L, 12L, 9216L)
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(4), strong, Table))
      r.state       shouldBe start
    }

    "halve the RCU for an eventually-consistent read" in {
      val r = TableMechanics.resolve(OperationOutcome.Query(Table, eventual, readShape), start)
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(2), eventual, Table))
    }

    "tag a GSI-targeted scan's RCU with the index target" in {
      val gsi = DynamoDbTarget.Gsi("customerId-status")
      val r   = TableMechanics.resolve(OperationOutcome.Scan(gsi, eventual, readShape), start)
      r.response    shouldBe ScanResponse(20L, 15360L, 12L, 9216L)
      r.consumption shouldBe List(ReadCapacityConsumed(BigDecimal(2), eventual, gsi))
      r.state       shouldBe start
    }
  }

  "TableMechanics.resolve — TransactWrite" should {
    "bill 2x base WCU per item, emit per-item storage deltas, and thread state across sub-writes" in {
      // an insert (800 B) then an overwrite (900 B replacing 768 B)
      val items = Vector(
        TableMechanics.TransactWriteItem(writtenItemBytes = 800L, previousItemBytes = None),
        TableMechanics.TransactWriteItem(writtenItemBytes = 900L, previousItemBytes = Some(768L))
      )
      val r = TableMechanics.resolve(OperationOutcome.TransactWrite(items), start)
      r.response shouldBe TransactWriteItemsResponse(2)
      // base WCU doubled (ceil(800/1024)=1 ->2, ceil(900/1024)=1 ->2); storage +800 then +132
      r.consumption shouldBe List(
        WriteCapacityConsumed(BigDecimal(2), Table), StorageBytesDelta(800L, Table),
        WriteCapacityConsumed(BigDecimal(2), Table), StorageBytesDelta(132L, Table)
      )
      // 10/7680 -> insert 11/8480 -> overwrite 11/8612
      r.state shouldBe TableSummaryState(11L, 8612L)
    }
  }

  "TableMechanics.resolve — TransactGet" should {
    "bill 2x strong RCU per item and leave state unchanged" in {
      val r = TableMechanics.resolve(OperationOutcome.TransactGet(Vector(Some(4096L), None)), start)
      r.response    shouldBe TransactGetItemsResponse(Vector(Some(4096L), None))
      r.consumption shouldBe List(
        ReadCapacityConsumed(BigDecimal(2), strong, Table), // 1 strong chunk x2
        ReadCapacityConsumed(BigDecimal(2), strong, Table)  // miss minimum x2
      )
      r.state shouldBe start
    }
  }
