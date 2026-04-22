package stochastacy.demo

object TimeWindowRollups:

  def rollupTrialTimeSeries(
                             points: Vector[SimulationTimeSeriesPoint],
                             windowSize: WindowSizeSeconds
                           ): Vector[WindowedTimeSeriesPoint] =
    points
      .groupBy(point => (windowStartTick(point.tick, windowSize), point.metric))
      .toVector
      .sortBy { case ((windowStart, metric), _) => (windowStart, metric.sortKey) }
      .map { case ((windowStart, metric), groupedPoints) =>
        WindowedTimeSeriesPoint(
          windowSizeSeconds = windowSize.seconds,
          windowStartTick = windowStart,
          metric = metric,
          value = rollupMetric(metric, groupedPoints.sortBy(_.tick))
        )
      }

  def aggregateWindowedTrials(
                               trials: Vector[TrialResult],
                               windowSize: WindowSizeSeconds
                             ): Vector[AggregatedWindowedTimeSeriesPoint] =
    require(trials.nonEmpty, "trials must be non-empty")

    val byTrial =
      trials.map { trial =>
        rollupTrialTimeSeries(trial.timeSeries, windowSize)
          .groupMapReduce(point => (point.windowStartTick, point.metric))(_.value)(_ + _)
      }

    val keys =
      byTrial.iterator.flatMap(_.keySet).toSet.toVector
        .sortBy { case (windowStart, metric) => (windowStart, metric.sortKey) }

    keys.flatMap { case (windowStart, metric) =>
      val values = byTrial.map(_.getOrElse((windowStart, metric), BigDecimal(0)))
      statisticPairs(values).map { case (statistic, value) =>
        AggregatedWindowedTimeSeriesPoint(
          windowSizeSeconds = windowSize.seconds,
          windowStartTick = windowStart,
          metric = metric,
          statistic = statistic,
          value = value
        )
      }
    }

  def windowStartTick(
                       tick: Long,
                       windowSize: WindowSizeSeconds
                     ): Long =
    require(tick >= 1L, "tick must be at least 1 for windowed rollups")
    ((tick - 1L) / windowSize.seconds) * windowSize.seconds + 1L

  private def rollupMetric(
                            metric: DemoMetric,
                            points: Vector[SimulationTimeSeriesPoint]
                          ): BigDecimal =
    metric match
      case DemoMetric.ReadCapacityUnits | DemoMetric.WriteCapacityUnits |
          DemoMetric.GsiReadCapacityUnits(_) | DemoMetric.GsiWriteCapacityUnits(_) =>
        points.map(_.value).sum
      case DemoMetric.StorageBytes =>
        points.map(_.value).sum / BigDecimal(points.size)
      case DemoMetric.CumulativeEstimatedCost =>
        points.maxBy(_.tick).value
      case unsupported =>
        throw new IllegalArgumentException(s"windowed time-series rollups are not supported for metric: $unsupported")

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
