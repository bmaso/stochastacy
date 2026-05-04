package stochastacy.demo

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class DemoModelSpec extends AnyWordSpec with should.Matchers:

  "TrialResult" should {
    "represent mixed time-series and summary demo metrics" in {
      val result = TrialResult(
        scenarioId = "orders",
        trialId = 2,
        timeSeries = Vector(
          SimulationTimeSeriesPoint(
            tick = 1L,
            metric = DemoMetric.ReadCapacityUnits,
            value = BigDecimal(2.0)
          ),
          SimulationTimeSeriesPoint(
            tick = 1L,
            metric = DemoMetric.CumulativeEstimatedCost,
            value = BigDecimal(3.5)
          )
        ),
        summary = Vector(
          TrialSummaryValue(
            metric = DemoMetric.TotalReadCapacityUnits,
            value = BigDecimal(7.0)
          ),
          TrialSummaryValue(
            metric = DemoMetric.TotalEstimatedCost,
            value = BigDecimal(11.25)
          )
        )
      )

      result.scenarioId shouldBe "orders"
      result.trialId shouldBe 2
      result.timeSeries.map(_.metric) shouldBe Vector(
        DemoMetric.ReadCapacityUnits,
        DemoMetric.CumulativeEstimatedCost
      )
      result.summary.map(_.metric) shouldBe Vector(
        DemoMetric.TotalReadCapacityUnits,
        DemoMetric.TotalEstimatedCost
      )
    }
  }
