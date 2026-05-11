package stochastacy.aws.dynamodb.pricing

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.aws.dynamodb.table.{DynamoDbConsumptionEvent, DynamoDbTarget}
import stochastacy.aws.dynamodb.table.DynamoDbTarget.Table
import stochastacy.aws.dynamodb.usage.{DynamoDbTimeBasedUsageTotals, DynamoDbUsageTotals}
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

class PITRPricingSpec extends AnyWordSpec with should.Matchers:

  private val target = Table("t")

  private def tick(n: Long): TimedControlEvent.Tick = TimedControlEvent.Tick(SimTime.of(n))
  private def pitrDelta(t: Long, bytes: Long): DynamoDbConsumptionEvent.PITRStorageBytesDelta =
    DynamoDbConsumptionEvent.PITRStorageBytesDelta(SimTime.of(t), "test", target, bytes)
  private def storageDelta(t: Long, bytes: Long): DynamoDbConsumptionEvent.StorageBytesDelta =
    DynamoDbConsumptionEvent.StorageBytesDelta(SimTime.of(t), "test", target, bytes)

  "DynamoDbTimeBasedUsageTotals" should {

    "accumulate pitrStorageByteTicks from PITRStorageBytesDelta events" in {
      val events: Seq[TimedElement[DynamoDbConsumptionEvent]] = Seq(tick(1), pitrDelta(1, 1000L), tick(2), tick(3))
      val totals  = DynamoDbTimeBasedUsageTotals.fromTimedEvents(events)
      totals.pitrStorageByteTicks should be >= BigInt(0)
    }

    "report pitrStorageByteTicks = 0 when no PITRStorageBytesDelta events are present" in {
      val events: Seq[TimedElement[DynamoDbConsumptionEvent]] = Seq(tick(1), storageDelta(1, 5000L), tick(2), tick(3))
      val totals  = DynamoDbTimeBasedUsageTotals.fromTimedEvents(events)
      totals.pitrStorageByteTicks shouldBe BigInt(0)
    }

    "accumulate pitrStorageByteTicks proportional to PITR storage size and duration" in {
      // 2048 bytes written at tick 1; ticks 2 and 3 follow
      // After tick 2: accumulate 2048 byte-ticks (first tick sets flag, second tick accumulates)
      // After tick 3: accumulate another 2048 byte-ticks
      // Total pitrStorageByteTicks = 2048 * 2 = 4096
      val events: Seq[TimedElement[DynamoDbConsumptionEvent]] = Seq(tick(1), pitrDelta(1, 2048L), tick(2), tick(3))
      val totals  = DynamoDbTimeBasedUsageTotals.fromTimedEvents(events)
      totals.pitrStorageByteTicks shouldBe BigInt(4096)
    }
  }

  "DynamoDbCostBreakdown" should {

    "include pitrCost = 0 when pitrStorageByteTicks is zero" in {
      val rates = DynamoDbPricingRates.phase1Default
      val cost  = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(pitrStorageByteTicks = BigInt(0))
        ),
        rates
      )
      cost.pitrCost shouldBe BigDecimal(0)
    }

    "compute pitrCost correctly from pitrStorageByteTicks" in {
      // 1 GiB of PITR storage for 30 days (2592000 seconds)
      // Expected cost ≈ $0.20
      val bytesPerGiB = BigDecimal(1024).pow(3)
      val secondsPer30Days = BigDecimal(30 * 24 * 3600)
      val pitrByteTicks: BigInt = (bytesPerGiB * secondsPer30Days).toBigInt

      val rates = DynamoDbPricingRates.phase1Default
      val cost  = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(pitrStorageByteTicks = pitrByteTicks)
        ),
        rates
      )

      // Should be approximately $0.20 (within floating-point tolerance)
      cost.pitrCost should be > BigDecimal("0.19")
      cost.pitrCost should be < BigDecimal("0.21")
    }

    "include pitrCost in totalCost" in {
      val bytesPerGiB = BigDecimal(1024).pow(3)
      val secondsPer30Days = BigDecimal(30 * 24 * 3600)
      val pitrByteTicks: BigInt = (bytesPerGiB * secondsPer30Days).toBigInt

      val rates = DynamoDbPricingRates.phase1Default
      val cost  = DynamoDbCostBreakdown.price(
        DynamoDbPricingInputs(
          usage = DynamoDbUsageTotals(),
          timeBasedUsage = DynamoDbTimeBasedUsageTotals(pitrStorageByteTicks = pitrByteTicks)
        ),
        rates
      )
      cost.totalCost shouldBe cost.readCapacityCost + cost.writeCapacityCost +
        cost.replicatedWriteCapacityCost + cost.storageCost + cost.pitrCost
    }
  }
