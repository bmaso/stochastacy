# IPS Hand-Off

Last updated: 2026-05-02 (phase 5 slices 1–5 complete: rWCU billing, tiered transfer pricing, GSI/LSI in replicated tables, ReturnedItemCount metric, mixed-mode dashboard enhanced with percentile bands)

## Current Position

The project is a DynamoDB Monte Carlo simulator (Scala 3 / sbt / Pekko Streams). Phase 3 (on-demand fidelity) and Phase 4 (provisioned capacity mode and dynamic reconfiguration) are complete. **Phase 5** (accuracy and metric completeness) is **in progress** — slices 1–5 are shipped; slices 6–12 are future.

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
- **Tiered cross-region transfer pricing**: `CrossRegionTransferPricing` accumulates cumulative bytes per source region and applies the correct tier rate per tranche
- **Mid-simulation reconfiguration** via `DynamoDbManagementEvent`:
  - `SwitchBillingMode` — on-demand ↔ provisioned (24-hour cooldown enforced)
  - `UpdateProvisionedCapacity` — change RCU/WCU/rWCU within provisioned mode (no cooldown)
- **`ReturnedItemCount` metric** for Query and Scan: `StorageMetricEvent.ReturnedItemCount` emitted per admitted request; collected and reported in all three demo runners
- raw DynamoDB consumption events → additive usage → time-based storage usage → pricing
- Monte Carlo multi-trial execution
- JSONL export (raw + 60s/300s windowed), Postgres staging, provisioned Grafana dashboards

### Phase 5 Status

| Slice | Status | Summary |
|-------|--------|---------|
| 1. rWCU On-Demand Billing | **Done** | `ReplicatedWriteCapacityConsumed` event; separate accumulation in `DynamoDbUsageTotals`; rWCU pricing in `DynamoDbPricing` |
| 2. rWCU Provisioned Admission | **Done** | `BillingMode.Provisioned.replicatedWriteCapacityUnits: Option[Long]`; token-bucket rWCU admission in `componentOfReplicated` |
| 3. Tiered Transfer Pricing | **Done** | Tiered `CrossRegionTransferPricingRates`; per-tranche cost accumulation |
| 4. GSI/LSI in `componentOfReplicated` | **Done** | Test-completion slice (guard was already absent); new provisioned-mode + GSI rWCU test |
| 5. ReturnedItemCount Metric | **Done** | `StorageMetricEvent.ReturnedItemCount`; collected in all runners; Grafana panels in both dashboards; WCU panel now shows p50/p75/p95 bands |
| 6–12 | Future | ReplicationLatency, SystemErrors, SuccessfulRequestLatency, Table Class, Per-GSI Pricing Accuracy, Reserved Capacity, Multi-Region Demo Update |

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

`CrossRegionTransferPricingRates` maps each source region to a `Vector[TransferPricingTier]` (cumulative byte threshold + per-GiB rate, sorted ascending). `TransferPricingTier.flat(rate)` preserves backward compatibility for callers that don't need tiers.

`CrossRegionTransferPricing` accumulates cumulative bytes transferred per source region across the simulation run and applies the correct tier rate to each tranche. The entire simulation run is treated as one billing period.

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
- [DynamoDbPricing.scala](core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)
- [model.scala](core/src/main/scala/stochastacy/demo/model.scala) — `DemoMetric` enum including `ReturnedItemCount(op)`, `TotalRegionReplicatedWriteCapacityUnits`, etc.

### Demo
- [ThermostatFleetBridge.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBridge.scala) — primary demo entry point (single-region, multi-region, mixed-mode dispatch)
- [ThermostatFleetSingleTrialRunner.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunner.scala) — two-sink (single-region) and three-sink (multi-region) graph patterns; collects ReturnedItemCount
- [ThermostatFleetMixedModeBridge.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeBridge.scala) — mixed billing mode demo entry point
- [ThermostatFleetMixedModeConfig.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeConfig.scala) — config for the mixed-mode scenario
- [ThermostatFleetMixedModeSingleTrialRunner.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetMixedModeSingleTrialRunner.scala) — two-sink graph; `updateMetricAcc` handles both `AdmissionMetricEvent` and `StorageMetricEvent.ReturnedItemCount`
- [thermostat-fleet-dashboard.json](examples/grafana/thermostat-fleet-dashboard.json) — UID `ips-phase3`; includes ReturnedItemCount panel
- [thermostat-fleet-mixed-mode-dashboard.json](examples/grafana/thermostat-fleet-mixed-mode-dashboard.json) — UID `ips-phase4-mixed-mode`; includes p50/p75/p95 WCU bands and ReturnedItemCount panel

## Key Proof Tests

- [TableAdmissionStageSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableAdmissionStageSpec.scala) — billing mode switching, provisioned capacity changes, utilization/snapshot metrics, rWCU admission
- [DynamoDbTableComponentSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala) — `componentOfManaged` integration tests and metric outlet routing
- [DynamoDbTableReplicatedSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableReplicatedSpec.scala) — rWCU admission, GSI/LSI in replicated tables, provisioned-mode + GSI rWCU intersection
- [DynamoDbGlobalTableSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbGlobalTableSpec.scala) — rWCU at peer regions, GSI/LSI replicated-write scenarios
- [TableStorageStageQuerySpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageQuerySpec.scala) — asserts `ReturnedItemCount` events including zero-count results
- [TableStorageStageScanSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageScanSpec.scala) — asserts `ReturnedItemCount` events for Scan
- [ThermostatFleetSingleTrialRunnerSpec.scala](examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunnerSpec.scala) — end-to-end trial runner tests including ReturnedItemCount and multi-region GSI metrics
- All storage stage specs (GetItem, PutItem, UpdateItem, DeleteItem, Query, Scan)

Total: 261 core tests + 123 examples tests = 384 tests all passing.

## Recommended Next Work

Phase 5 slices 6–12 remain. Suggested order:

1. **Slice 6 — ReplicationLatency metric**: surface the tick-delta already computed in `ReplicationCoordinator` as `ReplicationMetricEvent.ReplicationLatency`; add a per-destination-region panel to the multi-region dashboard.
2. **Slice 7 — SystemErrors**: Bernoulli error model in `TableStorageStage`; `SystemErrorResponse`; `DemoMetric.SystemErrorCount`.
3. **Slice 8 — SuccessfulRequestLatency**: log-normal per-operation latency samples emitted from `TableStorageStage`; P50/P95/P99 rollup in demo pipeline; latency panel in dashboards.
4. **Slices 9–11 — Pricing accuracy**: Table Class (Standard vs IA), per-GSI provisioned pricing, reserved capacity discount.
5. **Slice 12 — Multi-region demo update**: update multi-region Grafana dashboard with rWCU vs WCU per region, tiered transfer cost, replication latency, and latency panels.

## Notes For A Fresh Session

- The mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- `sbt test` runs all 384 tests; `sbt "core/test"` runs the 261 core tests
- The canonical planning anchors are [ips-phase5.md](../roadmaps/ips-phase5.md) (ongoing) and [ips-phase4.md](../roadmaps/ips-phase4.md) (complete); the architecture reference is [dynamodb-table.md](../architecture/dynamodb-table.md)
- Implement one slice at a time; use plan mode for new slices
- The management event pipeline uses a shared `BillingModeRef` pattern (analogous to `TopologySnapshotRef`): management processor writes to the ref, admission stages read it at tick boundaries
- `componentOfManaged` is the factory for any table that needs mid-simulation reconfiguration; `componentOf` and `componentOfReplicated` do not accept management events
- rWCU is a destination-region concept: only `componentOfReplicated` / `componentOfManagedReplicated` handle it; single-region tables never see `ReplicatedWriteCapacityConsumed`
- When adding a new `StorageMetricEvent` subtype, update `StorageMetricTotals.scala` (test helper) to add a wildcard or explicit case — otherwise the compiler emits a fatal exhaustivity warning
