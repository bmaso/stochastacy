package stochastacy.examples.thermostatfleet

import stochastacy.aws.dynamodb.autoscaling.DynamoDbAutoScaler
import stochastacy.aws.dynamodb.table.{DynamoDbTable, ReadConsistency}

object ThermostatFleetCapstoneConfig:

  val ScenarioId = "thermostat-fleet-capstone"

  val DeviceRegistryTableName  = "device-registry"
  val DeviceTelemetryTableName = "device-telemetry"
  val DeviceCommandsTableName  = "device-commands"
  val DeviceAlertsTableName    = "device-alerts"

  private val DefaultRegion = RegionFleetConfig(
    regionName         = "us-east-1",
    initialDeviceCount = 50_000L,
    deviceGrowthPerTick = 0.0
  )

  private val autoScalerPolicy = DynamoDbAutoScaler.Policy(
    targetUtilization            = 0.70,
    evaluationWindowTicks        = 60,
    scaleUpReactionDelayTicks    = 120,
    scaleDownReactionDelayTicks  = 900,
    scaleUpCooldownTicks         = 120,
    scaleDownCooldownTicks       = 900,
    minReadCapacityUnits         = 50L,
    maxReadCapacityUnits         = 2000L,
    minWriteCapacityUnits        = 50L,
    maxWriteCapacityUnits        = 5000L
  )

  val capstoneDefault: MultiTableScenarioConfig = MultiTableScenarioConfig(
    scenarioId      = ScenarioId,
    simulationTicks = 1440L,
    trialCount      = 100,
    parallelism     = 4,
    tables = Vector(
      // Device Registry: on-demand, eventual consistency, low writes, moderate reads
      MultiTableEntry(
        tableName = DeviceRegistryTableName,
        config = ThermostatFleetScenarioConfig(
          scenarioId                       = ScenarioId,
          simulationTicks                  = 1440L,
          trialCount                       = 1,
          parallelism                      = 1,
          tableName                        = DeviceRegistryTableName,
          regions                          = Vector(DefaultRegion),
          telemetryReportsPerDevicePerTick = 0.001,
          customerSupportQueryRatePerTick  = 3.0,
          fleetDashboardScanRatePerTick    = 0.2,
          alertStormProbabilityPerTick     = 0.0,
          billingMode                      = DynamoDbTable.BillingMode.OnDemand(),
          readConsistency                  = ReadConsistency.EventuallyConsistent,
          systemErrorRate                  = 0.001
        )
      ),
      // Device Telemetry: provisioned + auto-scaling + TTL + polar vortex
      MultiTableEntry(
        tableName = DeviceTelemetryTableName,
        config = ThermostatFleetScenarioConfig(
          scenarioId                       = ScenarioId,
          simulationTicks                  = 1440L,
          trialCount                       = 1,
          parallelism                      = 1,
          tableName                        = DeviceTelemetryTableName,
          regions                          = Vector(DefaultRegion),
          telemetryReportsPerDevicePerTick = 0.033,
          customerSupportQueryRatePerTick  = 0.1,
          fleetDashboardScanRatePerTick    = 0.05,
          alertStormProbabilityPerTick     = 0.002,
          alertStormWriteMultiplier        = 5.0,
          billingMode                      = DynamoDbTable.BillingMode.Provisioned(200L, 200L),
          readConsistency                  = ReadConsistency.EventuallyConsistent,
          ttlPeriodTicks                   = Some(720),
          polarVortexWriteMultiplier       = 5.0,
          polarVortexAffectedFraction      = 0.40,
          polarVortexTickRange             = (600L, 700L),
          autoScalerPolicy                 = Some(autoScalerPolicy),
          systemErrorRate                  = 0.001
        )
      ),
      // Device Commands: on-demand, low volume; uses eventual reads since ThermostatFleetBehavior
      // generates GSI queries (which cannot be strongly consistent).
      MultiTableEntry(
        tableName = DeviceCommandsTableName,
        config = ThermostatFleetScenarioConfig(
          scenarioId                       = ScenarioId,
          simulationTicks                  = 1440L,
          trialCount                       = 1,
          parallelism                      = 1,
          tableName                        = DeviceCommandsTableName,
          regions                          = Vector(DefaultRegion),
          telemetryReportsPerDevicePerTick = 0.001,
          customerSupportQueryRatePerTick  = 5.0,
          fleetDashboardScanRatePerTick    = 0.0,
          alertStormProbabilityPerTick     = 0.0,
          billingMode                      = DynamoDbTable.BillingMode.OnDemand(),
          readConsistency                  = ReadConsistency.EventuallyConsistent,
          systemErrorRate                  = 0.001
        )
      ),
      // Device Alerts: on-demand, eventual, higher alert storm rate + polar vortex spike
      MultiTableEntry(
        tableName = DeviceAlertsTableName,
        config = ThermostatFleetScenarioConfig(
          scenarioId                       = ScenarioId,
          simulationTicks                  = 1440L,
          trialCount                       = 1,
          parallelism                      = 1,
          tableName                        = DeviceAlertsTableName,
          regions                          = Vector(DefaultRegion),
          telemetryReportsPerDevicePerTick = 0.005,
          customerSupportQueryRatePerTick  = 0.5,
          fleetDashboardScanRatePerTick    = 0.1,
          alertStormProbabilityPerTick     = 0.01,
          alertStormWriteMultiplier        = 8.0,
          billingMode                      = DynamoDbTable.BillingMode.OnDemand(),
          readConsistency                  = ReadConsistency.EventuallyConsistent,
          polarVortexWriteMultiplier       = 3.0,
          polarVortexAffectedFraction      = 0.40,
          polarVortexTickRange             = (600L, 700L),
          systemErrorRate                  = 0.001
        )
      )
    )
  )
