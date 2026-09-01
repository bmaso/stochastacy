package stochastacy.aws.examples.thermostatfleet

import stochastacy.aws.dynamodb.BillingMode
import stochastacy.aws.examples.demo.{MultiTableScenario, TableSpec}

/**
 * The Thermostat-fleet multi-table scenario, re-created on the v2 core — several **independent** thermostat
 * tables run in one simulation and reported per table (`Table:<name>:…`). It implements
 * [[MultiTableScenario]], so the shared multi-table harness runs it.
 *
 * Reproduces the legacy `MultiTableScenarioConfig.twoTableDefault`: a `device-registry` table (a large,
 * heavily-queried, lightly-written fleet) and a `device-telemetry` table (the phase-4 single-region
 * default). Each per-table [[ThermostatConfig]] is mapped to a named [[TableSpec]]; the per-table
 * `trialCount` / `parallelism` are inert (the outer ensemble governs).
 */
final case class ThermostatMultiTableConfig(
  scenarioId:      String,
  simulationTicks: Long,
  trialCount:      Int,
  parallelism:     Int,
  tableConfigs:    Vector[(String, ThermostatConfig)]
) extends MultiTableScenario:
  require(scenarioId.nonEmpty,   "scenarioId must be non-empty")
  require(simulationTicks >= 1L, "simulationTicks must be at least 1")
  require(trialCount >= 1,       "trialCount must be at least 1")
  require(parallelism >= 1,      "parallelism must be at least 1")
  require(tableConfigs.nonEmpty, "tableConfigs must be non-empty")
  require(tableConfigs.map(_._1).distinct.size == tableConfigs.size, "table names must be distinct")
  require(tableConfigs.forall(_._2.simulationTicks == simulationTicks),
                                 "each table config's simulationTicks must equal the outer horizon")

  def tables: Vector[TableSpec] = tableConfigs.map { (name, cfg) => cfg.tableSpec.copy(tableName = name) }

  /** Apply ensemble overrides, propagating the horizon into each per-table config (whose `arrivals` bake
   *  in their own tick count) so the outer and per-table horizons stay in lock-step. */
  def withEnsemble(trials: Int, ticks: Long, par: Int): ThermostatMultiTableConfig =
    copy(
      trialCount      = trials,
      simulationTicks = ticks,
      parallelism     = par,
      tableConfigs    = tableConfigs.map { (n, c) => (n, c.copy(simulationTicks = ticks)) }
    )

object ThermostatMultiTableConfig:

  /** The two-table scenario matching the legacy `MultiTableScenarioConfig.twoTableDefault`. */
  val twoTableDefault: ThermostatMultiTableConfig = ThermostatMultiTableConfig(
    scenarioId      = "thermostat-fleet-multi-table",
    simulationTicks = 1200L,
    trialCount      = 100,
    parallelism     = 4,
    tableConfigs = Vector(
      "device-registry" -> ThermostatConfig(
        scenarioId                       = "thermostat-fleet-multi-table",
        simulationTicks                  = 1200L,
        trialCount                       = 1, // ignored; the outer config governs
        parallelism                      = 1, // ignored; the outer config governs
        initialDeviceCount               = 3000L,
        deviceGrowthPerTick              = 0.25,
        telemetryReportsPerDevicePerTick = 0.005,
        customerSupportQueryRatePerTick  = 2.0,
        fleetDashboardScanRatePerTick    = 0.2,
        systemErrorRate                  = 0.0 // legacy fresh-config default (device-telemetry keeps 0.001)
      ),
      "device-telemetry" -> ThermostatConfig.singleRegionDefault.copy(
        scenarioId      = "thermostat-fleet-multi-table",
        simulationTicks = 1200L
      )
    )
  )

  /** The full **4-table capstone** matching the legacy `ThermostatFleetCapstoneConfig` (single-region): a
   *  fixed 50 k-device fleet across a Registry (on-demand, read-heavy), a Telemetry table (provisioned +
   *  burst + auto-scaling + TTL + PITR, under a polar-vortex + alert-storm workload), a Commands table
   *  (transactional command dispatch), and an Alerts table (storm + vortex). The integration proof. */
  val capstoneDefault: ThermostatMultiTableConfig =
    val ticks = 1440L
    def base(name: String) = ThermostatConfig(
      scenarioId = "thermostat-fleet-capstone", simulationTicks = ticks, trialCount = 1, parallelism = 1,
      initialDeviceCount = 50000L, deviceGrowthPerTick = 0.0,
      morningSpikePeakMultiplier = 1.0, eveningSpikePeakMultiplier = 1.0,
      systemErrorRate = 0.001
    )
    ThermostatMultiTableConfig(
      scenarioId = "thermostat-fleet-capstone", simulationTicks = ticks, trialCount = 100, parallelism = 4,
      tableConfigs = Vector(
        // Registry: on-demand, lightly written, heavily queried.
        "device-registry" -> base("device-registry").copy(
          telemetryReportsPerDevicePerTick = 0.001,
          customerSupportQueryRatePerTick  = 3.0, fleetDashboardScanRatePerTick = 0.2,
          alertStormProbabilityPerTick     = 0.0
        ),
        // Telemetry: provisioned + burst + auto-scaling + TTL + PITR + polar-vortex + alert-storm.
        "device-telemetry" -> base("device-telemetry").copy(
          telemetryReportsPerDevicePerTick = 0.033,
          customerSupportQueryRatePerTick  = 0.1, fleetDashboardScanRatePerTick = 0.05,
          alertStormProbabilityPerTick     = 0.002, alertStormWriteMultiplier = 5.0,
          polarVortexWriteMultiplier       = 5.0, polarVortexAffectedFraction = 0.40, polarVortexTickRange = (600L, 700L),
          billingMode                      = BillingMode.Provisioned(200L, 200L),
          burstWindowTicks                 = 300,
          autoScalingPolicy                = Some(ThermostatConfig.telemetryAutoScalingPolicy),
          ttlPeriodTicks                   = Some(720),
          pointInTimeRecoveryEnabled       = true
        ),
        // Commands: on-demand; each dispatch is a 2-item transaction (status update + audit).
        "device-commands" -> base("device-commands").copy(
          telemetryReportsPerDevicePerTick = 0.001,
          customerSupportQueryRatePerTick  = 5.0, fleetDashboardScanRatePerTick = 0.0,
          alertStormProbabilityPerTick     = 0.0,
          transactWriteItemsPerItemBytes   = Some(Vector(200L, 150L))
        ),
        // Alerts: on-demand, storm-heavy + polar-vortex spike.
        "device-alerts" -> base("device-alerts").copy(
          telemetryReportsPerDevicePerTick = 0.005,
          customerSupportQueryRatePerTick  = 0.5, fleetDashboardScanRatePerTick = 0.1,
          alertStormProbabilityPerTick     = 0.01, alertStormWriteMultiplier = 5.0,
          polarVortexWriteMultiplier       = 5.0, polarVortexAffectedFraction = 0.40, polarVortexTickRange = (600L, 700L)
        )
      )
    )
