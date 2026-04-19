package stochastacy.demo

enum AggregateStatistic:
  case Mean
  case StdDev

  def exportName: String =
    this match
      case Mean => "mean"
      case StdDev => "stddev"

final case class AggregatedTimeSeriesPoint(
                                            tick: Long,
                                            metric: DemoMetric,
                                            statistic: AggregateStatistic,
                                            value: BigDecimal
                                          ):
  require(tick >= 0L, "tick must be non-negative")

final case class AggregatedSummaryValue(
                                         metric: DemoMetric,
                                         statistic: AggregateStatistic,
                                         value: BigDecimal
                                       )

final case class MonteCarloResult(
                                    scenarioId: String,
                                    trialCount: Int,
                                    timeSeries: Vector[AggregatedTimeSeriesPoint],
                                    summary: Vector[AggregatedSummaryValue]
                                  ):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(trialCount >= 1, "trialCount must be at least 1")

object MonteCarloAggregator:

  def aggregate(trials: Vector[TrialResult]): MonteCarloResult =
    require(trials.nonEmpty, "trials must be non-empty")

    val scenarioId = trials.head.scenarioId
    require(
      trials.forall(_.scenarioId == scenarioId),
      "all trials must share the same scenarioId"
    )

    val timeSeriesByTrial = trials.map { trial =>
      trial.timeSeries
        .groupMapReduce(point => (point.tick, point.metric))(_.value)(_ + _)
    }
    val summaryByTrial = trials.map { trial =>
      trial.summary
        .groupMapReduce(_.metric)(_.value)(_ + _)
    }

    val timeSeriesKeys = timeSeriesByTrial.iterator.flatMap(_.keySet).toSet.toVector
      .sortBy { case (tick, metric) => (tick, metric.ordinal) }
    val summaryKeys = summaryByTrial.iterator.flatMap(_.keySet).toSet.toVector
      .sortBy(_.ordinal)

    MonteCarloResult(
      scenarioId = scenarioId,
      trialCount = trials.size,
      timeSeries = timeSeriesKeys.flatMap { case (tick, metric) =>
        val values = timeSeriesByTrial.map(_.getOrElse((tick, metric), BigDecimal(0)))
        statisticPairs(values).map { case (statistic, value) =>
          AggregatedTimeSeriesPoint(
            tick = tick,
            metric = metric,
            statistic = statistic,
            value = value
          )
        }
      },
      summary = summaryKeys.flatMap { metric =>
        val values = summaryByTrial.map(_.getOrElse(metric, BigDecimal(0)))
        statisticPairs(values).map { case (statistic, value) =>
          AggregatedSummaryValue(
            metric = metric,
            statistic = statistic,
            value = value
          )
        }
      }
    )

  private def statisticPairs(
                              values: Vector[BigDecimal]
                            ): Vector[(AggregateStatistic, BigDecimal)] =
    val mean = values.sum / BigDecimal(values.size)
    val variance =
      values.map { value =>
        val deviation = value - mean
        deviation * deviation
      }.sum / BigDecimal(values.size)
    val stddev = BigDecimal.decimal(math.sqrt(variance.toDouble))

    Vector(
      AggregateStatistic.Mean -> mean,
      AggregateStatistic.StdDev -> stddev
    )
