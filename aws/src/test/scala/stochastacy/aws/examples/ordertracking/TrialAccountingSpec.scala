package stochastacy.aws.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{DynamoDbConsumption, ReadCapacityConsumed, ReadConsistency, StorageBytesDelta, WriteCapacityConsumed}
import stochastacy.core.component.Timed
import stochastacy.sim.{SimTime, TimedControlEvent, TimedElement}

class TrialAccountingSpec extends AnyWordSpec with should.Matchers:

  private val rates  = OnDemandPricing.phase1Default
  private val strong = ReadConsistency.StronglyConsistent

  private def tick(t: Long): TimedElement[Timed[DynamoDbConsumption]]            = TimedControlEvent.Tick(SimTime.of(t))
  private val eot: TimedElement[Timed[DynamoDbConsumption]]                      = TimedControlEvent.EndOfTime
  private def cons(t: Long, c: DynamoDbConsumption): TimedElement[Timed[DynamoDbConsumption]] =
    Timed(c, SimTime.of(t), 0.0, "orders")

  "TrialAccounting.account" should {

    "count the table's initial storage in byte-ticks (seeded, not started from zero)" in {
      // N = 2 real ticks: Tick(1), Tick(2), flush Tick(3); no deltas.
      val (summary, series) = TrialAccounting.account(Vector(tick(1), tick(2), tick(3), eot), initialStorageBytes = 7680L, rates)
      summary.finalStorageBytes     shouldBe 7680L
      summary.totalStorageByteTicks shouldBe BigInt(7680L * 2) // held across both closed windows
      series.map(_.tick)            shouldBe Vector(1L, 2L)
      all(series.map(_.storageBytes)) shouldBe 7680L
    }

    "move absolute storage by deltas, with no accrual before the first tick" in {
      // initial 1000; +100 in window 1; N = 2.
      val (summary, series) = TrialAccounting.account(
        Vector(tick(1), cons(1, StorageBytesDelta(100L)), tick(2), tick(3), eot),
        initialStorageBytes = 1000L, rates
      )
      summary.finalStorageBytes     shouldBe 1100L
      summary.totalStorageByteTicks shouldBe BigInt(1100L * 2) // 1100 held across both windows; the pre-write 1000 alone is never accrued
      all(series.map(_.storageBytes)) shouldBe 1100L
    }

    "sum capacity units and reconcile the summary with the time series" in {
      val stream = Vector(
        tick(1),
        cons(1, ReadCapacityConsumed(BigDecimal(2), strong)),
        cons(1, StorageBytesDelta(500L)),
        tick(2),
        cons(2, WriteCapacityConsumed(BigDecimal(3))),
        tick(3),
        cons(3, ReadCapacityConsumed(BigDecimal(1), strong)),
        cons(3, StorageBytesDelta(-200L)),
        tick(4),
        eot
      )
      val (summary, series) = TrialAccounting.account(stream, initialStorageBytes = 1000L, rates)

      summary.totalReadCapacityUnits  shouldBe BigDecimal(3)
      summary.totalWriteCapacityUnits shouldBe BigDecimal(3)
      summary.totalStorageByteTicks   shouldBe BigInt(1500 + 1500 + 1300) // 4300
      summary.finalStorageBytes       shouldBe 1300L

      // reconciliation with the per-tick series
      series.map(_.tick)                              shouldBe Vector(1L, 2L, 3L)
      series.map(_.readCapacityUnits).sum             shouldBe summary.totalReadCapacityUnits
      series.map(_.writeCapacityUnits).sum            shouldBe summary.totalWriteCapacityUnits
      series.map(p => BigInt(p.storageBytes)).sum     shouldBe summary.totalStorageByteTicks
      series.last.storageBytes                        shouldBe summary.finalStorageBytes
      series.last.cumulativeEstimatedCost             shouldBe summary.totalEstimatedCost
    }

    "price totals with the on-demand rate model" in {
      val (summary, _) = TrialAccounting.account(
        Vector(tick(1), cons(1, ReadCapacityConsumed(BigDecimal(10), strong)), tick(2), tick(3), eot),
        initialStorageBytes = 0L, rates
      )
      summary.totalEstimatedCost shouldBe OnDemandPricing.cost(BigDecimal(10), BigDecimal(0), summary.totalStorageByteTicks, rates)
    }
  }
