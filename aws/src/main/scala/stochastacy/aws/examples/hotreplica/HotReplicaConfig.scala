package stochastacy.aws.examples.hotreplica

import stochastacy.aws.dynamodb.{BillingMode, DynamoDbTable, GlobalTable, ReplicationModel, TableSummaryState}
import stochastacy.aws.examples.demo.{Pricing, Rates}
import stochastacy.aws.examples.thermostatfleet.ThermostatConfig
import stochastacy.core.sampler.{LogNormalSampler, StatelessSampler}

/**
 * One region of the hot-replica Global Table: a thermostat **telemetry** table (the reused
 * [[ThermostatConfig]] shape — mixed-projection GSIs + LSI, temporally-shaped telemetry writes, customer
 * queries, fleet scans) sized by its own fleet, on-demand or provisioned with its own per-region pricing.
 *
 * There is **no cross-region transfer charge**: AWS does not bill data transfer for replicating data between
 * the Regions of a global table, so replication egress is free. The replication **volume** (transfer bytes) is
 * still surfaced as a metric — it just carries no cost.
 */
final case class RegionConfig(
  regionName:            String,
  fleetSize:             Long,
  growthPerTick:         Double,
  billingMode:           BillingMode,
  rates:                 Rates
):
  require(regionName.nonEmpty,  "regionName must be non-empty")
  require(fleetSize >= 1L,      "fleetSize must be at least 1")
  require(growthPerTick >= 0.0, "growthPerTick must be non-negative")

  /** The reused thermostat telemetry scenario for this region — its fleet size, growth and billing mode,
   *  everything else the thermostat single-region default (so arm A reconciles directly against the
   *  single-region telemetry baseline). The vortex/spikes ride along from the default; the shared simulation
   *  horizon is stamped in by the ensemble. No system-error `ChaosGate` is attached in the multi-region graph
   *  (the modeled ~0.1 % transient-failure rate is well within the reconcile tolerance). */
  def thermostat(simulationTicks: Long): ThermostatConfig =
    ThermostatConfig(
      scenarioId          = s"hot-replica-$regionName",
      simulationTicks     = simulationTicks,
      initialDeviceCount  = fleetSize,
      deviceGrowthPerTick = growthPerTick,
      systemErrorRate     = 0.0,        // no ChaosGate inside the Global Table graph
      billingMode         = billingMode
    )

  def tableConfig(simulationTicks: Long, latency: StatelessSampler[Double]): DynamoDbTable.Config =
    val tc = thermostat(simulationTicks)
    DynamoDbTable.Config(
      initialState           = tc.initialTableState,
      behavior               = tc.behavior,
      latency                = latency,
      globalSecondaryIndexes = tc.globalSecondaryIndexes,
      localSecondaryIndexes  = tc.localSecondaryIndexes,
      billingMode            = billingMode
    )

/**
 * A bespoke, thermostat-flavored **multi-region** scenario: three regional telemetry tables composing one
 * [[GlobalTable]] that replicates every write to its peers, exercising the phase-11 stack end-to-end
 * (replication, rWCU billing + throttling, cross-region transfer, and the per-region + per-link metrics).
 *
 * Two arms ship:
 *   - [[HotReplicaConfig.reconcileDefault]] — all on-demand, the multi-region reference fleets
 *     (1800 / 900 / 300); healthy replication (`PendingReplicationCount` ≈ 0, `ReplicationLatency` ≈ the
 *     link-lag mean). Reconciled per-region against the established per-region baseline in Slice 5.
 *   - [[HotReplicaConfig.depletionDefault]] — an **8 : 1** fleet discrepancy (2000 / 250 / 300) whose small
 *     `ap-southeast-1` replica is **provisioned with an inbound rWCU ceiling below its ~2250 combined inbound
 *     rate**, so **both** inbound links back up. A modestly longer `us-east-1 → ap-southeast-1` link lag plus
 *     the fair-share drain make the two inbound links **diverge**: the heavy `us-east-1` stream builds the
 *     deeper, slower queue (pending ≈ 8× and latency above the light `eu-west-1` stream). The two large
 *     regions stay healthy by contrast. A v2 showcase, not reconciled (these coupled metrics stand on their own).
 */
final case class HotReplicaConfig(
  scenarioId:       String,
  simulationTicks:  Long,
  trialCount:       Int,
  parallelism:      Int,
  regions:          Vector[RegionConfig],
  replicationModel: ReplicationModel
):
  require(scenarioId.nonEmpty,    "scenarioId must be non-empty")
  require(simulationTicks >= 1L,  "simulationTicks must be at least 1")
  require(trialCount >= 1,        "trialCount must be at least 1")
  require(parallelism >= 1,       "parallelism must be at least 1")
  require(regions.size >= 2,      "a Global Table needs at least two regions")
  require(regions.map(_.regionName).distinct.size == regions.size, "region names must be unique")

  def regionNames: Vector[String] = regions.map(_.regionName).sorted
  def region(name: String): RegionConfig = regions.find(_.regionName == name).get

  /** A small, constant per-op service latency (fractional ticks) — the demo's cost/replication story does not
   *  turn on service latency, only on the replication link lag. */
  val opLatency: StatelessSampler[Double] = LogNormalSampler.constant(math.log(0.005), 0.0)

  def globalTableConfig: GlobalTable.Config =
    GlobalTable.Config(
      regions          = regions.map(r => r.regionName -> r.tableConfig(simulationTicks, opLatency)).toMap,
      replicationModel = replicationModel
    )

object HotReplicaConfig:
  val UsEast     = "us-east-1"
  val EuWest     = "eu-west-1"
  val ApSoutheast = "ap-southeast-1"

  // Per-region on-demand pricing (1800 / 900 / 300 device fleets): us-east is the shared phase-1 default;
  // eu-west and ap-southeast carry their own (higher) regional rates. Matched so arm A's per-region cost
  // reconciles.
  private val UsEastRates = Pricing.phase1Default
  private val EuWestRates = Rates(
    rcuPrice                 = BigDecimal("0.000000283"),
    wcuPrice                 = BigDecimal("0.0000014"),
    storagePricePerGiBSecond = BigDecimal("0.000000108507"))
  private val ApSeRates = Rates(
    rcuPrice                 = BigDecimal("0.000000338"),
    wcuPrice                 = BigDecimal("0.000001690"),
    storagePricePerGiBSecond = BigDecimal("0.000000125"))

  /** Link lag samplers: `LogNormal(0, 1)` floored to ticks on every link (≈ 1-tick base lag) — except a
   *  modestly longer `us-east-1 → ap-southeast-1` link in the depletion arm. */
  private def uniformLag: StatelessSampler[Double]  = LogNormalSampler.constant(math.log(1.4), 0.0) // ⌊1.4⌋ = 1
  private def longerLag:  StatelessSampler[Double]  = LogNormalSampler.constant(math.log(2.6), 0.0) // ⌊2.6⌋ = 2

  private def allToAllModel(regions: Vector[String], longLink: Option[(String, String)]): ReplicationModel =
    val perLink = (for src <- regions; dst <- regions if src != dst yield
      (src, dst) -> (if longLink.contains((src, dst)) then longerLag else uniformLag)).toMap
    ReplicationModel(perLink = perLink)

  private val regionOrder = Vector(UsEast, EuWest, ApSoutheast)

  /** The healthy reconcile arm: all on-demand, reference fleets 1800 / 900 / 300. */
  def reconcileDefault(simulationTicks: Long = 600L, trialCount: Int = 100, parallelism: Int = 8): HotReplicaConfig =
    HotReplicaConfig(
      scenarioId      = "hot-replica-reconcile",
      simulationTicks = simulationTicks,
      trialCount      = trialCount,
      parallelism     = parallelism,
      regions = Vector(
        RegionConfig(UsEast,      1800L, 0.15,  BillingMode.OnDemand, UsEastRates),
        RegionConfig(EuWest,       900L, 0.075, BillingMode.OnDemand, EuWestRates),
        RegionConfig(ApSoutheast,  300L, 0.025, BillingMode.OnDemand, ApSeRates)
      ),
      replicationModel = allToAllModel(regionOrder, longLink = None)
    )

  /** The depletion showcase: 8 : 1 fleets (2000 / 250 / 300); ap-southeast-1 provisioned with an inbound rWCU
   *  ceiling below its ~2250 combined inbound, and a longer us-east-1 → ap-southeast-1 link. */
  def depletionDefault(simulationTicks: Long = 600L, trialCount: Int = 100, parallelism: Int = 8): HotReplicaConfig =
    // ap-southeast's combined inbound base rWCU ≈ (2000 + 250) devices × 0.033 reports/tick ≈ 74 rWCU/tick,
    // dominated ~8 : 1 by us-east. A 12-rWCU ceiling sits well below that, so **both** inbound links back up;
    // the fair-share drain (≈ 6 rWCU each once both saturate) leaves us-east's 66/tick arrival deeply
    // backlogged while eu-west's ~8/tick lags only mildly — the per-link distinction. The base capacity is
    // generous so the small local workload never throttles and only rWCU is the constraint.
    val apSeProvisioned = BillingMode.Provisioned(
      readCapacityUnits            = 2000L,
      writeCapacityUnits           = 1000L,
      replicatedWriteCapacityUnits = Some(12L)
    )
    HotReplicaConfig(
      scenarioId      = "hot-replica-depletion",
      simulationTicks = simulationTicks,
      trialCount      = trialCount,
      parallelism     = parallelism,
      regions = Vector(
        RegionConfig(UsEast,      2000L, 0.0, BillingMode.OnDemand, Pricing.phase1Default),
        RegionConfig(EuWest,       250L, 0.0, BillingMode.OnDemand, Pricing.phase1Default),
        RegionConfig(ApSoutheast,  300L, 0.0, apSeProvisioned,      Pricing.phase1Default)
      ),
      replicationModel = allToAllModel(regionOrder, longLink = Some((UsEast, ApSoutheast)))
    )
