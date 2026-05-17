# Workload DSL Architecture

## Overview

The workload DSL is a YAML-based language for describing **DynamoDB workloads**: time-varying
statistical models of DynamoDB request streams. A workload description is independent of any
specific table or architecture — it describes the *shape* of traffic, not where that traffic
lands.

The DSL project's parser produces a `WorkloadTemplate` (an unbound workload) that is later *bound* to a concrete
architecture model (specific table names, GSI names, behavior sampler keys) to produce a
`WorkloadDefinition` — the existing type consumed by `WorkloadRequestStream` and all demo runners.

---

## The Statistical Model Metaphor

A workload is a **superposition of independent stochastic processes**. Each flow in a workload
is one component process: a particular DynamoDB request type, arriving at a stochastic rate,
with stochastically-sampled per-request parameters. The workload is the superposition of all
its flows.

Any stream generated from a workload is a **realization** of that superposition — statistically
consistent with the model, but not identical to any other realization, in the same sense that
a sequence of coin tosses is consistent with Bernoulli(0.5) but not determined by it.

This metaphor has three direct consequences:

1. **Workloads are table-agnostic.** The table (and usecase sampler) are bound at playback
   time, not at definition time. The same workload can be played against a dev table, a prod
   table, or a test fixture.

2. **Workloads are architecture-agnostic.** A workload that references a GSI uses a variable
   name (`$support-index`) rather than a hardcoded string. The concrete GSI name is supplied
   at bind time as part of the architecture binding.

3. **Composition is first-class.** The superposition of two sets of independent processes is
   itself a valid independent process. Combining workload A and workload B is closed
   — the result is a valid workload with no loss of information.

---

## Core Concepts

### `WorkloadTemplate` (unbound)

A `WorkloadTemplate` is the Scala type produced by parsing a named workload entry from a YAML
file. It holds:

- A vector of `RequestShapeDefinition`s, each with a rate sampler and a request shape. These
  are identical to the entries in `WorkloadDefinition.flows` except that `DynamoDbReadTarget`
  references may contain unresolved index variables.
- A `requiredBindings: Set[String]` — the set of index variable names (without the `$` prefix)
  that must be supplied to bind the template. Inferred by the parser from all `$var` references
  found in the template's flows, recursively through any included workloads.

### `WorkloadDefinition` (bound, existing)

`WorkloadDefinition(tableName, usecase, flows)` is the concrete, fully-resolved artifact that
`WorkloadRequestStream` and all runners consume. The field `flows: Vector[RequestShapeDefinition]`
holds one entry per flow — the compact statistical description of one independent arrival
process — not a materialized list of request objects.

The field is named `flows` (not `requests`) for two reasons: it aligns with the DSL concept,
and it is forward-looking — a workload may eventually produce event types other than DynamoDB
requests, making `flows` more durable than `requests`.

The rename from the prior field name `requests` is a mechanical code change delivered as part
of Slice 4 alongside the introduction of `WorkloadTemplate`.

### Binding

`WorkloadTemplate.bind(tableName, usecase, indices)` produces a `WorkloadDefinition`:

- `tableName: String` — the concrete table name. Replaces the implicit default-table slot.
- `usecase: String` — the key used to look up the `UseCaseSampler` in the runner.
- `indices: Map[String, String]` — maps each index variable name to a concrete GSI name.
  Must cover every name in `requiredBindings`.

### Flows

A **flow** is one independent stochastic process within a workload. It maps directly to one
`RequestShapeDefinition`. Flows are composable: the flows of two workloads can be merged without
conflict because they are statistically independent.

### Index Variables

Index variable references appear in the `target` field of `query` and `scan` flows. A variable
reference is a string beginning with `$`, e.g., `$support-index`. Variable names must match
`[a-zA-Z][a-zA-Z0-9-]*` after the `$`.

The set of variable names required to bind a workload is collected transitively: if workload A
includes workload B, and B references `$gsi-1`, then binding A requires supplying `gsi-1`.

---

## Composition Model

A YAML file contains a named map of workloads under the top-level `workloads:` key. Any
workload may include other workloads from the same file via `include:`. Composition is pure
flow-union: the included workload's flows are prepended to the including workload's own flows.

Circular `include:` chains are a parse error.

Cross-file references are not supported in Slice 4. All `include:` names must resolve within
the same file.

When realizing a workload for playback, the caller names which workload (by key) to resolve.
The parser collects that workload's own flows plus the flows of all transitively included
workloads (depth-first, in include-list order), deduplicating nothing — two included workloads
may both contribute flows of the same type, and that is intentional (e.g., two independent
query patterns against the same GSI).

---

## Target Rules by Flow Type

| Flow type | Default target (no `target:` field) | `target: { index: $var }` |
|-----------|--------------------------------------|---------------------------|
| `get-item` | base table | not valid |
| `delete-item` | base table | not valid |
| `put-item` | base table | not valid |
| `update-item` | base table | not valid |
| `transact-write-items` | base table | not valid |
| `transact-get-items` | base table | not valid |
| `query` | base table (primary-key query) | named GSI |
| `scan` | base table (full table scan) | named GSI |

There is no "default GSI" concept. A `query` or `scan` flow with no `target:` field targets
the base table. To target a GSI, the `target:` field is required and must name an index
variable.

---

## Scope by Slice

### Slice 4 (current)

- Single-table workloads only. No `table:` variable in flows; the entire workload targets one
  table, supplied as `tableName` at bind time.
- Stateless samplers only. `RandomBurstSampler` and other stateful samplers are not
  representable in YAML. They remain available programmatically.
- No sampler combination (`combine: product` / `combine: sum`) in value expressions. Each
  sampler parameter accepts exactly one temporal shape function or a constant.
- `target:` in `query`/`scan` flows accepts only `{ index: $var }` or is absent. Explicit
  `{ table: ... }` syntax is not supported.
- All `include:` references must resolve within the same YAML file.

### Slice 5 and later (planned)

- Sampler combination: `combine: product`, `combine: sum`, `combine: overlay` in value
  expressions.
- Stateful samplers: a small set of named stateful sampler types with dedicated YAML grammar
  (e.g., `distribution: random-burst` as a special case). Design TBD.
- Multi-table workloads: flows may carry an explicit `table: $var` field. Binding produces a
  `Map[String, WorkloadDefinition]` (one entry per table variable). Design TBD.
- Cross-file `include:` references. Design TBD.

---

## Scala Type Overview

| Type | Location | Role |
|------|----------|------|
| `WorkloadTemplate` | `core/.../workload/WorkloadTemplate.scala` | Unbound workload; holds flows with unresolved index variables; `bind(...)` method |
| `WorkloadFile` | `core/.../workload/yaml/WorkloadFile.scala` | Parsed YAML file; `Map[String, WorkloadTemplate]`; `resolve(name)` for dependency-aware lookup |
| `WorkloadDefinition` | `core/.../workload/WorkloadDefinition.scala` | Bound workload; `flows` field renamed from `requests` as part of Slice 4 |
| `WorkloadDsl` | `core/.../workload/yaml/WorkloadDsl.scala` | Parser: YAML string → `WorkloadFile` |

`WorkloadDsl.parse(yaml: String): WorkloadFile` is the public entry point. The caller then
calls `file.resolve(name)` to get a `WorkloadTemplate`, and `template.bind(...)` to get a
`WorkloadDefinition`.

---

## Relationship to `UseCaseSampler`

The `WorkloadTemplate` / `WorkloadDefinition` boundary is the **arrival** side of the
simulation pipeline. It controls which request types arrive, at what rate, and with what
per-request parameters (item sizes, read targets).

`UseCaseSampler` is the **outcome** side. Given an arrived request and current table state,
it determines what stochastically happened in storage (bytes read, hit/miss, partition access).

They meet at the `DynamoDBRequest` handoff: `WorkloadRequestStream` produces it;
`TableSamplingStage` consumes it and calls `UseCaseSampler`. The `usecase` binding supplied
to `WorkloadTemplate.bind(...)` is the key used to look up the correct `UseCaseSampler` in
the runner's `Map[Any, UseCaseSampler[TableState]]`.

See `docs/specs/workload-yaml-schema.md` for the complete YAML schema.
