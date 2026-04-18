package stochastacy.demo

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class MonteCarloAggregatorSpec extends AnyWordSpec with should.Matchers:

  "MonteCarloAggregator" should {
    "compute mean and variance for time-series and summary values" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(2)),
            SimulationTimeSeriesPoint(1L, DemoMetric.WriteCapacityUnits, BigDecimal(4))
          ),
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(10))
          )
        ),
        TrialResult(
          scenarioId = "orders",
          trialId = 1,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(4)),
            SimulationTimeSeriesPoint(1L, DemoMetric.WriteCapacityUnits, BigDecimal(8))
          ),
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(14))
          )
        )
      )

      val result = MonteCarloAggregator.aggregate(trials)

      result.trialCount shouldBe 2
      result.timeSeries should contain allElementsOf Vector(
        AggregatedTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean, BigDecimal(3)),
        AggregatedTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Variance, BigDecimal(1)),
        AggregatedTimeSeriesPoint(1L, DemoMetric.WriteCapacityUnits, AggregateStatistic.Mean, BigDecimal(6)),
        AggregatedTimeSeriesPoint(1L, DemoMetric.WriteCapacityUnits, AggregateStatistic.Variance, BigDecimal(4))
      )
      result.summary should contain allElementsOf Vector(
        AggregatedSummaryValue(DemoMetric.TotalEstimatedCost, AggregateStatistic.Mean, BigDecimal(12)),
        AggregatedSummaryValue(DemoMetric.TotalEstimatedCost, AggregateStatistic.Variance, BigDecimal(4))
      )
    }

    "zero-fill sparse time-series points before aggregation" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(2L, DemoMetric.ReadCapacityUnits, BigDecimal(5))
          ),
          summary = Vector.empty
        ),
        TrialResult(
          scenarioId = "orders",
          trialId = 1,
          timeSeries = Vector.empty,
          summary = Vector.empty
        )
      )

      val result = MonteCarloAggregator.aggregate(trials)

      result.timeSeries should contain allElementsOf Vector(
        AggregatedTimeSeriesPoint(2L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean, BigDecimal("2.5")),
        AggregatedTimeSeriesPoint(2L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Variance, BigDecimal("6.25"))
      )
    }

    "order output deterministically" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(2L, DemoMetric.WriteCapacityUnits, BigDecimal(1)),
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(1))
          ),
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(1)),
            TrialSummaryValue(DemoMetric.FinalStorageBytes, BigDecimal(1))
          )
        )
      )

      val result = MonteCarloAggregator.aggregate(trials)

      result.timeSeries.map(point => (point.tick, point.metric, point.statistic)) shouldBe Vector(
        (1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean),
        (1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Variance),
        (2L, DemoMetric.WriteCapacityUnits, AggregateStatistic.Mean),
        (2L, DemoMetric.WriteCapacityUnits, AggregateStatistic.Variance)
      )
      result.summary.map(value => (value.metric, value.statistic)) shouldBe Vector(
        (DemoMetric.FinalStorageBytes, AggregateStatistic.Mean),
        (DemoMetric.FinalStorageBytes, AggregateStatistic.Variance),
        (DemoMetric.TotalEstimatedCost, AggregateStatistic.Mean),
        (DemoMetric.TotalEstimatedCost, AggregateStatistic.Variance)
      )
    }

    "reject mixed scenario ids" in {
      val thrown = the[IllegalArgumentException] thrownBy {
        MonteCarloAggregator.aggregate(
          Vector(
            TrialResult("orders-a", 0, Vector.empty, Vector.empty),
            TrialResult("orders-b", 1, Vector.empty, Vector.empty)
          )
        )
      }

      thrown.getMessage should include("scenarioId")
    }
  }
