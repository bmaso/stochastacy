# IPS Hand-Off

Last updated: 2026-04-26 (phase 3 complete — slice 10 shipped)

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

Phase 3 is complete. Recommended next-phase work (phase 4):

1. **Multi-region runnable demo** — extend the order-tracking demo to materialize a multi-region scenario. Touches the JSONL/Postgres/Grafana stack with per-region cost panels, a Postgres schema migration adding region columns, and demo-runner CLI extensions. This is "slice 10b" or its own phase-4 deliverable.
2. **rWCU as a distinct capacity bucket** — replicated writes at a destination region currently bill against normal WCU; AWS bills them as rWCU with separate pricing. Adding this is required before claiming "on-demand simulation is fully accurate."
3. **Tiered cross-region transfer pricing** — slice 10 uses flat per-source-region rates. Real AWS uses tiered rates ("first 10 TB at rate X, next 40 TB at rate Y"). Requires the pricing component to track a billing-period bucket.
4. **GSI/LSI support inside replicated tables** — slice 10's `componentOfReplicated` rejects configs with secondary indexes. Real AWS Global Tables support GSIs/LSIs on replicas; adding this is its own slice.
5. Continue keeping the runnable phase-2 demo stable.

Slice 8b (`TableAdmissionStage` decomposition) is complete: sampling and shaping have been extracted into the upstream `TableSamplingStage`, and `TableAdmissionStage` re-resolves a shaped request's footprint and index-maintenance plan when topology evolution at a tick boundary invalidates the memorialized values. `IndexMaintenancePlanDerivation` is the single canonical helper for deriving per-index plans, used by both stages.

Slice 9 (LSI item-collection size limit) is complete: `DynamoDbTable.Config.itemCollectionSizeLimitBytes` (default 10 GiB when LSIs are configured) drives a validate-then-mutate split in `TableStorageStage`. The "current size" of an item collection is sampler-provided per write (`WriteItemSample.currentItemCollectionBytes`, `DeleteItemSample.currentItemCollectionBytes`); no per-key state lives in the simulator. Rejected writes emit a new top-level `ItemCollectionSizeLimitExceededResponse` and a `StorageMetricEvent.ItemCollectionSizeLimitExceeded` metric; no consumption is charged, no state is mutated, and no index maintenance is propagated. The graph wiring now puts `TableStorageStage` between admission and the index-maintenance graph (validated samples flow on its new `out3`).

Slice 10 (Global Tables) is complete. New public components: `DynamoDbGlobalTable.componentOf(config)` and `DynamoDbTable.componentOfReplicated(config)`. Replication is modeled with stochastic per-link lag (sampled from `ContinuousDistribution`s configured per directional region pair), per-region cost accounting (no region-awareness in regional pipelines, per decision 4), and a generic `stochastacy.aws.transfer` package for cross-region data transfer cost (parallel pipeline, AWS-service-agnostic with `sourceService` tag). Replication coordinator restamps replicated writes to destination apply-tick eventTime; loop prevention forks outbound replication from `admission.out0` rather than `storage.out3`. See `docs/specs/global-tables-design.md` for the 10 design decisions.

Treat [ips-phase3.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/roadmaps/ips-phase3.md) as the canonical planning anchor for ongoing simulator work, and use [dynamodb-table.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/architecture/dynamodb-table.md) as the design-boundary reference for where new slice logic should live.

## Upcoming Phase-3 Slices

### Slice 8b: TableAdmissionStage Decomposition (complete)

Sampling, throughput-demand calculation, partition resolution, and index-maintenance-plan derivation now live in the upstream `TableSamplingStage`. `TableAdmissionStage.componentOfShaped` consumes shaped envelopes and applies admission. The `TopologySnapshotRef` is owned by `TableAdmissionStage` and read by `TableSamplingStage`.

One subtlety: in a linear pipeline, `TableSamplingStage` shapes a request *before* `TableAdmissionStage` sees it, so the first request of a new tick is shaped against the previous tick's topology. When `TableAdmissionStage` evolves topology at the tick boundary, it sets `topologyChangedOnLastAdvance` and re-resolves the shaped envelope's base-table footprint and (for writes) index-maintenance plan via `IndexMaintenancePlanDerivation.derivePlans` before passing the request into `decideFromShaped`. Subsequent requests in the same tick read the freshly-published topology ref and need no reshape.

`IndexMaintenancePlanDerivation` is the canonical single source for plan derivation, used by both `TableSamplingStage` (initial shaping) and `TableAdmissionStage` (post-evolution re-shaping). All 143 core and 38 examples tests pass with no test modifications.

### Slice 9: LSI Item-Collection Constraints (complete)

LSI-aware item-collection size limit modeled stochastically: the use-case sampler provides the assumed current size of the item collection a write lands in via `WriteItemSample.currentItemCollectionBytes` / `DeleteItemSample.currentItemCollectionBytes` (default `0L`). `TableStorageStage` performs a validate-then-mutate split inside `componentOfAdmitted`: for each write it computes `current + (baseDelta + sum(LSI plan deltas))` and compares to the effective limit. Writes whose resulting collection would exceed the limit AND whose total delta is positive are rejected before any state mutation; rejected writes emit the new `ItemCollectionSizeLimitExceededResponse` (a top-level `DynamoDBResponse` variant, distinct from `ThrottledResponse`) and a `StorageMetricEvent.ItemCollectionSizeLimitExceeded` metric, no consumption events, and crucially do **not** propagate index-maintenance — `TableStorageStage` now sits between admission and the index-maintenance graph, with its new `out3` (validated admitted samples) feeding maintenance.

The configuration entry point is `DynamoDbTable.Config.itemCollectionSizeLimitBytes: Option[Long]`. When LSIs are configured and the field is `None`, the limit defaults to 10 GiB; when no LSIs are configured the rule never runs regardless of the field value. Shrinking writes (negative or zero `totalDelta`) are always allowed even when current state is anomalously over the limit. No per-key state was added to the simulator — bounded summary-oriented modeling preserved.

Code touchpoints:

- [DynamoDbTable.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala) — config field, validation, graph rewiring (admission → storage → maintenance)
- [TableStorageStage.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStorageStage.scala) — `StorageOutcome` / `StorageRejection`, `validateItemCollectionLimit`, new `out3` for validated samples
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala) — `ItemCollectionSizeLimitExceededResponse`
- [metric_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/metric_events.scala) — `StorageMetricEvent.ItemCollectionSizeLimitExceeded`
- [sample.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/sample.scala) — `currentItemCollectionBytes` on the write/delete sample traits (default `0L`)

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
