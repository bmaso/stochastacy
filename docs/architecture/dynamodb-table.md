# DynamoDB Table Architecture

## Overview

A future complete DynamoDB `Table` component should be a composed Pekko graph built from smaller stages with distinct responsibilities. The goal is to model a table the same way the wider simulator models AWS resources: as a timed, stateful stream component that consumes requests and emits synchronous responses, resource-consumption events, and telemetry events.

`TableStorageStage` is the storage-facing core of that future `Table` simulator. It represents the part of the table that actually touches simulated storage: the place where item existence, item size, table byte totals, and direct read/write physical effects are determined.

Phase-2 step 2 introduced `DynamoDbTable` as the first public **table-and-indexes mono-component** rather than exposing a set of separately wired public index components.

That means:

- the public simulator surface should expose one composed DynamoDB **table-and-indexes** resource via `DynamoDbTable`
- that resource may internally contain:
  - one base-table execution unit
  - zero or more GSI execution units
  - zero or more LSI execution units
- requests should be dispatched internally within the graph based on target selection
- writes against the base table should propagate internally to the relevant index execution units

In step 2 specifically:

- `DynamoDbTable` is public
- `TableStorageStage` remains the base-table execution unit inside it
- GSI and LSI execution units are placeholder internal components
- real index state and write propagation are still deferred to the next phase-2 step

In step 3:

- configured GSIs and LSIs now own internal `TableState`
- successful base-table writes propagate into those internal index states
- propagation currently mirrors summary-level write effects into every configured index
- index propagation now emits index-targeted write consumption facts
- real index reads are still deferred

In step 4:

- `Query` is now executable across the public `DynamoDbTable` component for:
  - the base table
  - configured GSIs
  - configured LSIs
- the first query slice remains intentionally opaque and usecase-driven rather than modeling full DynamoDB query ASTs
- query execution is summary-oriented:
  - evaluated item and byte totals are tracked separately from returned item and byte totals
  - read consumption is derived from evaluated bytes
- `Scan` remains deferred

In step 5:

- `Scan` is now executable across the public `DynamoDbTable` component for:
  - the base table
  - configured GSIs
  - configured LSIs
- the first scan slice also remains intentionally opaque and usecase-driven rather than modeling full DynamoDB scan shapes
- scan execution is summary-oriented:
  - evaluated item and byte totals are tracked separately from returned item and byte totals
  - read consumption is derived from evaluated bytes
- GSI scans are eventually-consistent only; base-table and LSI scans may be eventual or strong

In phase-3 slice 1:

- `TableAdmissionStage` now exists as the first real admission stage inside `DynamoDbTable`
- the sampler is consulted when a request first enters the internal table graph
- sampled throughput demand and sampled operation outcomes are memorialized in an internal admitted-request envelope
- `TableAdmissionStage` applies immediate on-demand hard checks for:
  - base-table read throughput
  - base-table write throughput
  - GSI read throughput
- LSI reads share the base-table read checks in this slice
- `TableStorageStage` now executes admitted sampled requests without independently resampling them
- throttled requests emit an immediate `ThrottledResponse` and admission-stage telemetry, but no consumption events

In phase-3 slice 2:

- hot partitions are now modeled with a partition topology known to the admission layer
- the sampler returns logical partition access rather than concrete partition ids
- `TableAdmissionStage` resolves that logical access into concrete partition footprints at admission time
- per-partition hot-partition limits now sit alongside the slice-1 whole-resource hard checks
- `Query` may now be modeled as single-partition or multi-partition access
- `Scan` is modeled as all-partitions access
- LSI reads share the base table partition topology and hot-partition enforcement
- dynamic partition splitting and repartitioning remained deferred at this slice

In phase-3 slice 3:

- `TableAdmissionStage` now models burst-backed admission using stored unused steady-state throughput
- burst is tracked separately for:
  - table reads
  - table writes
  - each GSI's reads
- LSI reads use the table-read burst path
- burst may rescue both whole-resource and hot-partition failures
- admission-stage telemetry now distinguishes:
  - normal admission
  - burst-backed admission
  - throttling with currently available burst headroom
- warm throughput remains deferred

In phase-3 slice 4:

- `TableAdmissionStage` now models adaptive-capacity relief as same-tick redistribution of unused baseline per-partition capacity
- adaptive capacity is attempted before burst capacity
- adaptive relief applies only to hot-partition failures, not whole-resource overage
- adaptive relief is tracked separately for:
  - table reads
  - table writes
  - each GSI's reads
- LSI reads use the table-read adaptive path
- admission-stage telemetry now distinguishes:
  - adaptive-backed admission
  - burst-backed admission
  - adaptive-and-burst-backed admission

In phase-3 slice 5:

- `TableAdmissionStage` now owns a time-varying partition topology snapshot for the table or index branch it admits
- logical partition access is resolved against the topology active at the current simulated tick
- topology changes are applied only at tick boundaries
- topology growth is modeled heuristically from AWS-documented trigger categories:
  - storage growth
  - throughput growth
  - sustained heat
- topology changes are reported as admission-stage telemetry events
- the current model grows partition counts and rehashes logical access; it does not model explicit physical split locations
- LSIs continue to share the base-table topology model at the admission layer
- item isolation remains deferred

In phase-3 slice 6:

- base-table writes now depend on internal GSI write admission before they are allowed into the data plane
- `TableAdmissionStage` derives internal GSI write propagation effects at table ingress for admitted base-table writes
- the same memorialized write-side index plan is now carried with admitted write requests for downstream propagation
- a base-table write may now throttle because a specific GSI cannot absorb the induced internal write pressure
- GSI write back-pressure uses the same simulator machinery already built for other scopes:
  - whole-resource on-demand checks
  - hot partitions
  - adaptive capacity
  - burst capacity
  - dynamic partition topology
- LSI write back-pressure remains deferred

In phase-3 slice 7:

- `Query` and `Scan` against GSIs and LSIs are now projection-aware
- index definitions now carry projection metadata:
  - `All`
  - `KeysOnly`
  - `Include(projectedNonKeyBytesPerItem)`
- `QueryRequest` and `ScanRequest` now carry a lightweight requested-read shape
- samplers now return projection-aware read summaries for query and scan outcomes
- `TableStorageStage` now enforces the DynamoDB difference between GSI and LSI reads:
  - GSI reads remain index-only and are limited to projected bytes when non-projected attributes would have been needed
  - LSI reads may emit additional base-table read work when non-projected attributes are fetched
- write-side projection-sensitive index maintenance remains deferred to the next slice

In phase-3 slice 8:

- the table now derives one memorialized index-maintenance plan for every admitted base-table write
- that plan is bytes-oriented and projection-aware, and may mark each index as:
  - `NoOp`
  - `InsertEntry`
  - `ReplaceEntry`
  - `DeleteEntry`
- only GSI entries from that plan participate in write back-pressure admission
- both GSI and LSI entries from that plan now drive downstream index mutation, storage deltas, and write-capacity consumption
- coarse response-driven index propagation has been replaced by plan-driven downstream propagation
- downstream index maintenance now emits explicit index-entry telemetry for inserted, replaced, deleted, and unchanged entries

The intent is to keep graph construction safe and coherent. A caller should not need to manually wire table writes into separate public index components in order to obtain valid DynamoDB-like behavior.

In phase-3 slice 8b:

- sampling, throughput-demand calculation, partition resolution, and index-maintenance-plan derivation are extracted from `TableAdmissionStage` into a separate upstream sampling-and-shaping stage
- the sampling stage takes raw `DynamoDBRequest` elements and emits fully-shaped request envelopes carrying sampled outcomes, throughput demand, resolved partition footprints, and index-maintenance plans
- the remaining `TableAdmissionStage` receives shaped envelopes and applies the admission sequence without re-invoking the sampler or re-deriving the maintenance plan
- topology snapshots are owned by the admission stage and made available to the sampling stage so partition resolution uses the correct topology at the current simulated tick
- this decomposition does not change any observable simulation behavior; it is a structural refactoring to support cleaner addition of future write-path logic

In phase-3 slice 9:

- the table now models the DynamoDB LSI item-collection-size rule (combined base item bytes plus LSI projected entry bytes for one base partition-key value cannot exceed a configured limit, default 10 GiB)
- the rule is enforced stochastically via summary state, not via per-key data structures: the use-case sampler provides the per-write "current item-collection bytes" estimate as a field on the write/delete sample
- `TableStorageStage` performs a validate-then-mutate split: per write, computes `current + (baseDelta + sum(LSI plan deltas))` and rejects when total delta is positive AND the result exceeds the limit
- shrinking writes (negative or zero total delta) are always allowed even when the current state is anomalously over the limit
- rejected writes emit a new top-level `ItemCollectionSizeLimitExceededResponse` (distinct from `ThrottledResponse`) and a `StorageMetricEvent.ItemCollectionSizeLimitExceeded` metric; no consumption events accrue, no state is mutated, and no index-maintenance is propagated
- the storage stage now sits between admission and the index-maintenance graph; its new `out3` (validated admitted samples) feeds maintenance so rejected writes never propagate index updates
- the rule never runs when no LSIs are configured, regardless of the limit field value

In phase-3 slice 10:

- DynamoDB Global Tables are now modeled. A new public component `DynamoDbGlobalTable.componentOf(config)` wraps N independent regional `DynamoDbTable` instances (each via the new `componentOfReplicated` factory) plus a `ReplicationCoordinator` stage that propagates writes between regions
- replication is stochastic and per directional link: each `(sourceRegion, destinationRegion)` pair has its own `ContinuousDistribution` for replication lag (using Apache Commons Statistics); samples are floored to non-negative tick counts
- replicated writes bypass the destination region's admission stage (per decision 3) and apply directly to storage as already-admitted samples; they accrue normal destination-region WCU consumption (rWCU as a distinct capacity bucket is a deferred follow-on)
- per-region cost accounting flows through unchanged single-region pipelines: `DynamoDbUsageTotals` and `DynamoDbPricing` know nothing about regions; aggregation across regions for grand-total cost is the caller's concern (per decision 4)
- cross-region data transfer cost is modeled as a generic, AWS-service-agnostic component in `stochastacy.aws.transfer` (separate from DDB-specific code) — `CrossRegionTransferEvent`, `CrossRegionTransferUsageTotals`, `CrossRegionTransferPricing`. The DDB replication coordinator emits events tagged `sourceService = "DynamoDB"`; future producers (S3 CRR, RDS, Lambda) emit the same type and feed the same pipeline
- replication loop prevention: `componentOfReplicated`'s outbound replication output forks from `admission.out0` (real client writes) NOT from `storage.out3` (which would also include applied-replicated writes); replicated writes bypass admission and never appear on outbound
- replicated writes are restamped with the destination region's apply-tick `eventTime` (since they apply at the destination's local clock, not the origin's)
- conflict resolution is explicitly out of scope; the simulator assumes workloads do not issue genuinely conflicting cross-region writes
- tick alignment across N regions uses chained `MergeTimedEventGraph` instances (newly exposed via a public `graphOf` factory) to combine outbound replication streams while preserving the timed-event protocol
- slice 10 supports base-table-only configurations per region; combining global tables with GSIs/LSIs is a deferred follow-on

## Layering

In the full `Table` component, requests now flow through several conceptual layers before reaching `TableStorageStage`:

1. Sampling, throughput-demand calculation, partition resolution, and index-maintenance-plan derivation in the sampling-and-shaping stage
2. Request admission and throttling in `TableAdmissionStage`
3. Topology-aware hot-partition enforcement
4. Same-tick adaptive-capacity redistribution
5. Burst-backed admission using retained unused throughput
6. Tick-boundary topology evolution
7. Internal GSI write back-pressure checks for base-table writes
8. Projection-aware index read execution in `TableStorageStage`
9. Precise downstream index maintenance from memorialized write plans
10. Data-plane storage execution in `TableStorageStage`

That storage layer should itself be internally composed of:

- base-table execution
- internal request dispatch or branching logic
- internal index execution units
- internal write-propagation logic from the base table into indexes
- merged response, consumption, and telemetry outputs

Earlier layers can model whether a request is delayed, throttled, rejected, or otherwise transformed before it reaches storage. `TableStorageStage` sits below those concerns. By the time an admitted sampled request arrives here, the simulator should treat it as an operation that has already been admitted to the table's physical data plane.

## Why TableStorageStage Exists

This separation is useful because it lets the `Table` component be composed from simpler Pekko graphs with clear boundaries. `TableAdmissionStage` can stay focused on sampling, admission, and hard checks, while `TableStorageStage` stays focused on storage semantics and physical effects.

That makes `TableStorageStage` the authoritative source of truth for the question: "what would the table itself do with this already admitted request?"

Phase 2 should preserve that idea while broadening the internal storage model. The base table and its indexes should still be treated as parts of one larger DynamoDB table resource, not as independent public resources that the caller assembles manually.

## Responsibilities Of TableStorageStage

`TableStorageStage` is responsible for:

- inspecting and possibly mutating table state
- producing the synchronous DynamoDB response for an admitted request
- emitting resource-consumption facts caused by servicing the request
- emitting telemetry and metric events that summarize what happened
- enforcing projection-aware GSI-vs-LSI read behavior once a read has reached the data plane

It is not responsible for account-wide limits, retries, or upstream admission decisions.

The sampling-and-shaping stage is responsible for:

- resolving the effective admission and execution targets
- invoking the use-case sampler at table ingress
- computing throughput demand from the sampled outcome
- resolving logical partition access into concrete partition footprints against the current topology
- deriving the index-maintenance plan for write operations
- emitting a fully-shaped request envelope that carries all sampled and derived facts downstream

The sampling-and-shaping stage is not responsible for admission decisions, per-tick usage tracking, burst or adaptive capacity, topology evolution, or throttled-response generation.

`TableAdmissionStage` is responsible for:

- applying immediate on-demand hard checks against shaped request envelopes
- applying per-partition hot-partition checks
- applying adaptive-capacity relief for eligible hot-partition overage
- applying burst-backed admission when steady-state checks would otherwise fail
- evaluating GSI write back-pressure for base-table writes
- owning per-tick usage state, burst reservoirs, and topology snapshots
- evolving topology at tick boundaries
- producing throttled responses and admission-stage metrics when a request is rejected

`TableAdmissionStage` is not responsible for sampling, throughput-demand calculation, partition resolution, or index-maintenance-plan derivation.

## Composition Goal

A complete future `Table` component can therefore be viewed as:

`incoming table request -> sampling/shaping -> admission/capacity stages -> composed table-and-indexes storage graph -> response/consumption/telemetry outputs`

The current concrete shape is now closer to:

`incoming table request -> validation/dispatch -> sampling/shaping -> TableAdmissionStage admit-or-throttle -> TableStorageStage storage execution -> merged response/consumption/telemetry outputs`

The composed storage graph should be public as one table resource, but internally structured from smaller execution units.

Conceptually, that internal structure should include:

- request dispatch to the correct read target
- base-table write execution
- internal index-state updates caused by base-table writes
- merged response, consumption, and metric streams

This is intentionally different from a design where a caller constructs:

- one public table component
- one public index component per index
- manual wiring between them

That more manual style would make it too easy to construct invalid or internally inconsistent graphs.

This structure also keeps the phase-2 demo target clear: the public graph should remain one indexed-table resource that can later support usage and cost-range estimation without asking the caller to assemble table and index behavior manually.

As the model becomes more realistic, we should be able to add outer stages without needing to redesign `TableStorageStage` itself.
