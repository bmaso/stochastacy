# IPS Hand-Off

Last updated: 2026-05-08 (phase 6 slices 1–9 complete; DynamoDB Transactions delivered)

## Current Position

The project is a DynamoDB Monte Carlo simulator (Scala 3 / sbt / Pekko Streams). Phases 1–5 are all complete. **Phase 5** (accuracy and metric completeness) shipped all planned pricing-accuracy slices (9–11, 11b, 12) plus the earlier simulation-accuracy slices (1–5). Three originally-planned Phase 5 slices (ReplicationLatency, SystemErrors, SuccessfulRequestLatency) were deferred to Phase 6.

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

Total: 303 core tests + 171 examples tests = 474 tests all passing.

## Recommended Next Work

Phase 6 — "Close the Gap" — five slices targeting features required for the final ThermoFleet
multi-service demo. The full ThermoFleet demo requires API Gateway, Lambda, SQS, DynamoDB, and
S3; Phase 6 delivers only the DynamoDB layer plus a capstone demo that exercises it. See
[ips-phase6.md](../roadmaps/ips-phase6.md) for the full spec.

1. **Read consistency RCU accounting** — DONE (verified): `TableThroughputMath` already applies 0.5× for eventually-consistent reads.
2. **TTL** — DONE: `TtlSampler`/`SimpleTtlSampler` ring-buffer; `TtlExpiry` StorageOutcome at tick boundaries; `TtlItemsExpired`+`EstimatedItemCount` metrics; `StorageBytesDelta` cascade for GSI/LSI; `DynamoDbTable.Config.ttlSampler`; 12 new tests.
3. **Reactive auto-scaling** — DONE: `DynamoDbAutoScaler` actor-based external coordinator; `Policy` config (separate scale-up/down reaction delays and cooldowns, rolling window, min/max bounds); `ThermostatFleetScenarioConfig.autoScalerPolicy` field; 3-way runner path (auto-scaler / schedule / plain); 7 new tests.
4. **Multi-table simulation framework** — DONE: composable runner for N parallel table instances with shared tick clock and per-table namespaced metrics (`Table:<name>:*`); `MultiTableScenarioConfig`, `MultiTableEntry`, `ThermostatFleetMultiTableSingleTrialRunner`.
5. **DynamoDB capstone demo** — DONE: four-table ThermoFleet-inspired simulation (Device Registry, Telemetry, Commands, Alerts); polar vortex burst (40% fleet, 5× writes); provisioned+auto-scaling on telemetry table; Grafana dashboard with per-table cost, throttle count, provisioned vs. consumed, and EstimatedItemCount panels.
6. **ReplicationLatency metric** — DONE: `ReplicationMetricEvent.ReplicationLatency` emitted by `ReplicationCoordinator` (both zero-lag and queued paths); routed to per-destination-region metric outlets in `DynamoDbGlobalTable`; collected by multi-region runner as `DemoMetric.ReplicationLatency(region)` with MAX-per-window rollup; Grafana panel in thermostat-fleet dashboard.
7. **SystemErrors** — Bernoulli error model in `TableStorageStage`; `SystemErrorResponse`; no-consumption no-state-mutation guarantee; `DemoMetric.SystemErrorCount`.
8. **SuccessfulRequestLatency** — DONE: log-normal latency samples per admitted non-errored request; `DynamoDbTable.LatencyModel` config; P50/P95/P99 rollup; latency panels in both dashboards.
9. **DynamoDB Transactions** — DONE: `TransactWriteItems` (2× WCU/item) and `TransactGetItems` (2× RCU/item, always strongly consistent); `TransactWriteItemsRequest` / `TransactGetItemsRequest` / response types in `op_events.scala`; `TransactWriteItemsSample` / `TransactGetItemsSample` sample traits; shaped and admitted types; `transactionalWriteCapacityUnitsFor` / `transactionalReadCapacityUnitsFor` in `TableThroughputMath`; all-or-nothing LSI collection limit and system-error checks; `WriteAsPutSample` adapter; `mergeFootprints` / `mergeIndexMaintenancePlans` helpers; `ThermostatFleetBehavior.transactWriteItems`; capstone Commands table uses 2-item transactions; 10 new tests.

**Remaining Phase 6 work:** Slice 10 — PITR Pricing ($0.20/GB-month for PITR-enabled tables).

## Notes For A Fresh Session

- The mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- `sbt test` runs all 474 tests; `sbt "core/test"` runs the 303 core tests
- The canonical planning anchors are [ips-phase5.md](../roadmaps/ips-phase5.md) (complete) and [ips-phase4.md](../roadmaps/ips-phase4.md) (complete); the architecture reference is [dynamodb-table.md](../architecture/dynamodb-table.md)
- Implement one slice at a time; use plan mode for new slices
- The management event pipeline uses a shared `BillingModeRef` pattern (analogous to `TopologySnapshotRef`): management processor writes to the ref, admission stages read it at tick boundaries
- `componentOfManaged` is the factory for any table that needs mid-simulation reconfiguration; `componentOf` and `componentOfReplicated` do not accept management events
- rWCU is a destination-region concept: only `componentOfReplicated` / `componentOfManagedReplicated` handle it; single-region tables never see `ReplicatedWriteCapacityConsumed`
- When adding a new `StorageMetricEvent` subtype, update `StorageMetricTotals.scala` (test helper) to add a wildcard or explicit case — otherwise the compiler emits a fatal exhaustivity warning
- `DynamoDbAutoScaler` (in `core/.../autoscaling/`) is an actor-based external controller that bridges the metric outlet and `componentOfManaged.managementIn` without forming a stream cycle. It uses `Source.queue[TimedElement[DynamoDbManagementEvent]](64).preMaterialize()` (non-deprecated `BoundedSourceQueue` API) and `Sink.actorRef` for metric ingestion. Stream completion: metric stream ends → `StreamComplete` → actor calls `queue.complete()` → management source completes → graph resolves. The runner calls `autoScaler.stop()` after the trial completes to release the actor.
- `PricingSchedule.default` is `StaticPricingSchedule(Map.empty, DynamoDbPricingRates.phase1Default)` — used by all single-region demos without any change in behavior; callers that need per-region rates construct via `PricingSchedule.byRegion(...)`
- Provisioned cost uses `provisionedReadCapacityUnitHourlyPrice`/`provisionedWriteCapacityUnitHourlyPrice` from `RateSet`, NOT the on-demand per-unit prices; the two differ by 520× for writes
