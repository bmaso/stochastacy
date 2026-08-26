package stochastacy.aws.examples.demo

/** One tick's slice of a trial: the capacity consumed that tick (overall, plus a per-GSI breakout), the
 *  storage held at tick close, and the running estimated cost through that tick. */
final case class TrialTimeSeriesPoint(
  tick:                    Long,
  readCapacityUnits:       BigDecimal,
  writeCapacityUnits:      BigDecimal,
  storageBytes:            Long,
  cumulativeEstimatedCost: BigDecimal,
  gsiReadCapacityUnits:    Map[String, BigDecimal] = Map.empty,
  gsiWriteCapacityUnits:   Map[String, BigDecimal] = Map.empty
)

/** A trial's roll-up totals: capacity consumed (overall — base + all indexes — plus a per-GSI breakout),
 *  storage integrated over ticks (byte-ticks), the ending table size, and the total estimated cost.
 *  Storage figures count the table's (and its indexes') initial contents. */
final case class TrialSummary(
  totalReadCapacityUnits:     BigDecimal,
  totalWriteCapacityUnits:    BigDecimal,
  totalStorageByteTicks:      BigInt,
  finalStorageBytes:          Long,
  totalEstimatedCost:         BigDecimal,
  gsiTotalReadCapacityUnits:  Map[String, BigDecimal] = Map.empty,
  gsiTotalWriteCapacityUnits: Map[String, BigDecimal] = Map.empty,
  // Reserved provisioned capacity integrated over the ticks it was in force (0 under on-demand). Priced as
  // capacity-hours; carried here for reporting/reconciliation (not yet a JSONL metric).
  totalProvisionedReadCapacityUnitTicks:  BigInt = 0,
  totalProvisionedWriteCapacityUnitTicks: BigInt = 0
)

/** The result of one Order-Tracking trial: the per-tick series and the summary totals. */
final case class TrialResult(
  trialId:    Int,
  timeSeries: Vector[TrialTimeSeriesPoint],
  summary:    TrialSummary
)
