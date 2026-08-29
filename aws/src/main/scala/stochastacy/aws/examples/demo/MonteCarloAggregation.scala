package stochastacy.aws.examples.demo

/**
 * Across-trial aggregation for the Order-Tracking ensemble. For each `(tick, metric)` time-series point
 * and each summary metric, it reduces the ensemble to a mean and a (population) standard deviation —
 * matching the legacy demo's `{mean, stddev}` statistic set. The metric name lists here are the single
 * source of truth for both the per-trial and the aggregate JSONL records.
 */
object MonteCarloAggregation:

  private val baseTimeSeriesMetrics: Vector[(String, TrialTimeSeriesPoint => BigDecimal)] = Vector(
    ("ReadCapacityUnits",       (p: TrialTimeSeriesPoint) => p.readCapacityUnits),
    ("WriteCapacityUnits",      (p: TrialTimeSeriesPoint) => p.writeCapacityUnits),
    ("StorageBytes",            (p: TrialTimeSeriesPoint) => BigDecimal(p.storageBytes)),
    ("CumulativeEstimatedCost", (p: TrialTimeSeriesPoint) => p.cumulativeEstimatedCost)
  )

  private val baseSummaryMetrics: Vector[(String, TrialSummary => BigDecimal)] = Vector(
    ("TotalReadCapacityUnits",  (s: TrialSummary) => s.totalReadCapacityUnits),
    ("TotalWriteCapacityUnits", (s: TrialSummary) => s.totalWriteCapacityUnits),
    ("TotalStorageByteTicks",   (s: TrialSummary) => BigDecimal(s.totalStorageByteTicks)),
    ("FinalStorageBytes",       (s: TrialSummary) => BigDecimal(s.finalStorageBytes)),
    ("TotalEstimatedCost",      (s: TrialSummary) => s.totalEstimatedCost)
  )

  /** Provisioned-billing summary metrics — reserved capacity-ticks and the throttle count — appended only
   *  when the ensemble actually used provisioned billing (so purely on-demand output is unchanged). */
  private val provisionedSummaryMetrics: Vector[(String, TrialSummary => BigDecimal)] = Vector(
    ("TotalProvisionedReadCapacityUnitTicks",  (s: TrialSummary) => BigDecimal(s.totalProvisionedReadCapacityUnitTicks)),
    ("TotalProvisionedWriteCapacityUnitTicks", (s: TrialSummary) => BigDecimal(s.totalProvisionedWriteCapacityUnitTicks)),
    ("TotalThrottledRequests",                 (s: TrialSummary) => BigDecimal(s.totalThrottledRequests))
  )

  /** The GSI names present in the ensemble (sorted), for the per-GSI metric breakout — the union of the
   *  read and write breakouts, so a GSI that is only *maintained* (WCU, never read) is still reported. */
  def gsiNames(trials: Vector[TrialResult]): Vector[String] =
    trials.flatMap(t => t.summary.gsiTotalReadCapacityUnits.keys ++ t.summary.gsiTotalWriteCapacityUnits.keys).distinct.sorted

  /** Whether the ensemble used provisioned billing (any reserved capacity-ticks or throttling occurred). */
  def hasProvisioning(trials: Vector[TrialResult]): Boolean =
    trials.exists(t => t.summary.totalProvisionedReadCapacityUnitTicks > 0 || t.summary.totalThrottledRequests > 0)

  /** The per-tick metrics — base plus a per-GSI RCU/WCU pair — as the single source of truth for both the
   *  per-trial and the aggregate records (metric names match the legacy `GSI:<name>:…`). */
  def timeSeriesMetrics(gsiNames: Vector[String]): Vector[(String, TrialTimeSeriesPoint => BigDecimal)] =
    baseTimeSeriesMetrics ++ gsiNames.flatMap { n =>
      Vector(
        (s"GSI:$n:ReadCapacityUnits",  (p: TrialTimeSeriesPoint) => p.gsiReadCapacityUnits.getOrElse(n, BigDecimal(0))),
        (s"GSI:$n:WriteCapacityUnits", (p: TrialTimeSeriesPoint) => p.gsiWriteCapacityUnits.getOrElse(n, BigDecimal(0)))
      )
    }

  def summaryMetrics(gsiNames: Vector[String], provisioned: Boolean = false): Vector[(String, TrialSummary => BigDecimal)] =
    baseSummaryMetrics ++ gsiNames.flatMap { n =>
      Vector(
        (s"GSI:$n:TotalReadCapacityUnits",  (s: TrialSummary) => s.gsiTotalReadCapacityUnits.getOrElse(n, BigDecimal(0))),
        (s"GSI:$n:TotalWriteCapacityUnits", (s: TrialSummary) => s.gsiTotalWriteCapacityUnits.getOrElse(n, BigDecimal(0)))
      )
    } ++ (if provisioned then provisionedSummaryMetrics else Vector.empty)

  /** Batch across-trial aggregation — a thin wrapper over the streaming [[IncrementalAggregator]] (folds
   *  the given trials, then reads its result), so batch and streaming aggregation are identical by
   *  construction. `gsiNames` is derived from the trials (as before), so write-only GSIs are included. */
  private def aggregatorFor(trials: Vector[TrialResult]): IncrementalAggregator =
    val names = gsiNames(trials)
    val agg   = new IncrementalAggregator(timeSeriesMetrics(names), summaryMetrics(names, hasProvisioning(trials)))
    trials.foreach(agg.add)
    agg

  def timeSeries(trials: Vector[TrialResult]): Vector[AggregateTimeSeriesPoint] =
    if trials.isEmpty then Vector.empty else aggregatorFor(trials).timeSeries

  def summary(trials: Vector[TrialResult]): Vector[AggregateSummaryValue] =
    aggregatorFor(trials).summary // empty trials → base metrics at zero (matches the legacy convention)
