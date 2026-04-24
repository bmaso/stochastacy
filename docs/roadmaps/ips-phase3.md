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

### 9. LSI Item-Collection Constraints

Add support for the LSI-specific table behaviors that arise from item-collection limits.

This slice should extend the realism of the single-Region table model without yet moving into cross-Region replication.

### 10. Global Tables And Cross-Region Replication

Add global tables and multi-Region replication behavior.

This slice should introduce replicated write billing, per-replica write amplification, and cross-Region transfer effects.

## Non-Goal For Now

Phase 3 should not initially focus on provisioned-throughput simulation.

That means the following remain secondary or deferred until the on-demand-mode table is more complete:

- table-level provisioned RCU and WCU enforcement
- GSI-level provisioned RCU and WCU enforcement
- provisioned auto scaling behavior
- provisioned-only throttling categories as the main organizing focus

## Current Next Step

Recommended starting point:

- `slice 7: projection-aware index reads`
