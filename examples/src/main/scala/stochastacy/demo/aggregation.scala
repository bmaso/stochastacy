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
      .sortBy { case (tick, metric) => (tick, metric.sortKey) }
    val summaryKeys = summaryByTrial.iterator.flatMap(_.keySet).toSet.toVector
      .sortBy(_.sortKey)

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

/** Welford online algorithm accumulator: computes mean and variance in a single pass. */
private[demo] final case class WelfordAcc(
  n: Int = 0,
  mean: BigDecimal = BigDecimal(0),
  m2: BigDecimal = BigDecimal(0)
):
  def update(x: BigDecimal): WelfordAcc =
    val n1 = n + 1
    val delta = x - mean
    val newMean = mean + delta / BigDecimal(n1)
    WelfordAcc(n1, newMean, m2 + delta * (x - newMean))

  def toStatisticPairs: Vector[(AggregateStatistic, BigDecimal)] =
    val variance = if n < 2 then BigDecimal(0) else m2 / BigDecimal(n)
    Vector(
      AggregateStatistic.Mean -> mean,
      AggregateStatistic.StdDev -> BigDecimal.decimal(math.sqrt(variance.toDouble))
    )

/**
 * Incremental Monte Carlo aggregator: folds one trial at a time using Welford accumulators so that
 * completed TrialResult objects can be GC'd immediately — peak aggregate state is O(distinct ticks ×
 * metrics), not O(trial count × ticks × metrics).
 */
final case class IncrementalMonteCarloAgg(
  scenarioId: String,
  trialCount: Int = 0,
  timeSeriesAcc: Map[(Long, DemoMetric), WelfordAcc] = Map.empty,
  summaryAcc: Map[DemoMetric, WelfordAcc] = Map.empty
):
  def addTrial(trial: TrialResult): IncrementalMonteCarloAgg =
    require(trial.scenarioId == scenarioId)
    val newTsAcc = trial.timeSeries.foldLeft(timeSeriesAcc) { (acc, point) =>
      val key = (point.tick, point.metric)
      acc.updated(key, acc.getOrElse(key, WelfordAcc()).update(point.value))
    }
    val newSumAcc = trial.summary.foldLeft(summaryAcc) { (acc, sv) =>
      acc.updated(sv.metric, acc.getOrElse(sv.metric, WelfordAcc()).update(sv.value))
    }
    copy(trialCount = trialCount + 1, timeSeriesAcc = newTsAcc, summaryAcc = newSumAcc)

  def toMonteCarloResult: MonteCarloResult =
    val tsKeys = timeSeriesAcc.keySet.toVector.sortBy { case (tick, metric) => (tick, metric.sortKey) }
    val sumKeys = summaryAcc.keySet.toVector.sortBy(_.sortKey)
    MonteCarloResult(
      scenarioId = scenarioId,
      trialCount = trialCount,
      timeSeries = tsKeys.flatMap { case (tick, metric) =>
        timeSeriesAcc((tick, metric)).toStatisticPairs.map { case (stat, value) =>
          AggregatedTimeSeriesPoint(tick, metric, stat, value)
        }
      },
      summary = sumKeys.flatMap { metric =>
        summaryAcc(metric).toStatisticPairs.map { case (stat, value) =>
          AggregatedSummaryValue(metric, stat, value)
        }
      }
    )
