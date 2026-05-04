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
  // Per-region metrics (multi-region / global table demos)
  case RegionReadCapacityUnits(regionName: String)
  case RegionWriteCapacityUnits(regionName: String)
  case RegionReplicatedWriteCapacityUnits(regionName: String)
  case RegionStorageBytes(regionName: String)
  case RegionCumulativeEstimatedCost(regionName: String)
  case CrossRegionTransferBytes(sourceRegion: String, destinationRegion: String)
  case TotalRegionReadCapacityUnits(regionName: String)
  case TotalRegionWriteCapacityUnits(regionName: String)
  case TotalRegionReplicatedWriteCapacityUnits(regionName: String)
  case TotalRegionStorageByteTicks(regionName: String)
  case TotalRegionFinalStorageBytes(regionName: String)
  case TotalRegionEstimatedCost(regionName: String)
  case TotalCrossRegionTransferBytes
  case TotalCrossRegionTransferCost
  case CumulativeCrossRegionTransferCost
  // Provisioned capacity mode metrics
  case ProvisionedReadCapacityUnits
  case ProvisionedWriteCapacityUnits
  case BillingModeIndicator
  case ThrottleCount
  case AdmittedRequestCount
  case ReturnedItemCount(operation: String)
  case ReplicationLatency(destinationRegion: String)
  case SystemErrorCount
  case LatencyP50(operation: String)
  case LatencyP95(operation: String)
  case LatencyP99(operation: String)

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
      case DemoMetric.RegionReadCapacityUnits(r) => s"Region:$r:ReadCapacityUnits"
      case DemoMetric.RegionWriteCapacityUnits(r) => s"Region:$r:WriteCapacityUnits"
      case DemoMetric.RegionReplicatedWriteCapacityUnits(r) => s"Region:$r:ReplicatedWriteCapacityUnits"
      case DemoMetric.RegionStorageBytes(r) => s"Region:$r:StorageBytes"
      case DemoMetric.RegionCumulativeEstimatedCost(r) => s"Region:$r:CumulativeEstimatedCost"
      case DemoMetric.CrossRegionTransferBytes(src, dst) => s"CrossRegionTransfer:$src:$dst:Bytes"
      case DemoMetric.TotalRegionReadCapacityUnits(r) => s"Region:$r:TotalReadCapacityUnits"
      case DemoMetric.TotalRegionWriteCapacityUnits(r) => s"Region:$r:TotalWriteCapacityUnits"
      case DemoMetric.TotalRegionReplicatedWriteCapacityUnits(r) => s"Region:$r:TotalReplicatedWriteCapacityUnits"
      case DemoMetric.TotalRegionStorageByteTicks(r) => s"Region:$r:TotalStorageByteTicks"
      case DemoMetric.TotalRegionFinalStorageBytes(r) => s"Region:$r:FinalStorageBytes"
      case DemoMetric.TotalRegionEstimatedCost(r) => s"Region:$r:TotalEstimatedCost"
      case DemoMetric.TotalCrossRegionTransferBytes => "TotalCrossRegionTransferBytes"
      case DemoMetric.TotalCrossRegionTransferCost => "TotalCrossRegionTransferCost"
      case DemoMetric.CumulativeCrossRegionTransferCost => "CumulativeCrossRegionTransferCost"
      case DemoMetric.ProvisionedReadCapacityUnits => "ProvisionedReadCapacityUnits"
      case DemoMetric.ProvisionedWriteCapacityUnits => "ProvisionedWriteCapacityUnits"
      case DemoMetric.BillingModeIndicator => "BillingModeIndicator"
      case DemoMetric.ThrottleCount => "ThrottleCount"
      case DemoMetric.AdmittedRequestCount => "AdmittedRequestCount"
      case DemoMetric.ReturnedItemCount(op) => s"ReturnedItemCount:$op"
      case DemoMetric.ReplicationLatency(r) => s"Region:$r:ReplicationLatencyMs"
      case DemoMetric.SystemErrorCount => "SystemErrorCount"
      case DemoMetric.LatencyP50(op) => s"LatencyP50:$op"
      case DemoMetric.LatencyP95(op) => s"LatencyP95:$op"
      case DemoMetric.LatencyP99(op) => s"LatencyP99:$op"

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
      case DemoMetric.RegionReadCapacityUnits(r) => (13, r)
      case DemoMetric.RegionWriteCapacityUnits(r) => (14, r)
      case DemoMetric.RegionReplicatedWriteCapacityUnits(r) => (15, r)
      case DemoMetric.RegionStorageBytes(r) => (16, r)
      case DemoMetric.RegionCumulativeEstimatedCost(r) => (17, r)
      case DemoMetric.CrossRegionTransferBytes(src, dst) => (18, s"$src:$dst")
      case DemoMetric.TotalRegionReadCapacityUnits(r) => (19, r)
      case DemoMetric.TotalRegionWriteCapacityUnits(r) => (20, r)
      case DemoMetric.TotalRegionReplicatedWriteCapacityUnits(r) => (21, r)
      case DemoMetric.TotalRegionStorageByteTicks(r) => (22, r)
      case DemoMetric.TotalRegionFinalStorageBytes(r) => (23, r)
      case DemoMetric.TotalRegionEstimatedCost(r) => (24, r)
      case DemoMetric.TotalCrossRegionTransferBytes => (25, "")
      case DemoMetric.TotalCrossRegionTransferCost => (26, "")
      case DemoMetric.CumulativeCrossRegionTransferCost => (31, "")
      case DemoMetric.ProvisionedReadCapacityUnits => (27, "")
      case DemoMetric.ProvisionedWriteCapacityUnits => (28, "")
      case DemoMetric.BillingModeIndicator => (29, "")
      case DemoMetric.ThrottleCount => (30, "")
      case DemoMetric.AdmittedRequestCount => (32, "")
      case DemoMetric.ReturnedItemCount(op) => (33, op)
      case DemoMetric.ReplicationLatency(r) => (34, r)
      case DemoMetric.SystemErrorCount => (35, "")
      case DemoMetric.LatencyP50(op) => (36, op)
      case DemoMetric.LatencyP95(op) => (37, op)
      case DemoMetric.LatencyP99(op) => (38, op)

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
