package stochastacy.demo

enum DemoMetric:
  case ReadCapacityUnits
  case WriteCapacityUnits
  case GsiReadCapacityUnits(indexName: String)
  case GsiWriteCapacityUnits(indexName: String)
  case StorageBytes
  case CumulativeEstimatedCost
  case TotalReadCapacityUnits
  case TotalWriteCapacityUnits
  case TotalGsiReadCapacityUnits(indexName: String)
  case TotalGsiWriteCapacityUnits(indexName: String)
  case TotalStorageByteTicks
  case FinalStorageBytes
  case TotalEstimatedCost

  def exportName: String =
    this match
      case DemoMetric.ReadCapacityUnits => "ReadCapacityUnits"
      case DemoMetric.WriteCapacityUnits => "WriteCapacityUnits"
      case DemoMetric.GsiReadCapacityUnits(indexName) => s"GSI:$indexName:ReadCapacityUnits"
      case DemoMetric.GsiWriteCapacityUnits(indexName) => s"GSI:$indexName:WriteCapacityUnits"
      case DemoMetric.StorageBytes => "StorageBytes"
      case DemoMetric.CumulativeEstimatedCost => "CumulativeEstimatedCost"
      case DemoMetric.TotalReadCapacityUnits => "TotalReadCapacityUnits"
      case DemoMetric.TotalWriteCapacityUnits => "TotalWriteCapacityUnits"
      case DemoMetric.TotalGsiReadCapacityUnits(indexName) => s"GSI:$indexName:TotalReadCapacityUnits"
      case DemoMetric.TotalGsiWriteCapacityUnits(indexName) => s"GSI:$indexName:TotalWriteCapacityUnits"
      case DemoMetric.TotalStorageByteTicks => "TotalStorageByteTicks"
      case DemoMetric.FinalStorageBytes => "FinalStorageBytes"
      case DemoMetric.TotalEstimatedCost => "TotalEstimatedCost"

  def sortKey: (Int, String) =
    this match
      case DemoMetric.ReadCapacityUnits => (0, "")
      case DemoMetric.WriteCapacityUnits => (1, "")
      case DemoMetric.GsiReadCapacityUnits(indexName) => (2, indexName)
      case DemoMetric.GsiWriteCapacityUnits(indexName) => (3, indexName)
      case DemoMetric.StorageBytes => (4, "")
      case DemoMetric.CumulativeEstimatedCost => (5, "")
      case DemoMetric.TotalReadCapacityUnits => (6, "")
      case DemoMetric.TotalWriteCapacityUnits => (7, "")
      case DemoMetric.TotalGsiReadCapacityUnits(indexName) => (8, indexName)
      case DemoMetric.TotalGsiWriteCapacityUnits(indexName) => (9, indexName)
      case DemoMetric.TotalStorageByteTicks => (10, "")
      case DemoMetric.FinalStorageBytes => (11, "")
      case DemoMetric.TotalEstimatedCost => (12, "")

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
