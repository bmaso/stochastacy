# IPS Phase 2

## Goal

Extend the initial public showing from table-only DynamoDB simulation into index-aware and query-oriented DynamoDB simulation.

Phase 2 should be treated as an incremental continuation of phase 1, not as one giant implementation task.

## Scope And Non-Goals

Phase 2 is in scope for:

- global secondary indexes
- local secondary indexes
- `Query`
- `Scan`
- PartiQL parser support at the request-shape level
- table-plus-index composition
- index-aware resource consumption, usage aggregation, and pricing extension

Phase 2 is out of scope for:

- cross-region replication
- global table behavior
- replicated index update behavior
- full PartiQL execution semantics

## Phase-2 Development Steps

Future implementation work should be planned and executed one numbered step at a time.

### 1. Execution Surface Expansion

Feature goal:

- expand the request and response surface so phase-2 read operations and index-targeted requests can exist cleanly beside the phase-1 CRUD surface

Why this step comes first:

- later work on indexes, `Query`, `Scan`, and PartiQL needs a stable phase-2 request surface before deeper behavior can be added

Testing strategy:

- add compatibility tests proving phase-1 CRUD behavior remains unchanged
- add request-shape tests that distinguish:
  - base-table reads
  - index-targeted reads
  - unsupported PartiQL execution paths

### 2. Index Modeling And Composition

Feature goal:

- add GSI and LSI modeling as part of one composed table-and-indexes resource

Why this step comes second:

- `Query` and `Scan` need a target model before they can behave differently for the base table vs an index
- the simulator needs to decide index ownership and composition before real index-aware read execution is added

Composition recommendation:

- expose one public table-and-indexes graph component
- represent GSIs and LSIs as internal execution units inside that larger graph
- do not require callers to construct or wire separate public index graph components
- keep request dispatch and write propagation inside the composed graph

Testing strategy:

- add composition tests for request routing between base table, GSI, and LSI
- add propagation tests showing base-table writes update index-facing state appropriately
- add accounting-separation tests for base-table vs index usage

### 3. `Query`

Feature goal:

- add `Query` semantics for both base-table and index-targeted reads

Why this step comes before `Scan`:

- `Query` is the tighter, more index-native read path and is the better first expansion of read behavior beyond `GetItem`

Testing strategy:

- add focused `Query` tests for:
  - table-targeted queries
  - GSI-targeted queries
  - LSI-targeted queries
  - empty vs non-empty result sets
  - differing read and byte-usage profiles by target
- add integration tests proving `Query` output feeds cleanly into usage, pricing, and reporting layers

### 4. `Scan`

Feature goal:

- add `Scan` semantics for the base table and any index targets phase 2 allows

Why this step follows `Query`:

- `Scan` should reuse the already-established routing and target model rather than forcing it to be invented while scan semantics are also being introduced

Testing strategy:

- add `Scan` tests that contrast table scans vs index scans
- add tests that verify `Scan` produces a distinct consumption signature from `Query`
- add regression tests ensuring `Scan` does not disturb phase-1 CRUD behavior

### 5. Accounting, Usage, And Pricing Extension

Feature goal:

- extend raw consumption events, usage rollups, and pricing support so phase-2 reads can be attributed to base tables vs GSIs vs LSIs

Why this step comes after core read semantics:

- the accounting and pricing layers should extend the concrete execution behavior, not guess at it in advance

Testing strategy:

- add accounting tests proving base-table and index usage are rolled up separately
- add pricing tests proving index-targeted read behavior produces coherent downstream estimates
- add export and bridge tests confirming phase-2 records remain stageable and dashboard-compatible

### 6. PartiQL Parser Stub

Feature goal:

- add a shallow PartiQL request surface that can parse and classify supported phase-2 request shapes without attempting full execution

Why this step is intentionally narrow:

- full PartiQL execution would materially expand scope; phase 2 only needs the parser stub so future phases have a clean place to continue from

Testing strategy:

- add parser and classification tests for accepted PartiQL request forms
- add explicit tests showing execution is stubbed or rejected in the intended way
- add tests proving PartiQL support does not silently bypass the lower-level operation model

### 7. Phase-2 Demo And Reporting Refresh

Feature goal:

- refresh the demo and reporting path so phase 2 can visibly show index-aware read behavior

Why this step comes last:

- the demo should reflect the actual implemented phase-2 behavior rather than lead the implementation

Testing strategy:

- add one end-to-end phase-2 scenario that includes:
  - base-table writes
  - index-aware reads
  - staged output through Postgres
  - dashboard-visible separation of read behavior

## Testing Strategy By Stage

The overall phase-2 testing strategy should stay layered:

- operation specs for `Query` and `Scan`
- composition tests for table-plus-index routing and write propagation
- accounting and pricing tests for target-aware usage totals and estimates
- regression tests keeping phase-1 CRUD, usage, pricing, and demo paths green
- one end-to-end phase-2 scenario for acceptance confidence

## Working Assumptions

- GSI and LSI support is in scope
- `Query` and `Scan` are in scope
- PartiQL is parser-stub-only in phase 2
- cross-region replication is out of scope
- phase-2 work should extend the phase-1 layers rather than bypass them
- indexes should be represented as internal graph components inside one larger table-and-indexes mono-component
- request dispatch and write propagation should be internal graph behavior, not caller-managed wiring

## Current Next Step

The recommended first implementation slice is:

- start with execution surface expansion and index-aware composition groundwork
- do not start with full `Scan` or broader demo/reporting work first

This roadmap should be the canonical planning anchor for future phase-2 requests.
