package stochastacy.aws.examples.demo

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class IncrementalAggregatorSpec extends AnyWordSpec with should.Matchers:

  private def trial(id: Int, rcuPerTick: BigDecimal, totalRcu: BigDecimal, gsiWcu: BigDecimal = 0): TrialResult =
    TrialResult(
      trialId = id,
      timeSeries = Vector(
        TrialTimeSeriesPoint(1L, rcuPerTick, 0, 100L, 0),
        TrialTimeSeriesPoint(2L, rcuPerTick, 0, 200L, 0)
      ),
      summary = TrialSummary(
        totalReadCapacityUnits = totalRcu, totalWriteCapacityUnits = 0,
        totalStorageByteTicks = BigInt(0), finalStorageBytes = 0L, totalEstimatedCost = 0,
        gsiTotalWriteCapacityUnits = Map("device-status" -> gsiWcu)
      )
    )

  "IncrementalAggregator" should {

    "produce the same aggregates as the batch MonteCarloAggregation, fed one trial at a time" in {
      val trials   = Vector(trial(0, 2, 10, 5), trial(1, 4, 20, 7), trial(2, 6, 30, 9))
      val gsiNames = MonteCarloAggregation.gsiNames(trials)

      val agg = new IncrementalAggregator(
        MonteCarloAggregation.timeSeriesMetrics(gsiNames),
        MonteCarloAggregation.summaryMetrics(gsiNames)
      )
      trials.foreach(agg.add) // streamed one at a time, each released after folding

      agg.summary    shouldBe MonteCarloAggregation.summary(trials)
      agg.timeSeries shouldBe MonteCarloAggregation.timeSeries(trials)
    }

    "report a write-only GSI column (device-status) even though it is never read" in {
      val trials = Vector(trial(0, 1, 10, gsiWcu = 5), trial(1, 1, 20, gsiWcu = 7))
      val summary = MonteCarloAggregation.summary(trials)
      summary.collectFirst {
        case AggregateSummaryValue("GSI:device-status:TotalWriteCapacityUnits", AggregateStatistic.Mean, v) => v
      } shouldBe Some(BigDecimal(6)) // mean of {5, 7}
      summary.collectFirst {
        case AggregateSummaryValue("GSI:device-status:TotalReadCapacityUnits", AggregateStatistic.Mean, v) => v
      } shouldBe Some(BigDecimal(0)) // present, and zero (never read)
    }

    "divide each metric by the trial count (mean and population stddev)" in {
      val agg = new IncrementalAggregator(Vector.empty, MonteCarloAggregation.summaryMetrics(Vector.empty))
      Vector(trial(0, 1, 10), trial(1, 1, 20), trial(2, 1, 30)).foreach(agg.add)
      val s = agg.summary
      s.collectFirst { case AggregateSummaryValue("TotalReadCapacityUnits", AggregateStatistic.Mean, v)   => v } shouldBe Some(BigDecimal(20))
      s.collectFirst { case AggregateSummaryValue("TotalReadCapacityUnits", AggregateStatistic.StdDev, v) => v.toDouble }
        .map(_ shouldBe (8.165 +- 0.01))
    }
  }
