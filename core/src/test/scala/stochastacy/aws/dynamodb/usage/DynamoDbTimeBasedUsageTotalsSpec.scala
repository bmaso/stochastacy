package stochastacy.aws.dynamodb.usage

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTarget}
import stochastacy.sim.{SimTime, TimedControlEvent}

class DynamoDbTimeBasedUsageTotalsSpec extends AnyWordSpec with should.Matchers:

  "DynamoDbTimeBasedUsageTotals" should {
    "derive storage byte-ticks and ending storage bytes from timed storage deltas" in {
      val ordersTable = DynamoDbTarget.Table("orders")
      val ordersIndex = DynamoDbTarget.GlobalSecondaryIndex("orders", "by-status")

      val totals = DynamoDbTimeBasedUsageTotals.fromTimedEvents(
        Seq(
          TimedControlEvent.Tick(SimTime.of(10L)),
          DynamoDbConsumptionEvent.StorageBytesDelta(
            eventTime = SimTime.of(10L),
            usecase = "demo",
            target = ordersTable,
            bytesDelta = 100L
          ),
          TimedControlEvent.Tick(SimTime.of(11L)),
          DynamoDbConsumptionEvent.StorageBytesDelta(
            eventTime = SimTime.of(11L),
            usecase = "demo",
            target = ordersIndex,
            bytesDelta = 50L
          ),
          TimedControlEvent.Tick(SimTime.of(12L)),
          DynamoDbConsumptionEvent.StorageBytesDelta(
            eventTime = SimTime.of(12L),
            usecase = "demo",
            target = ordersTable,
            bytesDelta = -40L
          ),
          TimedControlEvent.Tick(SimTime.of(13L))
        )
      )

      totals.overallStorageByteTicks shouldBe BigInt(360)
      totals.endingOverallStorageBytes shouldBe 110L
      totals.byTarget shouldBe Map(
        ordersTable -> DynamoDbTargetTimeBasedUsageTotals(
          storageByteTicks = BigInt(260),
          endingStorageBytes = 60L
        ),
        ordersIndex -> DynamoDbTargetTimeBasedUsageTotals(
          storageByteTicks = BigInt(100),
          endingStorageBytes = 50L
        )
      )
    }
  }
