# IPS Phase 3

## Goal

Phase 3 is aimed at an accurate **on-demand-mode** DynamoDB table and index resource-consumption and billing simulation.

The architectural focus for this phase is the next set of internal `Table` graph behaviors, not primarily new demo-surface work.

Phase 3 should stay explicitly **on-demand-first** before expanding into provisioned-throughput-specific simulation.

## Phase-3 Implementation Slices

Future phase-3 work should be planned and implemented one slice at a time.

### 1. On-Demand Throttling Foundation

Introduce the basic on-demand throttling and enforcement framework for tables and indexes.

This slice should establish the core internal machinery for rate enforcement and throttling outcomes in on-demand mode.

### 2. Hot Partitions

Add hot-partition or key-range throttling behavior for tables and indexes.

This slice should make throttling sensitive to uneven access patterns instead of treating all load as evenly distributed.

### 3. Burst Capacity

Add short-term burst absorption behavior for on-demand workloads.

This slice should make the simulator less binary under spikes by allowing brief use of retained excess capacity.

### 4. Adaptive Capacity

Add adaptive-capacity behavior for uneven traffic patterns.

This slice should refine the hot-partition model by allowing DynamoDB-like relief for imbalanced access when appropriate.

### 5. Dynamic Partition Topology

Allow table and index partition topology to change over simulated time.

This slice should introduce time-varying partition counts and topology changes so admission and partition-resolution logic can use the topology that exists at the current simulated time.

### 6. GSI Back-Pressure

Model the case where base-table writes are throttled because a GSI cannot absorb index-update demand quickly enough.

This slice should make the dependency between table writes and internal GSI update capacity explicit.

### 7. Projection-Aware Index Reads

Refine `Query` and `Scan` so GSI and LSI reads depend on projection shape.

This slice should capture the difference between:

- GSI reads that cannot fetch non-projected attributes from the base table
- LSI reads that may require additional fetches from the base table

### 8. More Precise Index Maintenance And Billing Effects

Refine write-side table and index maintenance so index creation, replacement, deletion, storage growth, and on-demand billing effects are more realistic.

This slice should improve the fidelity of write amplification and index-related billing outcomes.

### 8b. TableAdmissionStage Decomposition: Extract Sampling And Shaping

Extract the sampling, throughput-demand calculation, partition-resolution, and index-maintenance-plan derivation responsibilities out of `TableAdmissionStage` into a separate upstream graph stage.

This slice is a structural refactoring that does not change observable simulator behavior. Its purpose is to decompose the growing `TableAdmissionStage` monolith into sequential pipeline stages with narrower responsibilities, so that slice 9 and later slices have a clean place to add new write-path logic without further bloating a single stage.

The concrete decomposition target is:

- a new upstream **sampling and shaping** stage that takes raw `DynamoDBRequest` elements, invokes the use-case sampler, computes throughput demand, resolves logical partition access into concrete partition footprints, derives the index-maintenance plan for writes, and emits a fully-shaped request envelope carrying all sampled and derived facts
- a slimmed-down **admission and throttling** stage (the remaining `TableAdmissionStage`) that receives shaped envelopes and applies the admission sequence: whole-resource checks, hot-partition checks, adaptive relief, burst rescue, GSI write back-pressure, tick-boundary housekeeping, and topology evolution

Implementation guidance:

- define an intermediate envelope type that carries the shaped request, sampled outcome, throughput demand, resolved partition footprint, and index-maintenance plan from the sampling stage to the admission stage
- the sampling stage should be functionally stateless with respect to admission concerns; it reads `TableState` for sampling but does not own per-tick usage, burst, or topology state
- the admission stage continues to own per-tick usage state, burst reservoirs, topology snapshots, and topology evolution
- the admission stage must publish its current topology snapshot to the sampling stage so that partition resolution uses the correct topology at the current simulated tick; the cleanest approach is for the admission stage to own topology and for the sampling stage to receive the current snapshot via a shared reference or tick-boundary protocol
- timed-event protocol invariants (control-event propagation, tick ordering) must be preserved across the new stage boundary
- all existing tests must remain green with no behavioral changes
- the public `DynamoDbTable` graph wiring must be updated to include the new stage in the internal pipeline

This slice should not:

- change any observable simulation behavior
- introduce new configuration parameters
- add or remove any metric, consumption, or response event types
- change the public `DynamoDbTable` API surface

Recommended test focus:

- all existing `TableAdmissionStageSpec` tests pass unchanged or with minimal fixture adaptation
- all existing `DynamoDbTableComponentSpec` tests pass unchanged
- all existing integration and demo tests pass unchanged
- the timed-event protocol is preserved across the new stage boundary

### 9. LSI Item-Collection Constraints (complete)

LSI item-collection size limit modeled stochastically rather than via per-key state. Implementation summary:

- `DynamoDbTable.Config.itemCollectionSizeLimitBytes: Option[Long]` (default 10 GiB when LSIs are configured; rule never runs when no LSIs are configured).
- The "current size" of a write's item collection is sampler-provided per write via `WriteItemSample.currentItemCollectionBytes` / `DeleteItemSample.currentItemCollectionBytes` (defaults `0L`). No per-key state in the simulator.
- `TableStorageStage` performs a validate-then-mutate split: per write, computes `current + (baseDelta + sum(LSI plan deltas))` and rejects when total delta is positive AND result exceeds the limit. Rejected writes emit a new top-level `ItemCollectionSizeLimitExceededResponse` and a `StorageMetricEvent.ItemCollectionSizeLimitExceeded` metric; no consumption events, no state mutation, no maintenance propagation.
- Pipeline rewiring: `TableStorageStage` now sits between admission and the index-maintenance graph. Its new `out3` (validated admitted samples) feeds maintenance so rejected writes never propagate index updates.
- Shrinking writes/deletes (`totalDelta <= 0`) are always allowed even when current state is anomalously over the limit.

### 10. Global Tables And Cross-Region Replication

Add global tables and multi-Region replication behavior.

This slice should introduce replicated write billing, per-replica write amplification, and cross-Region transfer effects.

The concrete planning target for this slice is one logical table with multiple regional replicas, where a successful write in one Region induces deterministic replica-side work in the other Regions.

Implementation guidance:

- keep one logical public table abstraction rather than exposing loosely coupled public per-Region table components
- treat replica writes as downstream consequences of an origin write, not as independent client requests
- reuse existing per-Region table execution as much as possible rather than forking a separate replica-only path
- make per-Region consumption and billing explicit so downstream pricing and reporting can attribute replicated work correctly
- keep replication orchestration separate from local admission behavior so single-Region reasoning remains simple
- document any heuristic treatment of conflict resolution or replication timing rather than implying unsupported DynamoDB-exact guarantees

This slice should aim to make the following outcomes true:

- an origin-region write causes replica-region write effects in every configured replica
- replica-side index maintenance is consistent with the write plan derived from the accepted origin write
- replicated write billing appears in usage and pricing layers in an inspectable way
- single-Region tables remain unchanged when global-table mode is not configured
- replicated effects remain deterministic within the simulator's tick model

This slice should not quietly become:

- a rewrite of the single-Region table pipeline
- a broad failover-orchestration project
- a deep simulation of every undocumented global-table conflict edge case
- a change that makes per-Region accounting opaque

## Non-Goal For Now

Phase 3 should not initially focus on provisioned-throughput simulation.

That means the following remain secondary or deferred until the on-demand-mode table is more complete:

- table-level provisioned RCU and WCU enforcement
- GSI-level provisioned RCU and WCU enforcement
- provisioned auto scaling behavior
- provisioned-only throttling categories as the main organizing focus

## Current Next Step

Recommended starting point:

- `slice 10: global tables and cross-Region replication` — slices 8b and 9 are complete. Slice 9 modeled the LSI item-collection size rule stochastically: the use-case sampler provides the per-write "current collection size" estimate, and `TableStorageStage` runs a validate-then-mutate split that rejects growing writes that would push the collection past the configured limit (default 10 GiB when LSIs are configured). No per-key state was introduced.
