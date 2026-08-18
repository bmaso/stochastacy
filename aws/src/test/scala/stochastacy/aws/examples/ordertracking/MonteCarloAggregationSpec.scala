package stochastacy.aws.examples.ordertracking

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class MonteCarloAggregationSpec extends AnyWordSpec with should.Matchers:

  private def trial(id: Int, rcuPerTick: BigDecimal, totalRcu: BigDecimal): OrderTrackingTrialResult =
    OrderTrackingTrialResult(
      trialId = id,
      timeSeries = Vector(
        TrialTimeSeriesPoint(tick = 1L, readCapacityUnits = rcuPerTick, writeCapacityUnits = 0, storageBytes = 100L, cumulativeEstimatedCost = 0),
        TrialTimeSeriesPoint(tick = 2L, readCapacityUnits = rcuPerTick, writeCapacityUnits = 0, storageBytes = 200L, cumulativeEstimatedCost = 0)
      ),
      summary = TrialSummary(
        totalReadCapacityUnits = totalRcu, totalWriteCapacityUnits = 0,
        totalStorageByteTicks = BigInt(0), finalStorageBytes = 0L, totalEstimatedCost = 0
      )
    )

  "MonteCarloAggregation.summary" should {
    "compute the across-trial mean and population stddev for a metric" in {
      val trials = Vector(trial(0, 1, totalRcu = 10), trial(1, 1, totalRcu = 20), trial(2, 1, totalRcu = 30))
      val agg    = MonteCarloAggregation.summary(trials)

      def stat(metric: String, s: AggregateStatistic): BigDecimal =
        agg.collectFirst { case AggregateSummaryValue(`metric`, `s`, v) => v }.getOrElse(fail(s"missing $metric/$s"))

      stat("TotalReadCapacityUnits", AggregateStatistic.Mean)   shouldBe BigDecimal(20)
      // population variance of {10,20,30} = 200/3; stddev = sqrt(66.67) ≈ 8.165
      stat("TotalReadCapacityUnits", AggregateStatistic.StdDev).toDouble shouldBe (8.165 +- 0.01)
    }

    "emit mean and stddev for every summary metric" in {
      val agg = MonteCarloAggregation.summary(Vector(trial(0, 1, 10), trial(1, 1, 20)))
      agg.map(_.metric).distinct    should contain theSameElementsAs MonteCarloAggregation.summaryMetrics.map(_._1)
      agg.map(_.statistic).distinct should contain theSameElementsAs Seq(AggregateStatistic.Mean, AggregateStatistic.StdDev)
    }
  }

  "MonteCarloAggregation.timeSeries" should {
    "aggregate each tick × metric across trials" in {
      val trials = Vector(trial(0, rcuPerTick = 2, 0), trial(1, rcuPerTick = 4, 0))
      val agg    = MonteCarloAggregation.timeSeries(trials)

      agg.map(_.tick).distinct should contain theSameElementsAs Seq(1L, 2L)
      val tick1ReadMean = agg.collectFirst {
        case AggregateTimeSeriesPoint(1L, "ReadCapacityUnits", AggregateStatistic.Mean, v) => v
      }
      tick1ReadMean shouldBe Some(BigDecimal(3)) // mean of {2, 4}
    }
  }
