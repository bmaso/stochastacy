package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{AutoScalingPolicy, BillingMode, DynamoDbRequest, GlobalSecondaryIndex, IndexProjection, LocalSecondaryIndex, ReconfigurationEvent, ReconfigurationSchedule, ScheduledReconfiguration, TableBehavior, TableSummaryState}
import stochastacy.aws.examples.demo.SingleTableScenario
import stochastacy.core.component.Timed
import stochastacy.core.sampler.{RandomBurstSampler, Sampler, StatelessSampler, TemporalShapeFunctions}

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
 * The telemetry write rate is **temporally shaped** (Slice 5): a morning and an evening triangular spike
 * (combined by `max`), a polar-vortex window multiplier, and stochastic alert-storm bursts, all on top of
 * the fleet-scaled per-device rate — reproducing the legacy `ThermostatFleetScenarioConfig` telemetry
 * profile. Reads (query/scan) are constant-rate. With the shipped `singleRegionDefault` the vortex is off
 * (`polarVortexWriteMultiplier == 1.0`), so a no-shape config still yields the plain fleet-scaled rate.
 *
 * A small `systemErrorRate` (default 0.001, matching the legacy) models DynamoDB's intrinsic transient
 * failures: the harness attaches a load-independent `ChaosGate` on the table's inlet, so ~0.1 % of
 * requests are rejected with a `SystemErrorResponse` — consuming no capacity and mutating no state.
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
  fleetDashboardScanRatePerTick:    Double = 0.1,
  morningSpikePeakMultiplier:       Double        = 2.0,
  morningSpikePeakTickRange:        (Long, Long)  = (420L, 540L),
  eveningSpikePeakMultiplier:       Double        = 2.0,
  eveningSpikePeakTickRange:        (Long, Long)  = (1020L, 1140L),
  alertStormProbabilityPerTick:     Double        = 0.002,
  alertStormDurationTicks:          Int           = 30,
  alertStormWriteMultiplier:        Double        = 5.0,
  polarVortexWriteMultiplier:       Double        = 1.0,
  polarVortexAffectedFraction:      Double        = 0.5,
  polarVortexTickRange:             (Long, Long)  = (0L, 0L),
  override val systemErrorRate:     Double        = 0.001,
  override val reconfigurationSchedule: ReconfigurationSchedule = ReconfigurationSchedule.empty,
  override val billingMode:         BillingMode                 = BillingMode.OnDemand,
  override val burstWindowTicks:    Int                         = 0,
  override val autoScalingPolicy:   Option[AutoScalingPolicy]   = None,
  override val ttlPeriodTicks:      Option[Int]                 = None,
  override val pointInTimeRecoveryEnabled: Boolean              = false,
  transactWriteItemsPerItemBytes:   Option[Vector[Long]]        = None,
  useTransactions:                  Boolean                     = true
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
  require(morningSpikePeakMultiplier >= 1.0,            "morningSpikePeakMultiplier must be at least 1.0")
  require(morningSpikePeakTickRange._1 >= 1L && morningSpikePeakTickRange._2 >= morningSpikePeakTickRange._1,
                                                        "morningSpikePeakTickRange must be non-empty and start at tick 1 or later")
  require(eveningSpikePeakMultiplier >= 1.0,            "eveningSpikePeakMultiplier must be at least 1.0")
  require(eveningSpikePeakTickRange._1 >= 1L && eveningSpikePeakTickRange._2 >= eveningSpikePeakTickRange._1,
                                                        "eveningSpikePeakTickRange must be non-empty and start at tick 1 or later")
  require(alertStormProbabilityPerTick >= 0.0 && alertStormProbabilityPerTick <= 1.0, "alertStormProbabilityPerTick must be in [0, 1]")
  require(alertStormDurationTicks >= 1,                 "alertStormDurationTicks must be at least 1")
  require(alertStormWriteMultiplier >= 1.0,             "alertStormWriteMultiplier must be at least 1.0")
  require(polarVortexWriteMultiplier >= 1.0,            "polarVortexWriteMultiplier must be at least 1.0")
  require(polarVortexAffectedFraction > 0.0 && polarVortexAffectedFraction <= 1.0, "polarVortexAffectedFraction must be in (0, 1]")
  require(polarVortexTickRange._1 >= 0L && polarVortexTickRange._2 >= polarVortexTickRange._1,
                                                        "polarVortexTickRange must be a valid range (start >= 0, end >= start)")
  require(systemErrorRate >= 0.0 && systemErrorRate < 1.0, "systemErrorRate must be in [0, 1)")
  require(transactWriteItemsPerItemBytes.forall(v => v.nonEmpty && v.forall(_ > 0L)),
                                                        "transactWriteItemsPerItemBytes, when set, must be non-empty and positive")
  reconfigurationSchedule.validate(billingMode, simulationTicks) match
    case Left(message) => throw new IllegalArgumentException(message)
    case Right(_)      => ()

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

  /** The fleet-scaled, spike- and vortex-shaped expected telemetry rate (λ) at `tick`, before alert storms.
   *  `reportsPerDevicePerTick × max(morningSpike, eveningSpike) × vortex × fleetSize` — the legacy formula. */
  private def baseTelemetryLambda: StatelessSampler[Double] = Sampler.deterministic { tick =>
    val morning = TemporalShapeFunctions.triangularFactor(
      morningSpikePeakTickRange._1, morningSpikePeakTickRange._2, morningSpikePeakMultiplier)(tick)
    val evening = TemporalShapeFunctions.triangularFactor(
      eveningSpikePeakTickRange._1, eveningSpikePeakTickRange._2, eveningSpikePeakMultiplier)(tick)
    val spike   = math.max(morning, evening)
    val (vs, ve) = polarVortexTickRange
    val vortex  =
      if vs > 0L && tick >= vs && tick <= ve then 1.0 + polarVortexAffectedFraction * (polarVortexWriteMultiplier - 1.0)
      else 1.0
    telemetryReportsPerDevicePerTick * spike * vortex * fleetSize(tick).toDouble
  }

  /** The per-tick telemetry count sampler: the shaped base λ wrapped in alert-storm bursts (additive in
   *  λ-space, stateful across ticks). Reproduces the legacy `RandomBurstSampler` telemetry rate. */
  def telemetryRateSampler: RandomBurstSampler[Unit] =
    RandomBurstSampler(
      inner         = baseTelemetryLambda,
      probability   = alertStormProbabilityPerTick,
      durationTicks = alertStormDurationTicks,
      burstAmount   = tick => telemetryReportsPerDevicePerTick * (alertStormWriteMultiplier - 1.0) * fleetSize(tick).toDouble
    )

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

  /** The mixed-mode scenario matching the legacy `ThermostatFleetMixedModeConfig`: the single-region
   *  workload, **starting on-demand**, switched to provisioned at tick 400 and then right-sized down at
   *  tick 800 (the "right-sizing trap" — the tightened capacity throttles telemetry bursts on-demand
   *  absorbed). */
  val mixedModeDefault: ThermostatConfig = singleRegionDefault.copy(
    scenarioId = "thermostat-fleet-mixed-mode",
    reconfigurationSchedule = ReconfigurationSchedule(Vector(
      ScheduledReconfiguration(400L, ReconfigurationEvent.SwitchBillingMode(BillingMode.Provisioned(250L, 125L))),
      ScheduledReconfiguration(800L, ReconfigurationEvent.UpdateProvisionedCapacity(BillingMode.Provisioned(100L, 333L)))
    ))
  )

  /** The auto-scaling policy for the telemetry table — the legacy capstone's values (target 70 %,
   *  60-tick window, 2-min scale-up / 15-min scale-down at a 1-second tick). */
  val telemetryAutoScalingPolicy: AutoScalingPolicy = AutoScalingPolicy(
    targetUtilization = 0.70, evaluationWindowTicks = 60,
    scaleUpReactionDelayTicks = 120, scaleDownReactionDelayTicks = 900,
    scaleUpCooldownTicks = 120, scaleDownCooldownTicks = 900,
    minReadCapacityUnits = 50L, maxReadCapacityUnits = 2000L,
    minWriteCapacityUnits = 50L, maxWriteCapacityUnits = 5000L
  )

  /** A **provisioned + auto-scaling + burst** telemetry scenario: the single-region workload starting
   *  provisioned with a deliberately modest reservation, so the growing fleet and telemetry spikes are
   *  first absorbed by banked **burst** capacity and then met by reactive **auto-scaling** (base capacity
   *  only). Compared against a same-reservation *fixed* table, it throttles far less. No reconfiguration
   *  schedule (mutually exclusive with auto-scaling). */
  val autoScalingDefault: ThermostatConfig = singleRegionDefault.copy(
    scenarioId        = "thermostat-fleet-autoscaling",
    billingMode       = BillingMode.Provisioned(readCapacityUnits = 100L, writeCapacityUnits = 150L),
    autoScalingPolicy = Some(telemetryAutoScalingPolicy),
    burstWindowTicks  = 300 // ~300 s of burst at a 1-second tick
  )
