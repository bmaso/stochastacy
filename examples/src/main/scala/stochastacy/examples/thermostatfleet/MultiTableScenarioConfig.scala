package stochastacy.examples.thermostatfleet

final case class MultiTableEntry(
  tableName: String,
  config: ThermostatFleetScenarioConfig
):
  require(tableName.nonEmpty, "tableName must be non-empty")
  require(!config.isMultiRegion, "multi-table entries must be single-region")

final case class MultiTableScenarioConfig(
  scenarioId: String,
  simulationTicks: Long,
  trialCount: Int,
  parallelism: Int,
  tables: Vector[MultiTableEntry]
):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(simulationTicks >= 1L, "simulationTicks must be at least 1")
  require(trialCount >= 1, "trialCount must be at least 1")
  require(parallelism >= 1, "parallelism must be at least 1")
  require(tables.nonEmpty, "tables must be non-empty")
  require(tables.map(_.tableName).distinct.size == tables.size, "table names must be distinct")
  require(
    tables.forall(_.config.simulationTicks == simulationTicks),
    "all table configs must have the same simulationTicks as the outer config"
  )

object MultiTableScenarioConfig:

  val twoTableDefault: MultiTableScenarioConfig = MultiTableScenarioConfig(
    scenarioId      = "thermostat-fleet-multi-table",
    simulationTicks = 1200L,
    trialCount      = 100,
    parallelism     = 4,
    tables = Vector(
      MultiTableEntry(
        tableName = "device-registry",
        config = ThermostatFleetScenarioConfig(
          scenarioId                       = "thermostat-fleet-multi-table",
          simulationTicks                  = 1200L,
          trialCount                       = 1,   // ignored; outer config governs
          parallelism                      = 1,   // ignored; outer config governs
          tableName                        = "device-registry",
          regions                          = Vector(RegionFleetConfig("us-east-1", 3000L, 0.25)),
          telemetryReportsPerDevicePerTick = 0.005,
          customerSupportQueryRatePerTick  = 2.0,
          fleetDashboardScanRatePerTick    = 0.2
        )
      ),
      MultiTableEntry(
        tableName = "device-telemetry",
        config    = ThermostatFleetScenarioConfig.singleRegionDefault.copy(
          scenarioId      = "thermostat-fleet-multi-table",
          simulationTicks = 1200L
        )
      )
    )
  )
