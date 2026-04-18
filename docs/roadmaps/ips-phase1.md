# IPS Phase 1

## Goal

Deliver an initial public showing build that can simulate the predicted:

- resource usage
- cost
- performance characteristics

for a non-trivial AWS-based system.

Phase 1 is scoped to:

- DynamoDB tables
- DynamoDB indexes

## Current Status

The current codebase already includes:

- `TableStage4` support for `GetItem`
- `TableStage4` support for `PutItem`
- mutable summary-state updates for successful puts
- read-side and write-side consumption events
- a usage aggregation layer that folds raw consumption events into usage totals

The biggest remaining gaps are:

- pricing
- index modeling and composition
- broader demo scenarios and demo-facing output

## Must Have For Public Showing

### 1. End-to-End Simulation Path

- A runnable path from workload or input events to simulation outputs
- A demonstration scenario that exercises a non-trivial DynamoDB-backed system
- Outputs that can be inspected after a run without digging through internals

### 2. DynamoDB Table Core

- `TableStage4` stable for admitted data-plane requests
- `GetItem` fully modeled for hit vs miss
- Table state represented clearly enough to support read and write behavior
- Timing and control-event propagation correct on all outputs

### 3. Resource Consumption Model

- Normalized resource-consumption events for DynamoDB operations
- Enough detail to support later pricing
- Clear distinction between logical response events and accounting/resource events
- A usage aggregation layer that folds raw consumption into stable DynamoDB usage totals

### 4. Cost Derivation

- A path from resource-consumption events to cost estimates
- DynamoDB table cost coverage for the operations included in phase 1
- Output format that makes the cost story easy to explain in a demo

### 5. Performance and Timing Story

- Request and response timing represented in the simulation
- Time-window behavior coherent and testable
- A basic explanation of what "performance characteristics" means in phase 1
- One or more example scenarios showing timing-sensitive behavior

### 6. Index Support

- At least one coherent index model included in the simulation story
- Index-related resource usage represented
- Table vs index behavior explained clearly enough for a public walkthrough

### 7. Demo-Ready Outputs

- Simulation outputs that are readable by someone other than the implementer
- Metrics and events named clearly
- One concise example run that shows:
  - workload in
  - responses
  - resource usage
  - estimated cost
  - timing and performance observations

### 8. Documentation

- Architecture docs for the DynamoDB table model
- Phase-1 scope documented clearly
- Clear statement of what is modeled vs not modeled
- Short demo walkthrough notes

### 9. Test Confidence

- `sbt test` green
- Focused tests for key DynamoDB table behaviors
- Tests covering timing/control-stream correctness
- Tests covering at least the visible demo path

## Should Have Soon

### 10. Write Operations

- `PutItem`
- `UpdateItem`
- `DeleteItem`
- State-mutation tests for write paths
- Resource and metric emission for writes

Current status:
`PutItem` plus state-mutation tests and write-side resource/metric emission are already implemented.
The remaining work in this area is `UpdateItem` and `DeleteItem`.

### 11. Better Metric Model

- Additive Stage 4 metric events with stable semantics
- Metric aggregation helpers for assertions and reporting
- Demo-visible metrics aligned with the public story

### 12. Better Scenario Coverage

- At least one scenario with mixed reads and writes
- At least one scenario with meaningful item sizes, skew, or hit/miss behavior
- At least one scenario that exercises an index

### 13. Presentation Polish

- A simple command or entrypoint for running the demo
- Predictable output formatting
- A small set of example workloads or configs

## Nice To Have Later

### 14. More Realistic DynamoDB Behavior

- Richer capacity behavior
- Throttling and admission stages above `TableStage4`
- Burst or adaptive-capacity behavior
- More precise billing rules

### 15. Broader AWS System Story

- Additional AWS resource types beyond DynamoDB
- Cross-resource interactions
- Larger system-level scenario modeling

## Suggested Near-Term Order

1. Build the pricing layer on top of aggregated usage totals.
2. Define the phase-1 index story.
3. Build one strong end-to-end demo scenario.
4. Tighten docs around scope, assumptions, and outputs.
5. Keep `sbt test` green throughout.
