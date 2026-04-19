# IPS Phase 1

## Goal

Deliver an initial public showing build that can simulate the predicted:

- resource usage
- cost
- performance characteristics

for a non-trivial AWS-based system.

Phase 1 is scoped to:

- DynamoDB tables
- table-oriented CRUD operations used in the initial demo
- a table-only `order-tracking` scenario
- a runnable Docker, Postgres, and Grafana demo flow

Deferred to phase 2:

- DynamoDB indexes
- `Query`
- `Scan`
- PartiQL queries

## Current Status

Phase 1 is now functionally complete.

The current codebase includes:

- `TableStage4` support for `GetItem`, `PutItem`, `UpdateItem`, and `DeleteItem`
- mutable stochastic-summary table state for table-only CRUD behavior
- raw DynamoDB consumption events and metric events
- additive usage aggregation
- time-based storage usage aggregation
- downstream pricing from additive and time-based usage
- Monte Carlo multi-trial execution
- raw per-tick JSONL export
- derived `60s` and `300s` windowed JSONL export
- a Postgres staging bridge and schema-backed demo workflow
- a provisioned Grafana dashboard with selectable `60s` and `300s` windows

Remaining work is limited to operator notes, documentation hygiene, and small presentation polish.

## Must Have For Public Showing

### 1. End-to-End Simulation Path

- A runnable path from workload or input events to simulation outputs
- A demonstration scenario that exercises a non-trivial DynamoDB-backed system
- Outputs that can be inspected after a run without digging through internals

### 2. DynamoDB Table Core

- `TableStage4` stable for admitted data-plane requests
- `GetItem`, `PutItem`, `UpdateItem`, and `DeleteItem` modeled coherently
- Table state represented clearly enough to support read and write behavior
- Timing and control-event propagation correct on all outputs

### 3. Resource Consumption Model

- Normalized resource-consumption events for DynamoDB operations
- Enough detail to support later pricing
- Clear distinction between logical response events and accounting/resource events
- A usage aggregation layer that folds raw consumption into stable DynamoDB usage totals
- A time-based storage usage layer that derives duration-sensitive usage from timed streams

### 4. Cost Derivation

- A path from resource-consumption events to cost estimates
- DynamoDB table cost coverage for the operations included in phase 1
- Output format that makes the cost story easy to explain in a demo

### 5. Performance and Timing Story

- Request and response timing represented in the simulation
- Time-window behavior coherent and testable
- A basic explanation of what "performance characteristics" means in phase 1
- Example scenarios showing timing-sensitive behavior

### 6. Demo-Ready Outputs

- Simulation outputs that are readable by someone other than the implementer
- Metrics and events named clearly
- A runnable `generate / stage / view` workflow
- A dashboard-backed example run that shows:
  - workload in
  - resource usage
  - estimated cost
  - timing-sensitive behavior
  - variation across repeated trials

### 7. Documentation

- Architecture docs for the DynamoDB table model
- Phase-1 scope documented clearly
- Clear statement of what is modeled vs not modeled
- Demo runbook for the Docker, CLI, Postgres, and Grafana flow

### 8. Test Confidence

- `sbt test` green
- Focused tests for key DynamoDB table behaviors
- Tests covering timing/control-stream correctness
- Tests covering the visible demo path

## Phase-1 Delivered Shape

The delivered phase-1 demo now includes:

- an `order-tracking` table-only scenario
- host-side CLI subcommands:
  - `generate`
  - `stage`
  - `view`
- Postgres-backed staged record families:
  - raw trial time-series
  - raw aggregate time-series
  - trial summary
  - aggregate summary
  - trial window time-series
  - aggregate window time-series
- a provisioned Grafana dashboard that reads the staged records through Postgres
- percentile-band read/write panels over windowed per-trial data
- central-range summary presentation for total cost and final storage

## Nice To Have Later

### 9. More Realistic DynamoDB Behavior

- Richer capacity behavior
- Throttling and admission stages above `TableStage4`
- Burst or adaptive-capacity behavior
- More precise billing rules

### 10. Broader AWS System Story

- Additional AWS resource types beyond DynamoDB
- Cross-resource interactions
- Larger system-level scenario modeling

## Next Planning Anchor

Phase 2 should now be the planning anchor for new feature work:

- DynamoDB indexes
- `Query`
- `Scan`
- PartiQL queries
- table-plus-index composition
