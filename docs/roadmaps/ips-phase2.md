# IPS Phase 2

## Goal

Extend the simulator from a table-only DynamoDB model into a demo-usable **table-and-indexes** model that can estimate usage and cost ranges for a table that has indexes.

Phase 2 should be treated as an incremental continuation of phase 1, not as one giant implementation task.

## Scope And Non-Goals

Phase 2 is in scope for:

- global secondary indexes
- local secondary indexes
- `Query`
- `Scan`
- index-aware resource consumption, usage aggregation, and pricing
- demo-ready indexed-table simulation
- a public **table-and-indexes** graph component

Phase 2 is out of scope for:

- cross-region replication
- global table behavior
- replicated cross-region index update behavior
- full PartiQL execution semantics

PartiQL remains in scope only as a parser and request-classification stub.

## Architectural Direction

Phase 2 should now be planned around one explicit architectural commitment:

- the public simulator surface should expose one **table-and-indexes** graph component
- GSIs and LSIs should be represented as internal execution units inside that component
- callers should not construct or wire separate public index graph components
- request dispatch and write propagation should be internal graph behavior

This matters because the end-of-phase demo is no longer just "table simulation plus a few extra operations." It is an indexed-table simulation that can produce credible usage and cost ranges for a table whose writes and reads interact with indexes.

## Phase-2 Development Steps

Future implementation work should be planned and executed one numbered step at a time.

### 1. Execution Surface Foundation

Feature goal:

- establish the public request and response surface for index-aware read operations without implying that real execution already exists

Why this step comes first:

- later composition, propagation, and read-path work need a stable public request surface before deeper behavior can be added

Testing strategy:

- add compatibility tests proving phase-1 CRUD behavior remains unchanged
- add request-shape tests that distinguish:
  - base-table reads
  - index-targeted reads
  - unsupported PartiQL execution paths
- keep explicit fail-fast behavior for unsupported phase-2 reads

### 2. Public Table-And-Indexes Component

Feature goal:

- introduce one public **table-and-indexes** graph component that callers use instead of manually assembling table plus index graph pieces

Why this step comes second:

- later `Query`, `Scan`, accounting, and demo work should all be built on the real public component shape rather than on a temporary composition assumption

Testing strategy:

- add composition tests proving:
  - the public component can be instantiated with no indexes
  - it can be instantiated with GSIs and LSIs
  - base-table-targeted and index-targeted requests are dispatched internally to the correct execution path
- add compatibility tests proving a table-only configuration still behaves like the phase-1 path

### 3. Internal Index State And Write Propagation

Feature goal:

- make the table-and-indexes component actually own index-facing state and propagate base-table writes into the relevant internal index execution units

Why this step comes before real index-aware reads:

- a useful phase-2 demo needs indexes that are updated by writes, not just syntactically targetable indexes
- `Query` against an index is not credible if the simulator has not first established how that index tracks the table

Testing strategy:

- add propagation tests showing:
  - base-table writes affect the correct internal indexes
  - unaffected indexes remain unchanged
  - table and index consumption can be separated downstream
- add regression tests proving base-table CRUD responses remain stable

### 4. `Query`

Feature goal:

- add real `Query` execution for:
  - the base table
  - GSIs
  - LSIs

Current status:

- complete for the first summary-oriented slice
- `QueryRequest` now carries explicit `readConsistency`
- `QueryResponse` now reports evaluated vs returned item and byte totals
- query execution remains intentionally opaque and usecase-driven rather than exposing typed key-condition or filter models
- read consumption is derived from evaluated bytes
- GSI queries are eventually-consistent only

Why this step comes before `Scan`:

- `Query` is the tighter and more index-native read path, so it should lead the read-path expansion once index ownership and propagation exist

Testing strategy:

- add focused `Query` tests for:
  - base-table targets
  - GSI targets
  - LSI targets
  - empty vs non-empty results
  - target-specific read and byte-consumption behavior
- add integration tests proving `Query` works through the public table-and-indexes component rather than bypassing it

### 5. `Scan`

Feature goal:

- add real `Scan` execution for the base table and supported index targets

Why this step follows `Query`:

- `Scan` should reuse the already-established component composition, target selection, and index-state ownership rather than inventing them while scan semantics are also being introduced

Testing strategy:

- add `Scan` tests contrasting:
  - table scans vs index scans
  - scan vs query consumption signatures
- add regression tests ensuring `Scan` does not disturb:
  - phase-1 CRUD behavior
  - earlier phase-2 `Query` behavior

### 6. Accounting, Pricing, Export, And Reporting Extension

Feature goal:

- make the downstream layers fully aware of index-targeted and propagated index activity so the simulator can support a phase-2 demo with indexed tables

Why this is a major phase-2 step:

- this is where the indexed-table behavior becomes operationally and financially meaningful rather than just behaviorally richer

Testing strategy:

- add accounting tests for separate table and index rollups
- add pricing tests for indexed read paths
- add export and bridge tests proving phase-2 records stage and visualize cleanly
- add acceptance checks confirming the simulator can produce usage and cost ranges for an indexed-table scenario

### 7. PartiQL Parser Stub

Feature goal:

- add a shallow PartiQL parser and classification layer without full execution semantics

Why this step is intentionally late and narrow:

- it is not required to make the indexed-table demo real
- it should not distract from establishing actual index-aware execution and costing first

Testing strategy:

- add parser and classification tests for accepted phase-2 forms
- add explicit unsupported-execution tests
- add tests proving PartiQL does not bypass the lower-level operation model

### 8. Phase-2 Demo Finalization

Feature goal:

- finish phase 2 with one strong indexed-table demo path using the public table-and-indexes component

End-of-phase expectation:

- the demo should show a table that has indexes
- writes should update table and index state coherently
- reads should be able to target the base table or indexes
- the system should estimate usage and cost ranges over multiple trials
- staged, exported, and dashboarded outputs should preserve table-vs-index visibility

Testing strategy:

- add one end-to-end acceptance path that includes:
  - base-table writes
  - index propagation
  - index-targeted reads
  - staged output through the Postgres bridge
  - dashboard-visible usage and cost ranges for the indexed-table scenario

## Testing Strategy By Stage

The overall phase-2 testing strategy should stay layered:

- surface tests for request and response types plus explicit unsupported handling
- composition tests for the public table-and-indexes component, internal dispatch, and merged outputs
- propagation tests for base-table writes affecting internal index state correctly
- operation specs for `Query` and `Scan` on both base-table and index targets
- accounting, pricing, export, and reporting tests for target-aware usage totals and coherent cost estimates
- regression tests keeping phase-1 CRUD, usage, pricing, export, and demo paths green
- one indexed-table scenario for end-to-end demo acceptance

## Working Assumptions

- GSI and LSI support is in scope
- `Query` and `Scan` are in scope
- PartiQL is parser-stub-only in phase 2
- cross-region replication is out of scope
- phase-2 work should extend the phase-1 layers rather than bypass them
- the public simulator surface should expose one table-and-indexes graph component
- indexes should be represented as internal execution units, not separately wired public graph components
- request dispatch and write propagation should be internal graph behavior
- the end-of-phase demo must be able to estimate usage and cost ranges for a table that has indexes

## Current Next Step

The recommended next implementation slice is:

- implement `Scan` on top of the now-established table-and-indexes component, internal index state ownership, and real `Query` behavior
- do not jump ahead to broader reporting work or PartiQL behavior before `Scan` is in place

This roadmap should remain the canonical planning anchor for future phase-2 work.
