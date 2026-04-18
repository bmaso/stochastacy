package stochastacy.demo

enum DemoMetric:
  case ReadCapacityUnits
  case WriteCapacityUnits
  case StorageBytes
  case CumulativeEstimatedCost
  case TotalReadCapacityUnits
  case TotalWriteCapacityUnits
  case TotalStorageByteTicks
  case FinalStorageBytes
  case TotalEstimatedCost

final case class TrialRunConfig(
                                 trialId: Int,
                                 seed: Long
                               ):
  require(trialId >= 0, "trialId must be non-negative")

final case class SimulationTimeSeriesPoint(
                                            tick: Long,
                                            metric: DemoMetric,
                                            value: BigDecimal
                                          ):
  require(tick >= 0L, "tick must be non-negative")

final case class TrialSummaryValue(
                                    metric: DemoMetric,
                                    value: BigDecimal
                                  )

final case class TrialResult(
                              scenarioId: String,
                              trialId: Int,
                              timeSeries: Vector[SimulationTimeSeriesPoint],
                              summary: Vector[TrialSummaryValue]
                            ):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(trialId >= 0, "trialId must be non-negative")
