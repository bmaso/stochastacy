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

enum WindowSizeSeconds(val seconds: Int):
  case OneMinute extends WindowSizeSeconds(60)
  case FiveMinutes extends WindowSizeSeconds(300)

object WindowSizeSeconds:
  val phase1Values: Vector[WindowSizeSeconds] = Vector(OneMinute, FiveMinutes)

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

final case class WindowedTimeSeriesPoint(
                                          windowSizeSeconds: Int,
                                          windowStartTick: Long,
                                          metric: DemoMetric,
                                          value: BigDecimal
                                        ):
  require(windowSizeSeconds > 0, "windowSizeSeconds must be positive")
  require(windowStartTick >= 1L, "windowStartTick must be at least 1")

final case class TrialSummaryValue(
                                    metric: DemoMetric,
                                    value: BigDecimal
                                  )

final case class AggregatedWindowedTimeSeriesPoint(
                                                    windowSizeSeconds: Int,
                                                    windowStartTick: Long,
                                                    metric: DemoMetric,
                                                    statistic: AggregateStatistic,
                                                    value: BigDecimal
                                                  ):
  require(windowSizeSeconds > 0, "windowSizeSeconds must be positive")
  require(windowStartTick >= 1L, "windowStartTick must be at least 1")

final case class TrialResult(
                              scenarioId: String,
                              trialId: Int,
                              timeSeries: Vector[SimulationTimeSeriesPoint],
                              summary: Vector[TrialSummaryValue]
                            ):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(trialId >= 0, "trialId must be non-negative")
