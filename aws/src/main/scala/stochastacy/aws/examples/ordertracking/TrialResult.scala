package stochastacy.aws.examples.ordertracking

/** One tick's slice of a trial: the capacity consumed that tick, the storage held at tick close, and the
 *  running estimated cost through that tick. */
final case class TrialTimeSeriesPoint(
  tick:                    Long,
  readCapacityUnits:       BigDecimal,
  writeCapacityUnits:      BigDecimal,
  storageBytes:            Long,
  cumulativeEstimatedCost: BigDecimal
)

/** A trial's roll-up totals: capacity consumed, storage integrated over ticks (byte-ticks), the ending
 *  table size, and the total estimated cost. Storage figures count the table's initial contents. */
final case class TrialSummary(
  totalReadCapacityUnits:  BigDecimal,
  totalWriteCapacityUnits: BigDecimal,
  totalStorageByteTicks:   BigInt,
  finalStorageBytes:       Long,
  totalEstimatedCost:      BigDecimal
)

/** The result of one Order-Tracking trial: the per-tick series and the summary totals. */
final case class OrderTrackingTrialResult(
  trialId:    Int,
  timeSeries: Vector[TrialTimeSeriesPoint],
  summary:    TrialSummary
)
