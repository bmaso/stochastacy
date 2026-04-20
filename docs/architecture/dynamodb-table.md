# DynamoDB Table Architecture

## Overview

A future complete DynamoDB `Table` component should be a composed Pekko graph built from smaller stages with distinct responsibilities. The goal is to model a table the same way the wider simulator models AWS resources: as a timed, stateful stream component that consumes requests and emits synchronous responses, resource-consumption events, and telemetry events.

`TableStage4` is the storage-facing core of that future `Table` simulator. It represents the part of the table that actually touches simulated storage: the place where item existence, item size, table byte totals, and direct read/write physical effects are determined.

Phase-2 step 2 now introduces `DynamoDbTable` as the first public **table-and-indexes mono-component** rather than exposing a set of separately wired public index components.

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
- `TableStage4` remains the base-table execution unit inside it
- GSI and LSI execution units are placeholder internal components
- real index state and write propagation are still deferred to the next phase-2 step

The intent is to keep graph construction safe and coherent. A caller should not need to manually wire table writes into separate public index components in order to obtain valid DynamoDB-like behavior.

## Layering

In the full `Table` component, requests would ideally flow through several conceptual layers before reaching `TableStage4`:

1. Request admission and shaping
2. Provisioned-capacity and throttling logic
3. Burst and adaptive-capacity behavior
4. Data-plane storage execution in the table-and-indexes storage layer

That storage layer should itself be internally composed of:

- base-table execution
- internal request dispatch or branching logic
- internal index execution units
- internal write-propagation logic from the base table into indexes
- merged response, consumption, and telemetry outputs

Earlier layers can model whether a request is delayed, throttled, rejected, or otherwise transformed before it reaches storage. `TableStage4` sits below those concerns. By the time a request arrives here, the simulator should treat it as an operation that has already been admitted to the table's physical data plane.

## Why TableStage4 Exists

This separation is useful because it lets the future `Table` component be composed from simpler Pekko graphs with clear boundaries. `TableStage4` can stay focused on storage semantics and physical effects, while outer stages stay focused on scheduling, admission, and capacity policy.

That makes `TableStage4` the authoritative source of truth for the question: "what would the table itself do with this request if it were allowed to execute?"

Phase 2 should preserve that idea while broadening the internal storage model. The base table and its indexes should still be treated as parts of one larger DynamoDB table resource, not as independent public resources that the caller assembles manually.

## Responsibilities Of TableStage4

`TableStage4` is responsible for:

- inspecting and possibly mutating table state
- producing the synchronous DynamoDB response for an admitted request
- emitting resource-consumption facts caused by servicing the request
- emitting telemetry and metric events that summarize what happened

It is not responsible for account-wide limits, retries, or upstream admission decisions.

## Composition Goal

A complete future `Table` component can therefore be viewed as:

`incoming table request -> admission/capacity stages -> composed table-and-indexes storage graph -> response/consumption/telemetry outputs`

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

As the model becomes more realistic, we should be able to add outer stages without needing to redesign `TableStage4` itself.
