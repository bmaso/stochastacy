package stochastacy.demo

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class TimeWindowRollupsSpec extends AnyWordSpec with should.Matchers:

  "TimeWindowRollups" should {
    "roll up read and write units by summing within a window" in {
      val points = Vector(
        SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(1)),
        SimulationTimeSeriesPoint(2L, DemoMetric.ReadCapacityUnits, BigDecimal(2)),
        SimulationTimeSeriesPoint(61L, DemoMetric.ReadCapacityUnits, BigDecimal(4)),
        SimulationTimeSeriesPoint(10L, DemoMetric.WriteCapacityUnits, BigDecimal(3)),
        SimulationTimeSeriesPoint(20L, DemoMetric.WriteCapacityUnits, BigDecimal(5))
      )

      TimeWindowRollups.rollupTrialTimeSeries(points, WindowSizeSeconds.OneMinute) should contain allElementsOf Vector(
        WindowedTimeSeriesPoint(60, 1L, DemoMetric.ReadCapacityUnits, BigDecimal(3)),
        WindowedTimeSeriesPoint(60, 61L, DemoMetric.ReadCapacityUnits, BigDecimal(4)),
        WindowedTimeSeriesPoint(60, 1L, DemoMetric.WriteCapacityUnits, BigDecimal(8))
      )
    }

    "roll up storage bytes by averaging within a window" in {
      val points = Vector(
        SimulationTimeSeriesPoint(1L, DemoMetric.StorageBytes, BigDecimal(100)),
        SimulationTimeSeriesPoint(2L, DemoMetric.StorageBytes, BigDecimal(200)),
        SimulationTimeSeriesPoint(3L, DemoMetric.StorageBytes, BigDecimal(300))
      )

      TimeWindowRollups.rollupTrialTimeSeries(points, WindowSizeSeconds.OneMinute) should contain(
        WindowedTimeSeriesPoint(60, 1L, DemoMetric.StorageBytes, BigDecimal(200))
      )
    }

    "roll up cumulative cost by taking the last value in a window" in {
      val points = Vector(
        SimulationTimeSeriesPoint(1L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.1")),
        SimulationTimeSeriesPoint(2L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.2")),
        SimulationTimeSeriesPoint(3L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.4")),
        SimulationTimeSeriesPoint(61L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.5"))
      )

      TimeWindowRollups.rollupTrialTimeSeries(points, WindowSizeSeconds.OneMinute) should contain allElementsOf Vector(
        WindowedTimeSeriesPoint(60, 1L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.4")),
        WindowedTimeSeriesPoint(60, 61L, DemoMetric.CumulativeEstimatedCost, BigDecimal("0.5"))
      )
    }

    "align window start ticks to 1, 61, 121 for one-minute windows" in {
      TimeWindowRollups.windowStartTick(1L, WindowSizeSeconds.OneMinute) shouldBe 1L
      TimeWindowRollups.windowStartTick(60L, WindowSizeSeconds.OneMinute) shouldBe 1L
      TimeWindowRollups.windowStartTick(61L, WindowSizeSeconds.OneMinute) shouldBe 61L
      TimeWindowRollups.windowStartTick(121L, WindowSizeSeconds.OneMinute) shouldBe 121L
    }

    "compute aggregate mean and stddev from rolled-up per-trial windows" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(1)),
            SimulationTimeSeriesPoint(2L, DemoMetric.ReadCapacityUnits, BigDecimal(2))
          ),
          summary = Vector.empty
        ),
        TrialResult(
          scenarioId = "orders",
          trialId = 1,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(5))
          ),
          summary = Vector.empty
        )
      )

      TimeWindowRollups.aggregateWindowedTrials(trials, WindowSizeSeconds.OneMinute) should contain allElementsOf Vector(
        AggregatedWindowedTimeSeriesPoint(60, 1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean, BigDecimal(4)),
        AggregatedWindowedTimeSeriesPoint(60, 1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.StdDev, BigDecimal(1))
      )
    }

    "zero-fill missing rolled-up windows across trials" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(5))
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

      TimeWindowRollups.aggregateWindowedTrials(trials, WindowSizeSeconds.OneMinute) should contain allElementsOf Vector(
        AggregatedWindowedTimeSeriesPoint(60, 1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean, BigDecimal("2.5")),
        AggregatedWindowedTimeSeriesPoint(60, 1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.StdDev, BigDecimal("2.5"))
      )
    }
  }
