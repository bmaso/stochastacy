package stochastacy.examples.thermostatfleet

import stochastacy.aws.dynamodb.autoscaling.DynamoDbAutoScaler
import stochastacy.aws.dynamodb.pricing.{DynamoDbPricingRates, PricingSchedule}
import stochastacy.aws.dynamodb.table.*
import stochastacy.aws.dynamodb.{DynamoDbReadTarget}
import stochastacy.aws.transfer.CrossRegionTransferPricingRates
import stochastacy.workload.*

final case class RegionFleetConfig(
  regionName: String,
  initialDeviceCount: Long,
  deviceGrowthPerTick: Double
):
  require(regionName.nonEmpty, "regionName must be non-empty")
  require(initialDeviceCount >= 0L, "initialDeviceCount must be non-negative")
  require(deviceGrowthPerTick >= 0.0, "deviceGrowthPerTick must be non-negative")

final case class ThermostatFleetScenarioConfig(
  scenarioId: String,
  simulationTicks: Long,
  trialCount: Int,
  parallelism: Int,
  tableName: String = "device-telemetry",
  regions: Vector[RegionFleetConfig],
  telemetryReportsPerDevicePerTick: Double,
  telemetryItemMeanBytes: Long = 300L,
  telemetryItemBytesVariance: Double = 0.25,
  morningSpikePeakMultiplier: Double = 2.0,
  morningSpikePeakTickRange: (Long, Long) = (420L, 540L),
  eveningSpikePeakMultiplier: Double = 2.0,
  eveningSpikePeakTickRange: (Long, Long) = (1020L, 1140L),
  alertStormProbabilityPerTick: Double = 0.002,
  alertStormDurationTicks: Int = 30,
  alertStormWriteMultiplier: Double = 5.0,
  customerSupportQueryRatePerTick: Double = 0.5,
  fleetDashboardScanRatePerTick: Double = 0.1,
  readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent,
  customerDevicesGsiProjection: DynamoDbTable.IndexProjection = DynamoDbTable.IndexProjection.KeysOnly,
  fleetAlertsGsiProjectedNonKeyBytes: Long = 64L,
  deviceStatusGsiProjection: DynamoDbTable.IndexProjection = DynamoDbTable.IndexProjection.All,
  readingTypeHistoryLsiProjection: DynamoDbTable.IndexProjection = DynamoDbTable.IndexProjection.All,
  itemCollectionSizeLimitBytes: Option[Long] = None,
  billingMode: DynamoDbTable.BillingMode = DynamoDbTable.BillingMode.OnDemand(),
  hotPartitionModel: Option[DynamoDbTable.HotPartitionModel] = None,
  burstCapacityModel: Option[DynamoDbTable.BurstCapacityModel] = None,
  adaptiveCapacityModel: Option[DynamoDbTable.AdaptiveCapacityModel] = None,
  dynamicPartitionTopologyModel: Option[DynamoDbTable.DynamicPartitionTopologyModel] = None,
  ttlPeriodTicks: Option[Int] = None,
  polarVortexWriteMultiplier: Double = 1.0,
  polarVortexAffectedFraction: Double = 1.0,
  polarVortexTickRange: (Long, Long) = (0L, 0L),
  reconfigurationSchedule: Option[ReconfigurationSchedule] = None,
  autoScalerPolicy: Option[DynamoDbAutoScaler.Policy] = None,
  replicationModel: Option[ReplicationModel] = None,
  transferPricingRates: CrossRegionTransferPricingRates = CrossRegionTransferPricingRates(),
  pricingSchedule: PricingSchedule = PricingSchedule.default,
  tableClass: DynamoDbTable.TableClass = DynamoDbTable.TableClass.Standard,
  systemErrorRate: Double = 0.0,
  transactWriteItemsPerItemBytes: Option[Vector[Long]] = None,
  pointInTimeRecoveryEnabled: Boolean = false
):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(simulationTicks >= 1L, "simulationTicks must be at least 1")
  require(trialCount >= 1, "trialCount must be at least 1")
  require(parallelism >= 1, "parallelism must be at least 1")
  require(tableName.nonEmpty, "tableName must be non-empty")
  require(regions.nonEmpty, "regions must be non-empty")
  require(regions.map(_.regionName).distinct.size == regions.size, "region names must be distinct")
  require(telemetryReportsPerDevicePerTick >= 0.0, "telemetryReportsPerDevicePerTick must be non-negative")
  require(telemetryItemMeanBytes >= 1L, "telemetryItemMeanBytes must be at least 1")
  require(telemetryItemBytesVariance >= 0.0, "telemetryItemBytesVariance must be non-negative")
  require(morningSpikePeakMultiplier >= 1.0, "morningSpikePeakMultiplier must be at least 1.0")
  require(
    morningSpikePeakTickRange._1 >= 1L && morningSpikePeakTickRange._2 >= morningSpikePeakTickRange._1,
    "morningSpikePeakTickRange must be a valid non-empty range starting at tick 1 or later"
  )
  require(eveningSpikePeakMultiplier >= 1.0, "eveningSpikePeakMultiplier must be at least 1.0")
  require(
    eveningSpikePeakTickRange._1 >= 1L && eveningSpikePeakTickRange._2 >= eveningSpikePeakTickRange._1,
    "eveningSpikePeakTickRange must be a valid non-empty range starting at tick 1 or later"
  )
  require(
    alertStormProbabilityPerTick >= 0.0 && alertStormProbabilityPerTick <= 1.0,
    "alertStormProbabilityPerTick must be between 0 and 1"
  )
  require(alertStormDurationTicks >= 1, "alertStormDurationTicks must be at least 1")
  require(alertStormWriteMultiplier >= 1.0, "alertStormWriteMultiplier must be at least 1.0")
  require(customerSupportQueryRatePerTick >= 0.0, "customerSupportQueryRatePerTick must be non-negative")
  require(fleetDashboardScanRatePerTick >= 0.0, "fleetDashboardScanRatePerTick must be non-negative")
  require(fleetAlertsGsiProjectedNonKeyBytes >= 0L, "fleetAlertsGsiProjectedNonKeyBytes must be non-negative")
  itemCollectionSizeLimitBytes.foreach { limit =>
    require(limit >= 1L, "itemCollectionSizeLimitBytes must be positive when defined")
  }
  ttlPeriodTicks.foreach { p =>
    require(p >= 1, "ttlPeriodTicks must be at least 1 when defined")
  }
  require(polarVortexWriteMultiplier >= 1.0, "polarVortexWriteMultiplier must be at least 1.0")
  require(
    polarVortexAffectedFraction > 0.0 && polarVortexAffectedFraction <= 1.0,
    "polarVortexAffectedFraction must be in (0, 1]"
  )
  require(
    polarVortexTickRange._1 >= 0L && polarVortexTickRange._2 >= polarVortexTickRange._1,
    "polarVortexTickRange must be a valid range (start >= 0, end >= start)"
  )
  reconfigurationSchedule.foreach { schedule =>
    schedule.validateAgainst(billingMode, simulationTicks) match
      case Left(message) => throw new IllegalArgumentException(message)
      case Right(_) => ()
  }
  transactWriteItemsPerItemBytes.foreach { itemBytes =>
    require(itemBytes.nonEmpty, "transactWriteItemsPerItemBytes must be non-empty when defined")
    require(itemBytes.forall(_ >= 1L), "transactWriteItemsPerItemBytes values must be positive")
  }

  def isMultiRegion: Boolean = regions.size > 1

  def totalInitialDeviceCount: Long = regions.map(_.initialDeviceCount).sum

  def toWorkloadDefinition(region: RegionFleetConfig): WorkloadDefinition =

    def fleetSize(tick: Long): Long =
      math.max(1L, region.initialDeviceCount + (region.deviceGrowthPerTick * tick).toLong)

    val baseTelemetryLambda: StatelessSampler[Double] = Sampler.deterministic { tick =>
      val morning = TemporalShapeFunctions.triangularFactor(
        morningSpikePeakTickRange._1, morningSpikePeakTickRange._2, morningSpikePeakMultiplier)(tick)
      val evening = TemporalShapeFunctions.triangularFactor(
        eveningSpikePeakTickRange._1, eveningSpikePeakTickRange._2, eveningSpikePeakMultiplier)(tick)
      val spike = math.max(morning, evening)
      val (vs, ve) = polarVortexTickRange
      val polar =
        if vs > 0L && tick >= vs && tick <= ve then
          1.0 + polarVortexAffectedFraction * (polarVortexWriteMultiplier - 1.0)
        else 1.0
      telemetryReportsPerDevicePerTick * spike * polar * fleetSize(tick).toDouble
    }

    val stormBurstAmount: Long => Double = tick =>
      telemetryReportsPerDevicePerTick * (alertStormWriteMultiplier - 1.0) * fleetSize(tick).toDouble

    val telemetryRateSampler: StatelessSampler[Int] = ErasedSampler.of(
      RandomBurstSampler(baseTelemetryLambda, alertStormProbabilityPerTick,
                         alertStormDurationTicks, stormBurstAmount)
    )

    val telemetryShape: PacedRequestFactory = transactWriteItemsPerItemBytes match
      case Some(perItemBytes) =>
        PacedRequestFactory.transactWriteItems(telemetryRateSampler,
          perItemBytes.map(b => ConstantSampler(b)))
      case None =>
        PacedRequestFactory.putItem(telemetryRateSampler, ConstantSampler(telemetryItemMeanBytes))

    val queryShape = PacedRequestFactory.query(
      PoissonSampler.constant(customerSupportQueryRatePerTick),
      DynamoDbReadTarget.GlobalSecondaryIndex(tableName, ThermostatFleetScenarioConfig.CustomerDevicesGsiName),
      readConsistency
    )

    val scanShape = PacedRequestFactory.scan(
      PoissonSampler.constant(fleetDashboardScanRatePerTick),
      DynamoDbReadTarget.GlobalSecondaryIndex(tableName, ThermostatFleetScenarioConfig.FleetAlertsGsiName)
    )

    WorkloadDefinition.ofIndependent(tableName, scenarioId, Vector(telemetryShape, queryShape, scanShape))

object ThermostatFleetScenarioConfig:

  private val OneGiB: Long = 1024L * 1024L * 1024L

  val CustomerDevicesGsiName = "customer-devices"
  val FleetAlertsGsiName = "fleet-alerts"
  val DeviceStatusGsiName = "device-status"
  val ReadingTypeHistoryLsiName = "reading-type-history"

  val singleRegionDefault: ThermostatFleetScenarioConfig =
    ThermostatFleetScenarioConfig(
      scenarioId = "thermostat-fleet-single-region",
      simulationTicks = 1200L,
      trialCount = 100,
      parallelism = 4,
      regions = Vector(
        RegionFleetConfig(
          regionName = "us-east-1",
          initialDeviceCount = 3000L,
          deviceGrowthPerTick = 0.25
        )
      ),
      telemetryReportsPerDevicePerTick = 0.033,
      morningSpikePeakTickRange = (420L, 540L),
      eveningSpikePeakTickRange = (1020L, 1140L),
      customerSupportQueryRatePerTick = 0.5,
      fleetDashboardScanRatePerTick = 0.1,
      transferPricingRates = CrossRegionTransferPricingRates.flat(
        Map("us-east-1" -> BigDecimal("0.02"))
      ),
      systemErrorRate = 0.001
    )

  val multiRegionDefault: ThermostatFleetScenarioConfig =
    ThermostatFleetScenarioConfig(
      scenarioId = "thermostat-fleet-multi-region",
      simulationTicks = 1200L,
      trialCount = 100,
      parallelism = 4,
      regions = Vector(
        RegionFleetConfig(regionName = "us-east-1", initialDeviceCount = 1800L, deviceGrowthPerTick = 0.15),
        RegionFleetConfig(regionName = "eu-west-1", initialDeviceCount = 900L, deviceGrowthPerTick = 0.075),
        RegionFleetConfig(regionName = "ap-southeast-1", initialDeviceCount = 300L, deviceGrowthPerTick = 0.025)
      ),
      telemetryReportsPerDevicePerTick = 0.033,
      morningSpikePeakTickRange = (420L, 540L),
      eveningSpikePeakTickRange = (1020L, 1140L),
      customerSupportQueryRatePerTick = 0.5,
      fleetDashboardScanRatePerTick = 0.1,
      transferPricingRates = CrossRegionTransferPricingRates.flat(Map(
        "us-east-1"      -> BigDecimal("0.02"),
        "eu-west-1"      -> BigDecimal("0.02"),
        "ap-southeast-1" -> BigDecimal("0.08")
      )),
      pricingSchedule = PricingSchedule.byRegion(
        Map(
          "us-east-1" -> DynamoDbPricingRates.phase1Default,
          "eu-west-1" -> DynamoDbPricingRates(standard = DynamoDbPricingRates.RateSet(
            readCapacityUnitPrice    = BigDecimal("0.000000283"),
            writeCapacityUnitPrice   = BigDecimal("0.0000014"),
            storagePricePerGiBSecond = BigDecimal("0.000000108507")
          )),
          "ap-southeast-1" -> DynamoDbPricingRates(standard = DynamoDbPricingRates.RateSet(
            readCapacityUnitPrice    = BigDecimal("0.000000338"),
            writeCapacityUnitPrice   = BigDecimal("0.000001690"),
            storagePricePerGiBSecond = BigDecimal("0.000000125")
          ))
        ),
        fallback = DynamoDbPricingRates.phase1Default
      ),
      systemErrorRate = 0.001
    )
