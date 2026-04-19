package stochastacy.aws.dynamodb.usage

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTarget, ReadConsistency}
import stochastacy.sim.SimTime

class DynamoDbUsageTotalsSpec extends AnyWordSpec with should.Matchers:

  "DynamoDbUsageTotals" should {
    "accumulate mixed consumption events into overall totals and per-target totals" in {
      val ordersTable = DynamoDbTarget.Table("orders")
      val ordersIndex = DynamoDbTarget.GlobalSecondaryIndex("orders", "by-status")

      val events = Seq(
        DynamoDbConsumptionEvent.ReadCapacityConsumed(
          eventTime = SimTime.of(1L),
          usecase = "demo",
          target = ordersTable,
          units = BigDecimal(0.5),
          consistency = ReadConsistency.EventuallyConsistent
        ),
        DynamoDbConsumptionEvent.StorageBytesRead(
          eventTime = SimTime.of(1L),
          usecase = "demo",
          target = ordersTable,
          bytes = 512L
        ),
        DynamoDbConsumptionEvent.WriteCapacityConsumed(
          eventTime = SimTime.of(2L),
          usecase = "demo",
          target = ordersIndex,
          units = BigDecimal(2.0)
        ),
        DynamoDbConsumptionEvent.StorageBytesWritten(
          eventTime = SimTime.of(2L),
          usecase = "demo",
          target = ordersIndex,
          bytes = 2048L
        ),
        DynamoDbConsumptionEvent.StorageBytesDeleted(
          eventTime = SimTime.of(3L),
          usecase = "demo",
          target = ordersIndex,
          bytes = 512L
        ),
        DynamoDbConsumptionEvent.StorageBytesDelta(
          eventTime = SimTime.of(2L),
          usecase = "demo",
          target = ordersIndex,
          bytesDelta = 1024L
        )
      )

      val totals = events.foldLeft(DynamoDbUsageTotals())(DynamoDbUsageTotals.accumulate)

      totals.overall.readCapacityUnits shouldBe BigDecimal(0.5)
      totals.overall.writeCapacityUnits shouldBe BigDecimal(2.0)
      totals.overall.storageBytesRead shouldBe 512L
      totals.overall.storageBytesWritten shouldBe 2048L
      totals.overall.storageBytesDeleted shouldBe 512L
      totals.overall.storageBytesDelta shouldBe 1024L

      totals.byTarget(ordersTable) shouldBe DynamoDbTargetUsageTotals(
        readCapacityUnits = BigDecimal(0.5),
        writeCapacityUnits = BigDecimal(0),
        storageBytesRead = 512L,
        storageBytesWritten = 0L,
        storageBytesDeleted = 0L,
        storageBytesDelta = 0L
      )

      totals.byTarget(ordersIndex) shouldBe DynamoDbTargetUsageTotals(
        readCapacityUnits = BigDecimal(0),
        writeCapacityUnits = BigDecimal(2.0),
        storageBytesRead = 0L,
        storageBytesWritten = 2048L,
        storageBytesDeleted = 512L,
        storageBytesDelta = 1024L
      )
    }
  }
