# IPS Hand-Off

Last updated: 2026-04-28 (phase 4 slices 1–6 complete: provisioned mode + management events + schedule DSL + utilization metrics)

## Current Position

The project is a DynamoDB Monte Carlo simulator (Scala 3 / sbt / Pekko Streams). Phase 3 (on-demand fidelity) is complete. **Phase 4** (provisioned capacity mode and dynamic reconfiguration) is in progress — **slices 1–6 are shipped**, slice 7 remains.

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
- Global Tables: N-region replicated table with stochastic per-link replication lag
- **Mid-simulation reconfiguration** via `DynamoDbManagementEvent`:
  - `SwitchBillingMode` — on-demand ↔ provisioned (24-hour cooldown enforced)
  - `UpdateProvisionedCapacity` — change RCU/WCU within provisioned mode (no cooldown)
- raw DynamoDB consumption events → additive usage → time-based storage usage → pricing
- Monte Carlo multi-trial execution
- JSONL export (raw + 60s/300s windowed), Postgres staging, provisioned Grafana dashboards

### Phase 4 Status (slices 1–6 complete)

| Slice | Status | Summary |
|-------|--------|---------|
| 1. BillingMode Config + Provisioned Admission | **Done** | `BillingMode` sealed type (`OnDemand` / `Provisioned`), RCU/WCU ceilings, adaptive suppression in provisioned mode |
| 2. Provisioned Capacity Pricing | **Done** | Capacity-driven pricing path in `DynamoDbPricing` |
| 3. Management Events + Billing Mode Switch | **Done** | `DynamoDbManagementEvent.SwitchBillingMode`, `componentOfManaged`, `BillingModeRef`, 24h cooldown, `BillingModeSwitched` metric |
| 4. Provisioned Capacity Change Events | **Done** | `UpdateProvisionedCapacity`, `ProvisionedCapacityChanged` metric, no-cooldown capacity updates |
| 5. Reconfiguration Schedule DSL | **Done** | `ReconfigurationSchedule`, thermostat scenario-config support, managed replicated/global table paths, schedule-driven management injection |
| 6. Utilization Metrics | **Done** | `ConsumedCapacitySnapshot`, `ProvisionedCapacityUtilization`, `BillingModeSnapshot` emitted per completed tick from `TableAdmissionStage` |
| 7. Demo Scenario + Grafana Panels | Not started | Mixed-mode thermostat fleet preset, capacity utilization panels |

## Key Architectural Concepts (Phase 4)

### BillingMode

`DynamoDbTable.BillingMode` is a sealed trait with two subtypes:
- `OnDemand(maxThroughput: OnDemandMaxThroughput)` — AWS-managed capacity with optional per-table/GSI max throughput
- `Provisioned(readCapacityUnits: Long, writeCapacityUnits: Long, globalSecondaryIndexReadCapacityUnits: Map[String, Long], globalSecondaryIndexWriteCapacityUnits: Map[String, Long])` — fixed RCU/WCU ceilings

Defined in `DynamoDbTable.scala` (lines 37–59).

### componentOfManaged

`DynamoDbTable.componentOfManaged(config)` is a graph factory with two inlets:
- `requestIn` — normal `DynamoDBRequest` traffic
- `managementIn` — `DynamoDbManagementEvent` stream (billing mode switches, capacity changes)

It creates a shared `BillingModeRef` and passes it to all admission stage branches. A management processor flow validates events (cooldown, mode checks) and updates the ref; admission stages read the ref at tick boundaries.

### BillingModeRef

`BillingModeRef` (in `shaped_request.scala`) is a shared mutable reference with `@volatile` fields:
- `currentMode: DynamoDbTable.BillingMode` — written by the management processor, read by admission stages
- `lastSwitchTick: Option[Long]` — tracks 24h cooldown for billing mode switches only (capacity changes don't touch this)

### Metric Events for Reconfiguration

- `AdmissionMetricEvent.BillingModeSwitched(previousMode, newMode)` — emitted for true on-demand ↔ provisioned transitions
- `AdmissionMetricEvent.ProvisionedCapacityChanged(previousCapacity, newCapacity)` — emitted for Provisioned→Provisioned capacity updates (both typed as `BillingMode.Provisioned`)

The admission stage distinguishes these by pattern-matching `(previousMode, newMode)` in the billing mode check block of `advanceToShaped`.

### Response for Rejected Reconfiguration

`ReconfigurationRejectedResponse(eventTime, usecase, reason)` — emitted by the management processor when:
- A billing mode switch violates the 24-hour cooldown
- An `UpdateProvisionedCapacity` is attempted while in on-demand mode

## Key Code Locations

### Core Table Simulator
- [DynamoDbTable.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala) — public component (`componentOf`, `componentOfReplicated`, `componentOfManaged`), `BillingMode` sealed type
- [TableSamplingStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableSamplingStage.scala) — sampling/shaping stage
- [TableAdmissionStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala) — admission/throttling with dynamic billing mode support
- [TableStorageStage.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala) — storage execution stage
- [management_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/management_events.scala) — `DynamoDbManagementEvent` (`SwitchBillingMode`, `UpdateProvisionedCapacity`)
- [DynamoDbTableManagedShape.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTableManagedShape.scala) — custom Shape for `componentOfManaged`
- [shaped_request.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/shaped_request.scala) — `ShapedRequest`, `TopologySnapshotRef`, `BillingModeRef`
- [table_metric_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/table_metric_events.scala) — `AdmissionMetricEvent` variants including `BillingModeSwitched`, `ProvisionedCapacityChanged`, `ConsumedCapacitySnapshot`, `ProvisionedCapacityUtilization`, `BillingModeSnapshot`
- [op_events.scala](core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala) — DynamoDB request/response types including `ReconfigurationRejectedResponse`

### Global Tables / Replication
- [DynamoDbGlobalTable.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbGlobalTable.scala) — N-region global table factory
- [ReplicationCoordinator.scala](core/src/main/scala/stochastacy/aws/dynamodb/table/ReplicationCoordinator.scala) — cross-region replication coordinator
- [CrossRegionTransferEvent.scala](core/src/main/scala/stochastacy/aws/transfer/CrossRegionTransferEvent.scala) — generic cross-region transfer events

### Downstream Pipeline
- [DynamoDbUsageTotals.scala](core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)

### Demo
- [ThermostatFleetBridge.scala](examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBridge.scala) — primary demo entry point
- [OrderTrackingPhase2Demo.scala](examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2Demo.scala) — baseline demo

## Key Proof Tests

- [TableAdmissionStageSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/TableAdmissionStageSpec.scala) — 37 tests including billing mode switching, provisioned capacity changes, and utilization/snapshot metrics
- [DynamoDbTableComponentSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala) — 29 tests including `componentOfManaged` integration tests and metric outlet routing
- [DynamoDbTableConfigSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableConfigSpec.scala)
- [LsiItemCollectionLimitSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/LsiItemCollectionLimitSpec.scala)
- [DynamoDbTableReplicatedSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableReplicatedSpec.scala)
- [ReplicationCoordinatorSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/table/ReplicationCoordinatorSpec.scala)
- All storage stage specs (GetItem, PutItem, UpdateItem, DeleteItem, Query, Scan)
- [TableStorageStagePricingIntegrationSpec.scala](core/src/test/scala/stochastacy/aws/dynamodb/pricing/TableStorageStagePricingIntegrationSpec.scala)

Total: 250 core tests + 89 examples tests = 339 tests all passing.

## Key Architectural Concepts (Phase 4 Slice 6)

### Per-Tick Snapshot Events

At each tick boundary in `advanceToShaped`, after resetting `usageState`, the admission stage emits three snapshot events for the just-completed tick:

- **`ConsumedCapacitySnapshot`** — total RCU and WCU consumed during the tick. Always emitted (both billing modes).
- **`ProvisionedCapacityUtilization`** — consumed + provisioned ceiling. Emitted only when the tick ran under `BillingMode.Provisioned`. Uses `completedTickBillingMode` (mode captured before the billing-mode switch block runs), so the ceiling reported is always the one that was actually in effect during the tick.
- **`BillingModeSnapshot`** — integer mode code (0 = on-demand, 1 = provisioned). Always emitted. Uses `currentBillingMode` after the billing-mode switch block, so it reflects the mode going into the next tick.

No snapshot is emitted before the first tick completes (`tickWasCompleted` flag guards this).

All three event types route through `metricFlow` in `componentOfShaped`; `admittedFlow` and `responseFlow` drop them.

## Recommended Next Work

### Immediate: Phase 4 Slice 7 — Demo Scenario and Grafana Panels

Wire the three new slice-6 metric events through the demo pipeline into Grafana. Add `DemoMetric` cases for `ConsumedReadCapacityUnits`, `ConsumedWriteCapacityUnits`, `ProvisionedReadCapacityUnits`, `ProvisionedWriteCapacityUnits`, and `BillingModeIndicator`. Add rollup routing and update `ThermostatFleetSingleTrialRunner` to consume the metric outlet. Mixed-mode thermostat fleet preset: on-demand for the first third, switch to provisioned at ~110% of observed mean, optionally adjust at 2/3 mark. New Grafana rows: Capacity Utilization, Billing Mode Timeline, Cost Composition.

### Deferred (from phase 3 follow-ons)

- **rWCU as distinct capacity bucket** — replicated writes currently bill as WCU; AWS bills as rWCU
- **Tiered cross-region transfer pricing** — slice 10 uses flat rates; real AWS uses tiered
- **GSI/LSI inside replicated tables** — `componentOfReplicated` currently rejects configs with secondary indexes

## Notes For A Fresh Session

- The mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- `sbt test` runs all 339 tests; `sbt "core/test"` runs the 250 core tests
- The canonical planning anchor is [ips-phase4.md](../roadmaps/ips-phase4.md); the architecture reference is [dynamodb-table.md](../architecture/dynamodb-table.md)
- Phase-4 slices should be implemented one at a time
- The management event pipeline uses a shared `BillingModeRef` pattern (analogous to `TopologySnapshotRef`): management processor writes to the ref, admission stages read it at tick boundaries
- `componentOfManaged` is the factory to use for any table that needs mid-simulation reconfiguration; `componentOf` and `componentOfReplicated` do not accept management events
- The `DynamoDbTableManagedShape` has 2 inlets (requests + management) and 3 outlets (response, consumption, metric)
