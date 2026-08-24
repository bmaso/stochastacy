package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GlobalSecondaryIndex, IndexProjection, LocalSecondaryIndex, TableBehavior, TableSummaryState}
import stochastacy.aws.examples.demo.SingleTableScenario
import stochastacy.core.component.Timed

/**
 * The Thermostat-fleet single-region scenario, re-created on the v2 core — a fleet of IoT thermostats
 * writing telemetry to one on-demand `device-telemetry` table, queried by customer and scanned for fleet
 * alerts. It implements [[SingleTableScenario]], so the shared demo harness runs it.
 *
 *   - the fleet has `initialDeviceCount` devices and grows `deviceGrowthPerTick` per tick; the table starts
 *     **empty** and fills as devices report telemetry;
 *   - telemetry items are `telemetryItemMeanBytes` ± `telemetryItemBytesVariance`;
 *   - three GSIs (`customer-devices` KeysOnly, `fleet-alerts` Include(64), `device-status` All) and one LSI
 *     (`reading-type-history` All) — a mix of projections;
 *   - reads: a per-tick customer-support query (`customer-devices`) and a fleet-dashboard scan
 *     (`fleet-alerts`).
 *
 * The temporal shaping of the telemetry rate (morning/evening spikes, polar-vortex, alert storms) is added
 * in a later slice; here the rate is a plain per-device constant scaled by the (growing) fleet.
 */
final case class ThermostatConfig(
  scenarioId:                       String = "thermostat-fleet-single-region",
  simulationTicks:                  Long   = 1200L,
  trialCount:                       Int    = 100,
  parallelism:                      Int    = 4,
  initialDeviceCount:               Long   = 3000L,
  deviceGrowthPerTick:              Double = 0.25,
  telemetryReportsPerDevicePerTick: Double = 0.033,
  telemetryItemMeanBytes:           Long   = 300L,
  telemetryItemBytesVariance:       Double = 0.25,
  customerSupportQueryRatePerTick:  Double = 0.5,
  fleetDashboardScanRatePerTick:    Double = 0.1
) extends SingleTableScenario:
  require(scenarioId.nonEmpty,                          "scenarioId must be non-empty")
  require(simulationTicks >= 1L,                        "simulationTicks must be at least 1")
  require(trialCount >= 1,                              "trialCount must be at least 1")
  require(parallelism >= 1,                             "parallelism must be at least 1")
  require(initialDeviceCount >= 0L,                     "initialDeviceCount must be non-negative")
  require(deviceGrowthPerTick >= 0.0,                   "deviceGrowthPerTick must be non-negative")
  require(telemetryReportsPerDevicePerTick >= 0.0,      "telemetryReportsPerDevicePerTick must be non-negative")
  require(telemetryItemMeanBytes >= 1L,                 "telemetryItemMeanBytes must be at least 1")
  require(telemetryItemBytesVariance >= 0.0 && telemetryItemBytesVariance < 1.0, "telemetryItemBytesVariance must be in [0, 1)")
  require(customerSupportQueryRatePerTick >= 0.0,       "customerSupportQueryRatePerTick must be non-negative")
  require(fleetDashboardScanRatePerTick >= 0.0,         "fleetDashboardScanRatePerTick must be non-negative")

  def globalSecondaryIndexes: Vector[GlobalSecondaryIndex] = Vector(
    GlobalSecondaryIndex(ThermostatConfig.CustomerDevicesGsiName, IndexProjection.KeysOnly),
    GlobalSecondaryIndex(ThermostatConfig.FleetAlertsGsiName,     IndexProjection.Include(ThermostatConfig.FleetAlertsProjectedNonKeyBytes)),
    GlobalSecondaryIndex(ThermostatConfig.DeviceStatusGsiName,    IndexProjection.All)
  )
  def localSecondaryIndexes: Vector[LocalSecondaryIndex] = Vector(
    LocalSecondaryIndex(ThermostatConfig.ReadingTypeHistoryLsiName, IndexProjection.All)
  )

  /** The table starts empty and fills as devices report telemetry. */
  def initialTableState: TableSummaryState = TableSummaryState.empty
  def initialStorageBytesAllTargets: Long  = 0L

  /** Devices in the fleet at `tick` (at least one) — the single source shared by workload and behavior. */
  def fleetSize(tick: Long): Long =
    math.max(1L, initialDeviceCount + (deviceGrowthPerTick * tick).toLong)

  def behavior: TableBehavior = new ThermostatFleetBehavior(this)

  def arrivals(rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    ThermostatWorkload.arrivals(this, rng)

object ThermostatConfig:
  val CustomerDevicesGsiName    = "customer-devices"
  val FleetAlertsGsiName        = "fleet-alerts"
  val DeviceStatusGsiName       = "device-status"
  val ReadingTypeHistoryLsiName = "reading-type-history"
  val FleetAlertsProjectedNonKeyBytes = 64L

  /** The single-region scenario matching the legacy `ThermostatFleetScenarioConfig.singleRegionDefault`. */
  val singleRegionDefault: ThermostatConfig = ThermostatConfig()
