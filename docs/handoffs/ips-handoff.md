# IPS Hand-Off

Last updated: 2026-07-27 (Phase 7 complete incl. YAML DSL + visualizer; Phase 7b complete — intra-tick arrival model; Phase 8 paused; Generic Workload Layer planned)

> **Note:** sections below dated from the 2026-05-11 revision describe Phase 7 Slices 1–3 and
> have not all been revised. Where this document and `CLAUDE.md` disagree, **`CLAUDE.md` is
> authoritative** — it has been kept current. Test counts quoted below predate Phase 7b and are
> stale; run `sbt test` for the true figure.

## Current Position

The project is a DynamoDB Monte Carlo simulator (Scala 3 / sbt / Pekko Streams). Phases 1–6 are all complete. **Phase 6** (Close the Gap) shipped 10 slices covering read consistency, TTL, reactive auto-scaling, a multi-table simulation framework, a DynamoDB capstone demo, ReplicationLatency, SystemErrors, SuccessfulRequestLatency, DynamoDB Transactions, and PITR Pricing.

The simulator supports:

- base-table CRUD (`GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`)
- base-table and index-targeted `Query` and `Scan`
- a public `DynamoDbTable` table-and-indexes mono-component
- internal GSI and LSI execution units with index-state ownership and write propagation
- a decomposed pipeline: `TableSamplingStage` → `TableAdmissionStage` → `TableStorageStage` → index maintenance
- on-demand AND provisioned admission modes with billing-mode-aware throttle reasons
- hot-partition enforcement, burst-capacity rescue, adaptive-capacity rescue (on-demand only)
- dynamic partition-topology evolution at tick boundaries
- GSI write back-pressure for base-table writes
- projection-aware GSI-vs-LSI read execution
- bytes-oriented, plan-driven index maintenance for admitted writes
- LSI item-collection size limit enforcement (stochastic, no per-key state)
- Global Tables: N-region replicated table with stochastic per-link replication lag, including GSI/LSI support at every replica
- **rWCU as a distinct capacity bucket**: replicated writes at a destination region bill as `ReplicatedWriteCapacityConsumed`, not `WriteCapacityConsumed`; separate on-demand pricing and separate provisioned admission ceiling
- **Tiered cross-region transfer pricing**: `CrossRegionTransferPricing` accumulates cumulative bytes per source region and applies the correct tier rate per tranche; `multiRegionDefault` demo config uses flat-rate pricing (no free tier)
- **Mid-simulation reconfiguration** via `DynamoDbManagementEvent`:
  - `SwitchBillingMode` — on-demand ↔ provisioned (24-hour cooldown enforced)
  - `UpdateProvisionedCapacity` — change RCU/WCU/rWCU within provisioned mode (no cooldown)
- **`ReturnedItemCount` metric** for Query and Scan: `StorageMetricEvent.ReturnedItemCount` emitted per admitted request; collected and reported in all three demo runners
- **Table class** (`Standard` / `StandardInfrequentAccess`): `DynamoDbTable.TableClass` selects between rate sets; Standard-IA has higher storage and lower throughput rates; reserved capacity unavailable for Standard-IA
- **Per-GSI provisioned capacity pricing**: `ProvisionedCapacityData` carries the true sum of base-table + all GSI provisioned ticks; pricing formula sums across all entities instead of approximating with `× (1 + numGsis)`
- **Reserved capacity discount**: `ReservedCapacity` sub-config on `DynamoDbPricingRates` specifies committed RCU/WCU and discounted hourly rates; `DynamoDbCostBreakdown.price()` splits ticks between discounted and standard-rate buckets
- **Regional pricing via `PricingSchedule`**: `PricingSchedule` trait maps `(region, tick)` to `DynamoDbPricingRates`; `StaticPricingSchedule` backed by `Map[String, DynamoDbPricingRates]` + fallback; `ThermostatFleetScenarioConfig.pricingSchedule` replaces the old single `pricingRates` field
- **Correct provisioned hourly rates**: `RateSet` carries `provisionedReadCapacityUnitHourlyPrice` ($0.00013/RCU-hr) and `provisionedWriteCapacityUnitHourlyPrice` ($0.00065/WCU-hr); previously on-demand per-unit rates were used here (520× too low for writes)
- **Mixed-mode cost accounting split**: `ConsAcc` maintains `onDemandUsageTotals` (ticks ≤ `modeSwitchTick` only) alongside `usageTotals` (all ticks); pricing uses `onDemandUsageTotals` so on-demand and provisioned cost components add correctly
- **Per-region cost breakdown metrics**: `DemoMetric.TotalRegionWriteCapacityCost`, `TotalRegionReplicatedWriteCapacityCost`, `TotalRegionTransferCost` per region; stacked barchart panel in multi-region Grafana dashboard
- raw DynamoDB consumption events → additive usage → time-based storage usage → pricing
- Monte Carlo multi-trial execution
- JSONL export (raw + 60s/300s windowed), Postgres staging, provisioned Grafana dashboards

### Phase 5 Status (complete)

| Slice | Status | Summary |
|-------|--------|---------|
| 1. rWCU On-Demand Billing | **Done** | `ReplicatedWriteCapacityConsumed` event; separate accumulation in `DynamoDbUsageTotals`; rWCU pricing in `DynamoDbPricing` |
| 2. rWCU Provisioned Admission | **Done** | `BillingMode.Provisioned.replicatedWriteCapacityUnits: Option[Long]`; token-bucket rWCU admission in `componentOfReplicated` |
| 3. Tiered Transfer Pricing | **Done** | Tiered `CrossRegionTransferPricingRates`; per-tranche cost accumulation |
| 4. GSI/LSI in `componentOfReplicated` | **Done** | Test-completion slice (guard was already absent); new provisioned-mode + GSI rWCU test |
| 5. ReturnedItemCount Metric | **Done** | `StorageMetricEvent.ReturnedItemCount`; collected in all runners; Grafana panels in both dashboards; WCU panel now shows p50/p75/p95 bands |
| 6. ReplicationLatency Metric | **In Phase 6** | Surface tick-delta from `ReplicationCoordinator` as a metric; per-destination-region panel |
| 7. SystemErrors | **In Phase 6** | Bernoulli error model; `SystemErrorResponse`; `DemoMetric.SystemErrorCount` |
| 8. SuccessfulRequestLatency | **In Phase 6** | Log-normal latency samples; P50/P95/P99 rollup; latency panels |
| 9. Table Class: Standard vs. Standard-IA | **Done** | `DynamoDbTable.TableClass` sealed type; `DynamoDbPricingRates` extended with Standard-IA rate set; Standard-IA incompatible with reserved capacity |
| 10. Per-GSI Provisioned Capacity Pricing | **Done** | `ProvisionedCapacityData` carries true base+GSI sum; `× (1+numGsis)` approximation removed |
| 11. Reserved Capacity Discount | **Done** | `ReservedCapacity` sub-config; discounted/standard rate split in `DynamoDbCostBreakdown.price()`; 19 pricing tests pass |
| 11b. Regional Pricing | **Done** | `PricingSchedule` trait + `StaticPricingSchedule`; `pricingSchedule` field on both scenario configs; per-region rate resolution in both trial runners |
| 12. Multi-Region Demo Update | **Done** | Full GSI/LSI config in multi-region; 3 new per-region cost metrics; stacked cost-breakdown panel in Grafana; flat transfer pricing (removes erroneous free tier) |

### Phase 4 Status (all 7 slices complete)

| Slice | Status | Summary |
|-------|--------|---------|
| 1. BillingMode Config + Provisioned Admission | **Done** | `BillingMode` sealed type (`OnDemand` / `Provisioned`), RCU/WCU ceilings, adaptive suppression in provisioned mode |
| 2. Provisioned Capacity Pricing | **Done** | Capacity-driven pricing path in `DynamoDbPricing` |
| 3. Management Events + Billing Mode Switch | **Done** | `DynamoDbManagementEvent.SwitchBillingMode`, `componentOfManaged`, `BillingModeRef`, 24h cooldown, `BillingModeSwitched` metric |
| 4. Provisioned Capacity Change Events | **Done** | `UpdateProvisionedCapacity`, `ProvisionedCapacityChanged` metric, no-cooldown capacity updates |
| 5. Reconfiguration Schedule DSL | **Done** | `ReconfigurationSchedule`, thermostat scenario-config support, managed replicated/global table paths, schedule-driven management injection |
| 6. Utilization Metrics | **Done** | `ConsumedCapacitySnapshot`, `ProvisionedCapacityUtilization`, `BillingModeSnapshot` emitted per completed tick from `TableAdmissionStage` |
| 7. Demo Scenario + Grafana Panels | **Done** | Mixed-mode thermostat fleet: `ThermostatFleetMixedModeBridge`, `DemoMetric` cases, mixed-mode Grafana dashboard, "right-sizing trap" narrative |

## Key Architectural Concepts

### Workload System (Phase 7)

The `stochastacy.workload` package provides a composable, declarative layer for defining request streams. It is separate from and upstream of the DynamoDB table simulator.

**`Sampler[S, T]`** is the unified abstraction for both stateless and stateful value generation:
```
trait Sampler[S, T]:
  def initialState: S
  def sample(tick: Long, rng: UniformRandomProvider, state: S): (T, S)
type StatelessSampler[T] = Sampler[Unit, T]
```
The N-kinded hierarchy:
- 0-kinded (primitives): `ConstantSampler`, distribution samplers, `Sampler.deterministic(Long => T)`
- 1-kinded (tick/output transform): `MappedSampler(base, tickTransform, outputTransform)` — `outputTransform` receives the ORIGINAL tick
- 2-kinded (combining): `CombiningSampler(baseA, baseB, combineOutput)` — named constructors: `sum`, `product`, `overlay(condition: Long => Boolean)`

**`RandomBurstSampler[S]`** is the one stateful primitive. It wraps a lambda-producing `Sampler[S, Double]` and adds a stochastic burst: `burstAmount(tick)` is added to the base lambda during burst ticks, with the Poisson draw done internally. State is `(ticksRemaining: Int, innerState: S)`. Used for the thermostat fleet alert storm pattern.

**`ErasedSampler.of[S, T](sampler)`** adapts any `Sampler[S, T]` into a `StatelessSampler[T]` by managing its own state as a mutable `var`. This allows stateful rate samplers (like `RandomBurstSampler`) to satisfy `RequestShapeDefinition.rate: StatelessSampler[Int]`. Single-use, not thread-safe; `WorkloadRequestStream` guarantees single-call-per-tick semantics.

**`WorkloadDefinition`** is a declarative request-stream description:
- `RequestShape` sealed ADT encodes the request type and its typed parameters: `GetItem`, `DeleteItem`, `PutItem(itemBytes)`, `UpdateItem(itemBytes)`, `Query(target, readConsistency)`, `Scan(target, readConsistency)`, `TransactWriteItems(perItemBytes)`, `TransactGetItems(itemCount)`
- `RequestShapeDefinition(rate: StatelessSampler[Int], shape: RequestShape)` — rate is orthogonal to shape; `copy(rate = ...)` works without pattern-matching
- `WorkloadDefinition(tableName, usecase, requests)` — a table name, use-case identifier, and vector of shapes

**`WorkloadRequestStream`** produces `Iterator[TimedElement[DynamoDBRequest]]` from a `WorkloadDefinition`. Output structure is identical to the old `generateRequestsForRegion`: each tick begins with a `Tick` control event followed by all requests for that tick, plus a final `Tick(simulationTicks + 1)`. Two independent RNGs are split per shape (one for rate draws, one for parameter draws) so that changing a param sampler does not affect rate draws.

**Workload / UseCaseSampler boundary:**
- `WorkloadDefinition` / `WorkloadRequestStream` controls **arrival**: which request types arrive, at what rate, with what parameters (item size, target index, read consistency).
- `UseCaseSampler[T <: TableState]` controls **outcomes**: given an arrived request and current table state, what stochastically happened in storage (bytes read, hit/miss, partition access).
- They meet at the `DynamoDBRequest` handoff. `UseCaseSampler` is intentionally not aligned with `Sampler[S, T]` — they serve different pipeline stages with incompatible input types and different RNG lifecycle patterns.

**`ThermostatFleetScenarioConfig.toWorkloadDefinition(region: RegionFleetConfig): WorkloadDefinition`** is the translation point from scenario config scalars to composable samplers. It builds the telemetry rate as `ErasedSampler.of(RandomBurstSampler(baseLambda, ...))` where `baseLambda` encodes fleet growth + spike multipliers + polar vortex, and handles the `transactWriteItemsPerItemBytes` branch. Runners call this method and pass the result to `WorkloadRequestStream`.

### BillingMode

`DynamoDbTable.BillingMode` is a sealed trait with two subtypes:
- `OnDemand(maxThroughput: OnDemandMaxThroughput)` — AWS-managed capacity with optional per-table/GSI max throughput
- `Provisioned(readCapacityUnits: Long, writeCapacityUnits: Long, replicatedWriteCapacityUnits: Option[Long], globalSecondaryIndexReadCapacityUnits: Map[String, Long], globalSecondaryIndexWriteCapacityUnits: Map[String, Long])` — fixed RCU/WCU/rWCU ceilings; `replicatedWriteCapacityUnits` is `None` for non-replica tables, `Some(n)` for replicated tables

Defined in `DynamoDbTable.scala`.

### rWCU vs. WCU

At a Global Table destination region, every replicated write (whether it arrived via replication or was written locally) bills as **rWCU** (replicated write capacity units), not WCU. The distinction matters in both billing and admission:

- **On-demand mode**: rWCU bills at a lower rate than WCU ($0.000975 vs $0.00130 per unit). No admission ceiling; the `ReplicatedWriteCapacityConsumed` event simply uses the rWCU rate in pricing.
- **Provisioned mode**: `BillingMode.Provisioned.replicatedWriteCapacityUnits` is the rWCU ceiling. Replicated writes are checked against this ceiling independently of the WCU ceiling. Throttle reason: `DynamoDbThrottleReason.InsufficientReplicatedWriteCapacity`.

`DynamoDbUsageTotals` accumulates `replicatedWriteCapacityUnits` as a separate field alongside `writeCapacityUnits`. The pricing component applies rWCU rates from `DynamoDbPricingRates.replicatedWriteCapacityUnitPrice`.

### Tiered Cross-Region Transfer Pricing

`CrossRegionTransferPricingRates` maps each source region to a `Vector[TransferPricingTier]` (cumulative byte threshold + per-GiB rate, sorted ascending). `CrossRegionTransferPricingRates.flat(rateByRegion)` constructs a single-tier (no free tier) pricing schedule for callers that don't need tiers.

`CrossRegionTransferPricing` accumulates cumulative bytes transferred per source region across the simulation run and applies the correct tier rate to each tranche. The entire simulation run is treated as one billing period.

`multiRegionDefault` uses flat-rate pricing (`CrossRegionTransferPricingRates.flat`). There is no free tier — AWS Global Tables charges for all replicated bytes from byte zero.

### PricingSchedule

`PricingSchedule` (in `core/src/main/scala/stochastacy/aws/dynamodb/pricing/PricingSchedule.scala`) maps `(region: String, tick: Long)` to a `DynamoDbPricingRates` instance. The default implementation is `StaticPricingSchedule(ratesByRegion: Map[String, DynamoDbPricingRates], fallback: DynamoDbPricingRates)`, which ignores the tick parameter and performs a region-keyed map lookup.

Factory methods:
- `PricingSchedule.default` — uniform `DynamoDbPricingRates.phase1Default` for all regions
- `PricingSchedule.uniform(rates)` — same rate set for all regions
- `PricingSchedule.byRegion(map, fallback)` — per-region rates; falls back for unmapped regions

`ThermostatFleetScenarioConfig` and `ThermostatFleetMixedModeConfig` carry `pricingSchedule: PricingSchedule = PricingSchedule.default`. Both single-trial runners resolve rates at trial completion via `config.pricingSchedule.ratesAt(region, simulationTicks)`. Aggregate (cross-region total) costs use `pricingSchedule.defaultRates`.

### Provisioned Capacity Hourly Rates

`DynamoDbPricingRates.RateSet` carries two distinct hourly prices used in the provisioned cost formula:
- `provisionedReadCapacityUnitHourlyPrice: BigDecimal` (default `$0.00013/RCU-hr`)
- `provisionedWriteCapacityUnitHourlyPrice: BigDecimal` (default `$0.00065/WCU-hr`)

The pricing formula: `capacity_unit_ticks × hourlyRate / 3600`. These are 520× higher than the on-demand per-unit rates ($0.00000025/RCU, $0.00000125/WCU), which is why using the on-demand rate for provisioned ticks was a critical bug (cost was 520× too low for writes).

### Mixed-Mode Cost Accounting

`ThermostatFleetMixedModeSingleTrialRunner`'s `ConsAcc` maintains two parallel usage accumulators:
- `usageTotals: DynamoDbUsageTotals` — ALL consumed units across all ticks (used for usage summary metrics like `TotalWriteCapacityUnits`)
- `onDemandUsageTotals: DynamoDbUsageTotals` — only units consumed during on-demand ticks (ticks ≤ `modeSwitchTick`)

`DynamoDbPricingInputs` uses `onDemandUsageTotals` for the `usage` field and `provisionedCapacity` carries the provisioned ticks. `DynamoDbCostBreakdown.price()` adds both components together, giving the correct total cost for a mixed billing-mode simulation.

### Reserved Capacity Discount

`ReservedCapacity` is an optional sub-config on `DynamoDbPricingRates` specifying:
- `reservedReadCapacityUnits` / `reservedWriteCapacityUnits` — the committed provisioned capacity
- `discountedReadCapacityUnitPrice` / `discountedWriteCapacityUnitPrice` — the discounted hourly rate per tick

`DynamoDbCostBreakdown.price()` splits provisioned ticks into discounted (up to reserved units) and standard-rate tranches. Validation enforces: reserved capacity requires provisioned billing mode and `TableClass.Standard` (not Standard-IA).

### ReturnedItemCount Metric

`StorageMetricEvent.ReturnedItemCount(eventTime, usecase, operation: DynamoDbOperationKind, count: Long)` is emitted by `TableStorageStage` once per admitted Query or Scan, including zero-count results. `DynamoDbOperationKind.Query` and `.Scan` distinguish the two operation types; `.toString` produces the strings `"Query"` and `"Scan"` used as `DemoMetric.ReturnedItemCount(op)` parameter values.

All three demo runners collect and emit this metric:
- `ThermostatFleetSingleTrialRunner` — single-region path uses a two-sink graph (consumption + metric); multi-region path uses a three-sink graph (tagged-consumption + transfer + tagged-metric)
- `ThermostatFleetMixedModeSingleTrialRunner` — handles `StorageMetricEvent.ReturnedItemCount` in `updateMetricAcc`, accumulates `retItemByOpAndTick`, appends `retItemTimeSeries` to the `TrialResult`

### componentOfManaged

`DynamoDbTable.componentOfManaged(config)` is a graph factory with two inlets:
- `requestIn` — normal `DynamoDBRequest` traffic
- `managementIn` — `DynamoDbManagementEvent` stream (billing mode switches, capacity changes)

It creates a shared `BillingModeRef` and passes it to all admission stage branches. A management processor flow validates events (cooldown, mode checks) and updates the ref; admission stages read the ref at tick boundaries.

### BillingModeRef

`BillingModeRef` (in `shaped_request.scala`) is a shared mutable reference with `@volatile` fields:
- `currentMode: DynamoDbTable.BillingMode` — written by the management processor, read by admission stages
- `lastSwitchTick: Option[Long]` — tracks 24h cooldown for billing mode switches only (capacity changes don't touch this)

### Per-Tick Snapshot Events

At each tick boundary in `advanceToShaped`, after resetting `usageState`, the admission stage emits three snapshot events for the just-completed tick:

- **`ConsumedCapacitySnapshot`** — total RCU and WCU consumed during the tick. Always emitted (both billing modes).
- **`ProvisionedCapacityUtilization`** — consumed + provisioned ceiling. Emitted only when the tick ran under `BillingMode.Provisioned`.
- **`BillingModeSnapshot`** — integer mode code (0 = on-demand, 1 = provisioned). Always emitted.

### Mixed-Mode Demo ("The Right-Sizing Trap")

`ThermostatFleetMixedModeBridge` runs the thermostat fleet workload in a single region with a billing mode reconfiguration schedule:

1. **On-demand phase** (ticks 1–400): no capacity ceiling; workload consumes ~3,800 WCU/tick mean
2. **Provisioned — initial capacity** (ticks 400–800): switch to provisioned at 4,200 WCU (≈110% of mean, well below the 2× morning-spike peak of ~7,600 WCU); throttles fire during every morning spike
3. **Provisioned — adjusted capacity** (ticks 800–1200): scale up to 12,500 WCU; throttles disappear; evening spike absorbed without shedding

The "Write Capacity: Consumed vs. Provisioned" panel shows mean, P50, P75, and P95 percentile bands (computed across trials) alongside the provisioned ceiling. The key insight: consumed WCU appears well below provisioned even during the throttling phase. This is because throttled requests never consume capacity — they are rejected at admission. The percentile bands reveal the full demand distribution: if P75 or P95 exceeds the provisioned line, 25% or 5% of trials are being throttled, respectively. Alert storms cause heavy right-skew in the WCU distribution, so the mean floats above P50; P75 and P95 are the more reliable planning anchors.

## Key Code Locations

### Workload System (Phase 7)
- [Sampler.scala](core/src/main/scala/stochastacy/workload/Sampler.scala) — `Sampler[S, T]` trait, `StatelessSampler[T]` alias, `stateless` / `deterministic` constructors
- [DistributionSamplers.scala](core/src/main/scala/stochastacy/workload/DistributionSamplers.scala) — 7 distribution samplers + `ConstantSampler`
- [SamplerCombinators.scala](core/src/main/scala/stochastacy/workload/SamplerCombinators.scala) — `MappedSampler`, `CombiningSampler` with `sum`/`product`/`overlay`
- [TemporalShapeFunctions.scala](core/src/main/scala/stochastacy/workload/TemporalShapeFunctions.scala) — `sinusoid`, `linearFactor`, `triangularFactor`, `weekdays`
- [RandomBurstSampler.scala](core/src/main/scala/stochastacy/workload/RandomBurstSampler.scala) — stateful burst pattern; lambda-space additive burst; output `Int`
- [ErasedSampler.scala](core/src/main/scala/stochastacy/workload/ErasedSampler.scala) — adapts `Sampler[S, T]` → `StatelessSampler[T]` via mutable state
- [WorkloadDefinition.scala](core/src/main/scala/stochastacy/workload/WorkloadDefinition.scala) — `RequestShape` ADT, `RequestShapeDefinition`, `WorkloadDefinition`
- [WorkloadRequestStream.scala](core/src/main/scala/stochastacy/workload/WorkloadRequestStream.scala) — stream generator; two RNGs per shape; `Tick`-framed output

### Core Table Simulator
- [DynamoDbTable.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala) — public component (`componentOf`, `componentOfReplicated`, `componentOfManaged`), `BillingMode` sealed type
- [TableSamplingStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableSamplingStage.scala) — sampling/shaping stage
- [TableAdmissionStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala) — admission/throttling with dynamic billing mode support
- [TableStorageStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala) — storage execution stage; emits `ReturnedItemCount`
- [metric_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/metric_events.scala) — `StorageMetricEvent` variants including `ReturnedItemCount`
- [table_metric_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/table_metric_events.scala) — `AdmissionMetricEvent` variants including `BillingModeSwitched`, `ConsumedCapacitySnapshot`, `ProvisionedCapacityUtilization`, `BillingModeSnapshot`
- [management_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/management_events.scala) — `DynamoDbManagementEvent` (`SwitchBillingMode`, `UpdateProvisionedCapacity`)
- [shaped_request.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/shaped_request.scala) — `ShapedRequest`, `TopologySnapshotRef`, `BillingModeRef`
- [op_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala) — DynamoDB request/response types

### Global Tables / Replication
- [DynamoDbGlobalTable.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbGlobalTable.scala) — N-region global table factory
- [ReplicationCoordinator.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/ReplicationCoordinator.scala) — cross-region replication coordinator; emits `ReplicatedWriteCapacityConsumed`
- [CrossRegionTransferEvent.scala](core/src/main/scala/stochastacy/aws/transfer/CrossRegionTransferEvent.scala) — generic cross-region transfer events
- [CrossRegionTransferPricing.scala](core/src/main/scala/stochastacy/aws/transfer/CrossRegionTransferPricing.scala) — tiered per-source-region transfer cost

### Downstream Pipeline
- [DynamoDbUsageTotals.scala](core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala) — accumulates WCU and rWCU as separate fields
- [DynamoDbPricing.scala](core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala) — `DynamoDbCostBreakdown.price()`, `RateSet` with provisioned hourly rates, `ReservedCapacity`, `DynamoDbPricingRates`
- [PricingSchedule.scala](core/src/main/scala/stochastacy/aws/dynamodb/pricing/PricingSchedule.scala) — `PricingSchedule` trait, `StaticPricingSchedule`, factory methods
- [model.scala](core/src/main/scala/stochastacy/demo/model.scala) — `DemoMetric` enum including `ReturnedItemCount(op)`, `TotalRegionReplicatedWriteCapacityUnits`, `TotalRegionWriteCapacityCost`, `TotalRegionReplicatedWriteCapacityCost`, `TotalRegionTransferCost`, etc.

### Demo
- [ThermostatFleetBridge.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBridge.scala) — primary demo entry point (single-region, multi-region, mixed-mode dispatch)
- [ThermostatFleetScenarioConfig.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetScenarioConfig.scala) — scenario config; `singleRegionDefault` and `multiRegionDefault` presets with `pricingSchedule`
- [ThermostatFleetSingleTrialRunner.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunner.scala) — two-sink (single-region) and three-sink (multi-region) graph patterns; collects ReturnedItemCount; resolves rates via `pricingSchedule`
- [ThermostatFleetMixedModeBridge.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeBridge.scala) — mixed billing mode demo entry point
- [ThermostatFleetMixedModeConfig.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeConfig.scala) — config for the mixed-mode scenario; carries `pricingSchedule`
- [ThermostatFleetMixedModeSingleTrialRunner.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeSingleTrialRunner.scala) — `ConsAcc` with `usageTotals` + `onDemandUsageTotals` split; billing mode timeline from config schedule
- [thermostat-fleet-dashboard.json](examples/grafana/thermostat-fleet-dashboard.json) — UID `ips-phase3`; includes ReturnedItemCount panel and per-region cost breakdown barchart
- [thermostat-fleet-mixed-mode-dashboard.json](examples/grafana/thermostat-fleet-mixed-mode-dashboard.json) — UID `ips-phase4-mixed-mode`; includes p50/p75/p95 WCU bands and ReturnedItemCount panel

## Key Proof Tests

- [TableAdmissionStageSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableAdmissionStageSpec.scala) — billing mode switching, provisioned capacity changes, utilization/snapshot metrics, rWCU admission
- [DynamoDbTableComponentSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala) — `componentOfManaged` integration tests and metric outlet routing
- [DynamoDbTableReplicatedSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableReplicatedSpec.scala) — rWCU admission, GSI/LSI in replicated tables, provisioned-mode + GSI rWCU intersection
- [DynamoDbGlobalTableSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbGlobalTableSpec.scala) — rWCU at peer regions, GSI/LSI replicated-write scenarios
- [DynamoDbPricingSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricingSpec.scala) — 19 tests: provisioned hourly rates, reserved capacity rate split, table class rates, mixed on-demand+provisioned cost, Standard-IA validation
- [PricingScheduleSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/pricing/PricingScheduleSpec.scala) — 5 tests: uniform schedule, per-region lookup, fallback for unknown region, `defaultRates`, tick-invariance
- [TableStorageStageQuerySpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageQuerySpec.scala) — asserts `ReturnedItemCount` events including zero-count results
- [ThermostatFleetSingleTrialRunnerSpec.scala](examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunnerSpec.scala) — end-to-end trial runner tests including ReturnedItemCount and multi-region GSI metrics
- [ThermostatFleetMixedModeSingleTrialRunnerSpec.scala](examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeSingleTrialRunnerSpec.scala) — reserved capacity test uses `PricingSchedule.uniform(reservedRates)`
- [TransactionSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TransactionSpec.scala) — 8 tests: `TransactWriteItems` 2× WCU billing, LSI all-or-nothing rejection, system-error no-consumption, state mutation per item; `TransactGetItems` 2× strongly-consistent RCU billing, system-error path
- [ThermostatFleetCapstoneTransactionSpec.scala](examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetCapstoneTransactionSpec.scala) — 2 tests: capstone Commands table config uses transactions; end-to-end WCU emission from transactional writes
- All storage stage specs (GetItem, PutItem, UpdateItem, DeleteItem, Query, Scan)

- [PITRPricingSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/pricing/PITRPricingSpec.scala) — 6 tests: pitrStorageByteTicks accumulation, zero PITR without events, proportional byte-ticks, zero pitrCost when no PITR events, correct per-GiB cost for 1 GiB/30-day, pitrCost in totalCost
- PITR integration via `ThermostatFleetCapstoneSingleTrialRunnerSpec` — 2 new tests: PITR-enabled DeviceTelemetry emits `TablePITRCumulativeCost`; PITR-disabled tables emit 0

Total: 317 core tests + 173 examples tests = 490 tests all passing.

## Recommended Next Work

**Generic Workload Layer.** Phase 7 (all 5 slices) and Phase 7b are complete; Phase 8 (EAS burst
simulator) is paused and stashed. The active work is making the workload arrival layer polymorphic
over request type.

**Read [generic-workload-layer.md](../specs/generic-workload-layer.md) first** — it carries the full
six-slice plan, the naming rationale, the scope boundary, and four open questions (two already
resolved with recorded findings).

Motivation: the `stochastacy.workload` package is two things bolted together. `Sampler[S, T]` and
everything built on it has zero AWS imports and is reusable as-is; `RequestShape` /
`RequestShapeDefinition` / `WorkloadDefinition` / `WorkloadRequestStream` are hard-typed to
`DynamoDBRequest`. An out-of-repo consumer cannot add cases to a sealed `RequestShape`, so the only
way to model a non-DynamoDB workload today is to reimplement the arrival protocol — `Tick` framing,
the `EndOfTime` sentinel, three-RNG discipline, intra-tick draw — which is exactly the part that is
easy to get subtly wrong.

Target vocabulary (three names, one job each):

| New | Replaces | Role |
|-----|----------|------|
| `RequestFactory[Req <: TimedEvent]` | `RequestShape` (bound form) | Mints one request; `build` returns `Req`, not `TimedElement[Req]` |
| `RatedRequestFactory[Req]` | `RequestShapeDefinition` | A factory that also knows its arrival rate; **extends** `RequestFactory[Req]` |
| `WorkloadFlow[Req]` | `FlowDefinition` | One named flow; `Workload*` prefix also disambiguates from Pekko's `Flow` |

Explicitly **out of scope**: `FollowOnTransformerStage`, `WorkloadGraph`, and the YAML DSL
(`WorkloadDsl` / `WorkloadFile` / `WorkloadTemplate` / `TemplateShape`) all stay DynamoDB-specific.
Derived flows key off simulator response outcomes; a workload-only consumer has no outcomes to
observe and gets **independent flows only**.

Start with slice 1 (introduce `RequestFactory`, move construction onto the shapes, delete
`WorkloadRequestStream.buildRequest`). It is provably test-neutral — `grep -rn buildRequest
core/src/test/` returns nothing, so if a test needs editing during slice 1, behaviour changed and
something is wrong.

### Phase 7 Status (complete — all 5 slices)

| Slice | Status | Summary |
|-------|--------|---------|
| 1. Core sampler hierarchy | **Done** | `Sampler[S, T]` trait; `StatelessSampler[T]` alias; 7 distribution samplers; `MappedSampler` / `CombiningSampler` combinators; `TemporalShapeFunctions`; `RandomBurstSampler`; `ErasedSampler` |
| 2. Workload definition model | **Done** | `RequestShape` sealed ADT; `RequestShapeDefinition`; `WorkloadDefinition`; `WorkloadRequestStream` generator |
| 3. Demo migration | **Done** | All runners use `WorkloadRequestStream`; `toWorkloadDefinition(region)` on `ThermostatFleetScenarioConfig`; `generateRequestsForRegion` deleted |
| 4. YAML DSL | **Done** | `WorkloadDsl` / `WorkloadEvaluator` / `WorkloadFile` / `WorkloadTemplate`; separate `TemplateShape` ADT as the parsed form |
| 5. Workload visualizer | **Done** | `visualizer` module |

### Phase 7b Status (complete)

Intra-tick arrival model. `intraTick: Double ∈ [0.0, 1.0)` on the `TimedEvent` trait (concrete `val`,
default `0.0` — so consumption/metric events inherit it without constructor changes).
`WorkloadRequestStream` draws arrival positions from `Uniform(0,1)` with an RNG independent of the
rate and param RNGs. `TableStorageStage` samples latency **once** per admitted request and threads it
through `StorageAdmitted(sample, latencyMs)` so the same draw feeds both the
`SuccessfulRequestLatency` metric and the response's `intraTick`/`eventTime`. Response timing:
`rawOffset = req.intraTick + latencyMs / (tickDurationSeconds * 1000)`. `ThrottledResponse.intraTick`
stays `0.0`, deferred.

Test helper: `stochastacy.test.clearTiming` (in `core/src/test/.../test/stream_assertions.scala`)
zeroes `intraTick` on any DynamoDB response so tests can compare response objects by value without
latency noise. Apply at comparison sites, not in production code.

### Phase 6 Status (complete — all 10 slices)

1. **Read consistency RCU accounting** — DONE: `TableThroughputMath` applies 0.5× for eventually-consistent reads.
2. **TTL** — DONE: `TtlSampler`/`SimpleTtlSampler` ring-buffer; `TtlExpiry` StorageOutcome; `TtlItemsExpired`+`EstimatedItemCount` metrics; 12 new tests.
3. **Reactive auto-scaling** — DONE: `DynamoDbAutoScaler` actor-based external coordinator; `Policy` config; `ThermostatFleetScenarioConfig.autoScalerPolicy`; 7 new tests.
4. **Multi-table simulation framework** — DONE: `MultiTableScenarioConfig`, `MultiTableEntry`, `ThermostatFleetMultiTableSingleTrialRunner`; per-table namespaced metrics (`Table:<name>:*`).
5. **DynamoDB capstone demo** — DONE: four-table simulation (Device Registry, Telemetry, Commands, Alerts); polar vortex burst; provisioned+auto-scaling on Telemetry; capstone Grafana dashboard.
6. **ReplicationLatency metric** — DONE: emitted by `ReplicationCoordinator`; `DemoMetric.ReplicationLatency(region)` with MAX-per-window rollup; Grafana panel.
7. **SystemErrors** — DONE: Bernoulli error model; `SystemErrorResponse`; `DemoMetric.SystemErrorCount`.
8. **SuccessfulRequestLatency** — DONE: log-normal latency samples; P50/P95/P99 rollup; latency panels in both dashboards.
9. **DynamoDB Transactions** — DONE: `TransactWriteItems` (2× WCU/item) and `TransactGetItems` (2× RCU/item); all-or-nothing LSI check; capstone Commands table uses 2-item transactions; 10 new tests.
10. **PITR Pricing** — DONE: `pointInTimeRecoveryEnabled` on `DynamoDbTable.Config`; `TogglePITR` management event; `PITRStateRef`; `PITRStorageBytesDelta`; `pitrCost` in `DynamoDbCostBreakdown`; `DemoMetric.TablePITRCumulativeCost(tableName)`; DeviceTelemetry opts in; 8 new tests.

**Note:** Slices 9 (Transactions) and 10 (PITR Pricing) passed all tests but were never visually verified by running the full `generate → stage → view` pipeline and inspecting Grafana output. The Grafana capstone dashboard also has no PITR cost panel.

## Notes For A Fresh Session

- The mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- `sbt test` runs all 490 tests; `sbt "core/test"` runs the 317 core tests
- The canonical next-work anchor is [ips-phase7.md](../roadmaps/ips-phase7.md) (Slice 4 — YAML DSL is next); earlier phases ([ips-phase5.md](../roadmaps/ips-phase5.md), [ips-phase6.md](../roadmaps/ips-phase6.md)) are complete; the architecture reference is [dynamodb-table.md](../architecture/dynamodb-table.md)
- The workload system (`stochastacy.workload`) is separate from the table simulator. `WorkloadRequestStream` produces `DynamoDBRequest` events; `UseCaseSampler` controls what happens to those requests inside the table. Do not conflate them.
- `ErasedSampler` is intentionally mutable: it manages stateful sampler state across ticks. It is correct only when `sample` is called once per tick in order — `WorkloadRequestStream` guarantees this.
- `ThermostatFleetScenarioConfig.toWorkloadDefinition(region)` is the only place where config scalars are translated into composed samplers. Runners do not construct samplers directly.
- Implement one slice at a time; use plan mode for new slices
- The management event pipeline uses a shared `BillingModeRef` pattern (analogous to `TopologySnapshotRef`): management processor writes to the ref, admission stages read it at tick boundaries
- `componentOfManaged` is the factory for any table that needs mid-simulation reconfiguration; `componentOf` and `componentOfReplicated` do not accept management events
- rWCU is a destination-region concept: only `componentOfReplicated` / `componentOfManagedReplicated` handle it; single-region tables never see `ReplicatedWriteCapacityConsumed`
- When adding a new `StorageMetricEvent` subtype, update `StorageMetricTotals.scala` (test helper) to add a wildcard or explicit case — otherwise the compiler emits a fatal exhaustivity warning
- `DynamoDbAutoScaler` (in `core/.../autoscaling/`) is an actor-based external controller that bridges the metric outlet and `componentOfManaged.managementIn` without forming a stream cycle. It uses `Source.queue[TimedElement[DynamoDbManagementEvent]](64).preMaterialize()` (non-deprecated `BoundedSourceQueue` API) and `Sink.actorRef` for metric ingestion. Stream completion: metric stream ends → `StreamComplete` → actor calls `queue.complete()` → management source completes → graph resolves. The runner calls `autoScaler.stop()` after the trial completes to release the actor.
- `PricingSchedule.default` is `StaticPricingSchedule(Map.empty, DynamoDbPricingRates.phase1Default)` — used by all single-region demos without any change in behavior; callers that need per-region rates construct via `PricingSchedule.byRegion(...)`
- Provisioned cost uses `provisionedReadCapacityUnitHourlyPrice`/`provisionedWriteCapacityUnitHourlyPrice` from `RateSet`, NOT the on-demand per-unit prices; the two differ by 520× for writes
- The workload arrival layer is mid-redesign — see [generic-workload-layer.md](../specs/generic-workload-layer.md) before touching `WorkloadDefinition.scala` or `WorkloadRequestStream.scala`. `buildRequest` has **three** callers (`WorkloadRequestStream:50`, `FollowOnTransformerStage:98`, `WorkloadGraph:277`), not one
- `TemplateShape` (DSL parsed form) and `RequestShape` (bound form) are distinct ADTs. All exhaustive matching in `WorkloadEvaluator` and `WorkloadDslSpec` targets `TemplateShape`; the only exhaustive match on `RequestShape` is `buildRequest`. This is why the bound form can become an open trait without breaking tooling
- `sbt core/publishM2` publishes core to `~/.m2` as `com.bmaso:stochastacy_3:0.1.0-SNAPSHOT` for standalone downstream projects. Root has `publish / skip := true`, so scope to `core`

## Known Open Issues

- **Grafana dashboard time range.** After adding `--start-time` to the order-tracking `generate`
  command (default: midnight 2026-05-01 US/Pacific = epoch `1777618800`), the dashboard renders
  correctly only when the time picker starts at "now" — the intended 2026-05-01 default window
  shows no data. Unresolved. The tick→epoch offset is applied in
  `OrderTrackingPhase2DemoRunner.applyTickOffset`, which shifts `tick` on per-tick records and
  `windowStartTick` on windowed records; summary records are passed through. Suspect either the
  staged Postgres rows or the dashboard's `time.from`/`to` are not both in the shifted domain.
- **Phase 6 slices 9 & 10 never visually verified.** Transactions and PITR Pricing pass all tests
  but were never checked through a full `generate → stage → view` cycle; the capstone Grafana
  dashboard still has no PITR cost panel.
