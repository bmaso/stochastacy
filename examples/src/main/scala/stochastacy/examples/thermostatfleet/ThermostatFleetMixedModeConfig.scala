package stochastacy.examples.thermostatfleet

import stochastacy.aws.dynamodb.pricing.PricingSchedule
import stochastacy.aws.dynamodb.table.*
import stochastacy.sim.SimTime

final case class ThermostatFleetMixedModeConfig(
  scenarioId: String = "thermostat-fleet-mixed-mode",
  simulationTicks: Long = 1200L,
  trialCount: Int = 100,
  parallelism: Int = 4,
  modeSwitchTick: Long = 400L,
  capacityAdjustTick: Long = 800L,
  initialProvisionedRcu: Long = 250L,
  initialProvisionedWcu: Long = 125L,
  adjustedProvisionedRcu: Long = 100L,
  adjustedProvisionedWcu: Long = 333L,
  pricingSchedule: PricingSchedule = PricingSchedule.default,
  systemErrorRate: Double = 0.001
):
  require(scenarioId.nonEmpty, "scenarioId must be non-empty")
  require(simulationTicks >= 1L, "simulationTicks must be at least 1")
  require(trialCount >= 1, "trialCount must be at least 1")
  require(parallelism >= 1, "parallelism must be at least 1")
  require(modeSwitchTick >= 1L && modeSwitchTick < simulationTicks,
    "modeSwitchTick must be at least 1 and less than simulationTicks")
  require(capacityAdjustTick > modeSwitchTick && capacityAdjustTick <= simulationTicks,
    "capacityAdjustTick must be after modeSwitchTick and at most simulationTicks")
  require(initialProvisionedRcu >= 1L, "initialProvisionedRcu must be at least 1")
  require(initialProvisionedWcu >= 1L, "initialProvisionedWcu must be at least 1")
  require(adjustedProvisionedRcu >= 1L, "adjustedProvisionedRcu must be at least 1")
  require(adjustedProvisionedWcu >= 1L, "adjustedProvisionedWcu must be at least 1")

  def toScenarioConfig: ThermostatFleetScenarioConfig =
    val initialProvisioned = DynamoDbTable.BillingMode.Provisioned(initialProvisionedRcu, initialProvisionedWcu)
    val adjustedProvisioned = DynamoDbTable.BillingMode.Provisioned(adjustedProvisionedRcu, adjustedProvisionedWcu)
    val schedule = ReconfigurationSchedule(Vector(
      DynamoDbManagementEvent.SwitchBillingMode(
        eventTime = SimTime.of(modeSwitchTick),
        usecase = "mixed-mode-switch",
        newMode = initialProvisioned
      ),
      DynamoDbManagementEvent.UpdateProvisionedCapacity(
        eventTime = SimTime.of(capacityAdjustTick),
        usecase = "mixed-mode-adjust",
        newCapacity = adjustedProvisioned
      )
    ))
    ThermostatFleetScenarioConfig.singleRegionDefault.copy(
      scenarioId = scenarioId,
      simulationTicks = simulationTicks,
      trialCount = trialCount,
      parallelism = parallelism,
      billingMode = DynamoDbTable.BillingMode.OnDemand(),
      reconfigurationSchedule = Some(schedule),
      pricingSchedule = pricingSchedule,
      systemErrorRate = systemErrorRate
    )
