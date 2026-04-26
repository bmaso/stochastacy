# IPS Hand-Off

Last updated: 2026-04-26 (slice 8b complete)

## Current Position

The project currently centers on a DynamoDB simulator that supports:

- base-table `GetItem`, `PutItem`, `UpdateItem`, and `DeleteItem`
- base-table and index-targeted `Query`
- base-table and index-targeted `Scan`
- a public `DynamoDbTable` table-and-indexes component
- internal GSI and LSI execution units
- internal index-state ownership and write propagation
- a first-class `TableAdmissionStage` admission layer in front of `TableStorageStage`
- on-demand hard admission checks for base-table reads and writes plus GSI reads and writes
- hot-partition enforcement against table and index partition topology
- burst-capacity admission rescue
- adaptive-capacity admission rescue
- dynamic partition-topology evolution at tick boundaries
- GSI write back-pressure for base-table writes
- projection-aware GSI-vs-LSI read execution
- bytes-oriented, plan-driven index maintenance for admitted writes
- raw DynamoDB consumption events
- additive usage aggregation
- time-based storage usage aggregation from timed event streams
- downstream pricing from usage totals and time-based usage
- Monte Carlo multi-trial execution
- raw per-tick JSONL export
- derived `60s` and `300s` windowed JSONL export
- Postgres staging for demo records
- a provisioned Grafana dashboard
- overall demo reporting plus per-GSI consumed read/write reporting

The current runnable demo surface is still the order-tracking phase-2 demo, but the simulator frontier has moved into phase 3 and is currently implemented through slice 8.

## Architectural Direction

The implemented design direction is:

- `TableStorageStage` remains the storage-facing execution core
- `TableAdmissionStage` is the upstream admission, shaping, and throttling layer
- `DynamoDbTable` is the public table-and-indexes graph component
- GSIs and LSIs are represented as internal execution units, not separately wired public graph components
- admission-time sampled request envelopes carry the information downstream that later stages need, rather than resampling in `TableStorageStage`
- on-demand behavior is still the primary planning axis; provisioned-mode fidelity is still intentionally secondary
- additive request-priced usage is folded into `DynamoDbUsageTotals`
- duration-based storage usage is derived from timed consumption streams into `DynamoDbTimeBasedUsageTotals`
- pricing is computed downstream from those two usage layers
- demo reporting preserves raw per-tick records and derives windowed records downstream
- visible demo output preserves:
  - overall read and write capacity
  - per-GSI read and write capacity
  - overall-only storage and total cost
- Grafana reads staged Postgres-backed records rather than reading raw files directly

## Key Code Locations

- [DynamoDbTable.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala)
- [TableAdmissionStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala)
- [TableStorageStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [table_metric_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/table_metric_events.scala)
- [UseCaseSampler.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/UseCaseSampler.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [DynamoDbUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)
- [model.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/model.scala)
- [rollup.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/rollup.scala)
- [report.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/report.scala)
- [OrderTrackingSingleTrialRunner.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingSingleTrialRunner.scala)
- [OrderTrackingPhase2Demo.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2Demo.scala)
- [001-schema.sql](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/postgres/init/001-schema.sql)
- [order-tracking-phase2-dashboard.json](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/grafana/order-tracking-phase2-dashboard.json)

## Key Proof Tests

- [TableStorageStageGetItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageGetItemSpec.scala)
- [TableStorageStagePutItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStagePutItemSpec.scala)
- [TableStorageStageUpdateItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageUpdateItemSpec.scala)
- [TableStorageStageDeleteItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageDeleteItemSpec.scala)
- [TableStorageStageQuerySpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageQuerySpec.scala)
- [TableStorageStageScanSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStorageStageScanSpec.scala)
- [TableAdmissionStageSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableAdmissionStageSpec.scala)
- [DynamoDbTableComponentSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala)
- [DynamoDbTableConfigSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableConfigSpec.scala)
- [DynamoDbRequestSurfaceSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/DynamoDbRequestSurfaceSpec.scala)
- [TableStorageStagePricingIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/pricing/TableStorageStagePricingIntegrationSpec.scala)
- [OrderTrackingPhase2DemoRunnerSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2DemoRunnerSpec.scala)
- [OrderTrackingPostgresBridgeSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPostgresBridgeSpec.scala)

## Current Operator Workflow

The current demo workflow is:

1. `docker compose up -d`
2. `generate` a batch to JSONL through `OrderTrackingPhase2Bridge`
3. `stage` that batch into Postgres
4. `view` the provisioned Grafana dashboard
5. select a staged `batch_id`, a `Window Size` of `60` or `300`, and a `GSI Index Name` when inspecting per-GSI panels

## Recommended Next Work

The main remaining work has moved into phase 3:

1. implement `slice 9: LSI item-collection constraints` — next concrete implementation target
2. implement `slice 10: global tables and cross-Region replication`
3. keep the runnable phase-2 demo stable while the simulator internals advance
4. perform any targeted documentation cleanup needed so the public docs stop implying phase 2 is still the simulator frontier

Slice 8b (`TableAdmissionStage` decomposition) is complete: sampling and shaping have been extracted into the upstream `TableSamplingStage`, and `TableAdmissionStage` re-resolves a shaped request's footprint and index-maintenance plan when topology evolution at a tick boundary invalidates the memorialized values. `IndexMaintenancePlanDerivation` is the single canonical helper for deriving per-index plans, used by both stages.

Treat [ips-phase3.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/roadmaps/ips-phase3.md) as the canonical planning anchor for ongoing simulator work, and use [dynamodb-table.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/architecture/dynamodb-table.md) as the design-boundary reference for where new slice logic should live.

## Upcoming Phase-3 Slices

### Slice 8b: TableAdmissionStage Decomposition (complete)

Sampling, throughput-demand calculation, partition resolution, and index-maintenance-plan derivation now live in the upstream `TableSamplingStage`. `TableAdmissionStage.componentOfShaped` consumes shaped envelopes and applies admission. The `TopologySnapshotRef` is owned by `TableAdmissionStage` and read by `TableSamplingStage`.

One subtlety: in a linear pipeline, `TableSamplingStage` shapes a request *before* `TableAdmissionStage` sees it, so the first request of a new tick is shaped against the previous tick's topology. When `TableAdmissionStage` evolves topology at the tick boundary, it sets `topologyChangedOnLastAdvance` and re-resolves the shaped envelope's base-table footprint and (for writes) index-maintenance plan via `IndexMaintenancePlanDerivation.derivePlans` before passing the request into `decideFromShaped`. Subsequent requests in the same tick read the freshly-published topology ref and need no reshape.

`IndexMaintenancePlanDerivation` is the canonical single source for plan derivation, used by both `TableSamplingStage` (initial shaping) and `TableAdmissionStage` (post-evolution re-shaping). All 143 core and 38 examples tests pass with no test modifications.

### Slice 9: LSI Item-Collection Constraints

This should be treated as the next concrete implementation target after slice 8b is stable.

The intended goal is to model the DynamoDB behavior that only shows up when a table has one or more LSIs and a single partition-key value accumulates too much combined table-plus-LSI data. The important realism target is the LSI-specific item-collection ceiling, not a general rewrite of storage accounting.

Guidance for this slice:

- keep the project single-Region; do not mix replication work into this slice
- keep `DynamoDbTable` as the public resource boundary; do not expose LSIs as separate public components
- preserve the existing split where `TableAdmissionStage` handles admission concerns and `TableStorageStage` handles storage semantics and downstream physical effects
- model the constraint at the item-collection level keyed by the base-table partition key, because LSIs share the table partition key
- make the limit depend on the combined size of base-table items plus corresponding LSI entries for that partition-key value
- prefer deterministic, bytes-oriented accounting over heuristic “risk of exceeding” guesses when the simulator already has enough data to decide
- allow writes that shrink the affected item collection even if earlier growth would have pushed the collection near or over the limit
- avoid turning this into a full per-item exact replica of DynamoDB internals; summary-oriented state is still the project norm unless exactness is required for the limit check

Likely implementation shape:

- extend table state so the simulator can track item-collection byte totals per base partition-key value when LSIs exist
- derive the effect of a write on that item collection from the same memorialized write-maintenance plan that now drives precise index maintenance
- reject or fail writes whose resulting item collection would exceed the configured LSI-aware limit
- emit a response and telemetry shape that makes it obvious the failure came from the LSI item-collection rule rather than ordinary throughput throttling
- ensure deletes and shrinking updates reduce the tracked item-collection footprint correctly

Likely code touchpoints:

- [DynamoDbTable.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala)
- [TableAdmissionStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala)
- [TableStorageStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [table_metric_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/table_metric_events.scala)

The most important wobble-avoidance note is that slice 9 should stay tightly scoped to LSI-specific item-collection realism. It should not quietly become:

- generic write validation for unrelated table limits
- a new provisioned-throughput slice
- global-table groundwork unless that groundwork is directly reusable and low-risk
- a broad redesign of how mutable state is stored

Recommended test focus:

- writes that grow one item collection past the limit fail
- writes against a different partition-key value still succeed
- deletes and shrinking updates remain allowed
- LSI projection width changes the item-collection outcome when it should
- failures are distinguishable from ordinary throttling in both responses and metric events
- existing slice-8 write-maintenance and projection-aware behaviors remain intact

### Slice 10: Global Tables And Cross-Region Replication

This slice should begin only after slice 9 is stable, because it introduces a new simulator dimension rather than refining the existing single-Region table.

The intended goal is to model a single logical DynamoDB table with multiple regional replicas, where a write accepted in one Region causes downstream replicated work in other Regions with corresponding billing and transfer effects. The simulator should capture the shape of replication and per-replica consequences without requiring a perfect reproduction of every operational detail of DynamoDB global tables.

Guidance for this slice:

- keep the public abstraction centered on one logical table resource, not a bag of loosely coupled per-Region tables that the caller wires manually
- make one Region the ingress point for each client write, then derive replica-side propagation from that admitted write
- preserve the local table pipeline inside each Region as much as possible instead of forking a second implementation path for replicas
- treat replicated writes as downstream consequences of a successful origin write, not as independent user requests
- keep billing and consumption accounting explicit per Region so later reporting layers can aggregate or break down by replica
- separate replication transport/propagation effects from base-table admission logic so single-Region behavior stays understandable
- avoid overcommitting to undocumented conflict-resolution internals; if a heuristic is required, document it plainly

Likely implementation shape:

- introduce a global-table configuration layer that names participating Regions and replica topology
- reuse the existing table-and-indexes execution model per Region, with additional replication envelopes or events emitted after successful origin writes
- emit replicated write consumption for each replica Region, plus any cross-Region transfer usage the simulator chooses to model
- decide explicitly whether replica-side writes should reuse the same index-maintenance plan bytes from the origin Region or re-derive them from replica state; the project will likely wobble if this is left implicit
- keep replica application ordering deterministic within the simulated tick model
- leave advanced conflict-resolution nuance, failover orchestration, and account-crossing behavior as documented non-goals unless the implementation genuinely needs them

Likely code touchpoints:

- [DynamoDbTable.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala)
- [TableStorageStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [DynamoDbUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)

The most important wobble-avoidance note is that slice 10 should introduce a clean replication model, not a second unrelated simulator. The next agent should prefer thin new orchestration around existing per-Region table logic over cloning or bypassing the current `DynamoDbTable -> TableAdmissionStage -> TableStorageStage` flow.

Recommended test focus:

- one origin-region write produces replica-region write effects in every configured replica
- replica-side index maintenance and consumption align with the already-admitted origin write plan
- per-Region usage totals and downstream pricing include replicated work in the expected places
- single-Region tables continue to behave exactly as before when global-table mode is absent
- ordering and timing of replicated effects stay deterministic within the existing tick model

## Notes For A Fresh Session

- the mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- the simulator frontier is phase 3 slice 8, even though the demo surface is still named and organized around phase 2
- countable usage is priced from totals, while storage-like duration pricing is derived from timed streams
- raw per-tick records remain the source of truth, while windowed records are derived for reporting and dashboard use
- per-window values are reporting artifacts, not authoritative billed prices
- visible per-GSI reporting is for read/write capacity only; storage and cost remain overall-only in the demo
- phase-3 slices should continue to be implemented one at a time; do not bundle slice 9 and slice 10 together
- if the next session starts with planning work, use the current handoff plus the phase-3 roadmap and the DynamoDB table architecture doc rather than relying on older phase-specific demo notes
