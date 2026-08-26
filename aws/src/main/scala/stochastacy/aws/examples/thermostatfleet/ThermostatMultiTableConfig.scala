package stochastacy.aws.examples.thermostatfleet

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
