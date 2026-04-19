package stochastacy.demo

import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

class DemoExportSpec extends AnyWordSpec with should.Matchers:

  private given Formats = DefaultFormats

  "DemoExportRecord" should {
    "derive trial-level and aggregate-level records with stable discriminator fields" in {
      val trial = TrialResult(
        scenarioId = "orders",
        trialId = 3,
        timeSeries = Vector(
          SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(2))
        ),
        summary = Vector(
          TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(9))
        )
      )
      val aggregate = MonteCarloResult(
        scenarioId = "orders",
        trialCount = 2,
        timeSeries = Vector(
          AggregatedTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, AggregateStatistic.Mean, BigDecimal(3))
        ),
        summary = Vector(
          AggregatedSummaryValue(DemoMetric.TotalEstimatedCost, AggregateStatistic.StdDev, BigDecimal(2))
        )
      )

      DemoExportRecord.fromTrialResult(trial) shouldBe Vector(
        DemoExportRecord.TrialTimeSeriesRecord(
          scenarioId = "orders",
          trialId = 3,
          tick = 1L,
          metric = "ReadCapacityUnits",
          value = BigDecimal(2)
        ),
        DemoExportRecord.TrialSummaryRecord(
          scenarioId = "orders",
          trialId = 3,
          metric = "TotalEstimatedCost",
          value = BigDecimal(9)
        )
      )

      DemoExportRecord.fromMonteCarloResult(aggregate) shouldBe Vector(
        DemoExportRecord.AggregateTimeSeriesRecord(
          scenarioId = "orders",
          trialCount = 2,
          tick = 1L,
          metric = "ReadCapacityUnits",
          statistic = "mean",
          value = BigDecimal(3)
        ),
        DemoExportRecord.AggregateSummaryRecord(
          scenarioId = "orders",
          trialCount = 2,
          metric = "TotalEstimatedCost",
          statistic = "stddev",
          value = BigDecimal(2)
        )
      )
    }
  }

  "DemoReportBuilder" should {
    "produce one mixed record stream in the expected family ordering" in {
      val trials = Vector(
        TrialResult(
          scenarioId = "orders",
          trialId = 0,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(1))
          ),
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(2))
          )
        ),
        TrialResult(
          scenarioId = "orders",
          trialId = 1,
          timeSeries = Vector(
            SimulationTimeSeriesPoint(1L, DemoMetric.ReadCapacityUnits, BigDecimal(3))
          ),
          summary = Vector(
            TrialSummaryValue(DemoMetric.TotalEstimatedCost, BigDecimal(4))
          )
        )
      )

      val bundle = DemoReportBuilder.build(trials)

      bundle.records.map(_.recordType) shouldBe Vector(
        "trial-time-series",
        "trial-time-series",
        "aggregate-time-series",
        "aggregate-time-series",
        "trial-summary",
        "trial-summary",
        "aggregate-summary",
        "aggregate-summary"
      )
    }
  }

  "DemoJsonlExporter" should {
    "render one compact JSON object per line while preserving order" in {
      val records = Vector[DemoExportRecord](
        DemoExportRecord.TrialTimeSeriesRecord(
          scenarioId = "orders",
          trialId = 0,
          tick = 1L,
          metric = "ReadCapacityUnits",
          value = BigDecimal(2)
        ),
        DemoExportRecord.AggregateSummaryRecord(
          scenarioId = "orders",
          trialCount = 2,
          metric = "TotalEstimatedCost",
          statistic = "mean",
          value = BigDecimal(7)
        )
      )

      val rendered = DemoJsonlExporter.render(records)
      val lines = rendered.linesIterator.toVector.filter(_.nonEmpty)

      lines should have size 2

      val first = parse(lines.head)
      val second = parse(lines(1))

      (first \ "recordType").extract[String] shouldBe "trial-time-series"
      (first \ "value").extract[BigDecimal] shouldBe BigDecimal(2)
      (second \ "recordType").extract[String] shouldBe "aggregate-summary"
      (second \ "value").extract[BigDecimal] shouldBe BigDecimal(7)
    }
  }
