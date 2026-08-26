package stochastacy.aws.examples.demo

/** One table's across-trial aggregates within a multi-table ensemble. The metric names are **un-prefixed**
 *  base names (`TotalWriteCapacityUnits`, …); the `Table:<name>:` prefix is applied only at JSONL export. */
final case class TableAggregate(
  tableName:           String,
  aggregateTimeSeries: Vector[AggregateTimeSeriesPoint],
  aggregateSummary:    Vector[AggregateSummaryValue]
)

/** The result of a multi-table Monte Carlo ensemble collected in memory: the per-table aggregates plus the
 *  per-trial results. Used by the collecting `run` (tests/gates at bounded sizes); the streaming
 *  `runToFile` returns a [[MultiTableRunReport]] instead, holding no per-trial data. */
final case class MultiTableMonteCarloResult(
  scenarioId: String,
  trialCount: Int,
  perTable:   Vector[TableAggregate],
  trials:     Vector[MultiTableTrialResult]
)

/** The result of a **streaming** multi-table run written straight to JSONL: the per-table aggregates
 *  (bounded — `O(tables × ticks × metrics)`) plus how many records were written. Carries no per-trial data. */
final case class MultiTableRunReport(
  scenarioId:     String,
  trialCount:     Int,
  perTable:       Vector[TableAggregate],
  recordsWritten: Long
)
