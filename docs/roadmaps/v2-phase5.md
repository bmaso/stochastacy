# v2/phase5 — Multi-table composition

**Status: PLANNED** — four slices. Compose several v2 `DynamoDbTable`s into one simulation, generalizing the
single-table demo harness into a multi-table one, and reconcile against the legacy multi-table demo.

Follows `v2/phase4` (single-region Thermostat-fleet). Phase 4 delivered the thermostat *domain* (behavior +
workload) and a shared single-table demo harness; this phase composes several of those tables side by side
— cashing in the **"table is the composable graph-level unit"** design and erecting the skeleton the 4-table
capstone (phase 8) will fill in.

## Goal

Reproduce the legacy `MultiTableScenarioConfig.twoTableDefault` on the v2 core — several independent
thermostat tables (each its own behavior + workload) run in one simulation, with **per-table reporting**
under the legacy `Table:<name>:…` metric names — and **generalize the single-table harness** so it supports
both one and N tables by *reusing* the per-table primitives, not duplicating them. No engine changes.

## The legacy target

`MultiTableScenarioConfig.twoTableDefault` (100 trials × 1200 ticks): two independent single-region
thermostat tables sharing the ensemble params, each with its own `ThermostatFleetScenarioConfig`:

| table | config |
|---|---|
| **device-registry** | low telemetry (0.005/device), high query (2.0/tick), scan 0.2 |
| **device-telemetry** | the phase-4 `singleRegionDefault` (0.033 telemetry, temporal shapes, mixed GSIs/LSI) |

The legacy `ThermostatFleetMultiTableSingleTrialRunner` runs each table as an independent
`DynamoDbTable` + workload, seeded per table (`run.seed ^ (i × 0x9E3779…)`), tags consumption with the
table name, merges, and emits **per-table metrics only** — `Table:<name>:{Read,Write}CapacityUnits`,
`Table:<name>:{StorageBytes,CumulativeEstimatedCost}` (time series) and `Table:<name>:Total…` /
`FinalStorageBytes` / `TotalEstimatedCost` (summary). **No overall cross-table roll-up, and no
per-GSI-within-table breakout.**

## Confirmed decisions

- **D-generalize-shape — shared primitives, not full unification.** Single-table demos emit *overall +
  per-GSI* metrics (no `Table:` prefix); multi-table emits *per-table* metrics. Unifying single-table as
  "multi-table with N=1" would change single-table output and break its gates / Grafana bindings. So the
  generalization is at the **composition** layer: extract a `TableSpec` (one table's name, initial state,
  behavior, indexes, latency, system-error rate, initial storage, arrivals, rates); `SingleTableScenario`
  yields one; a new `MultiTableScenario` carries a `Vector[TableSpec]`; and the per-table work **reuses**
  `TrialAccountingState`, `IncrementalAggregator`, `JsonlWriter`, and `MonteCarlo.stream`. Single-table
  stays byte-identical.
- **D-composition — N independent accounting sinks, no tag+merge.** The legacy tags consumption per table
  and merges into one stream; in v2 each table leg gets its **own** accounting fold (one
  `TrialAccountingState` per table) in one graph — no tag/merge/demux. Per-table seeds are derived from the
  trial seed.
- **D-scope-per-table-only — match the legacy output.** Per-table totals only. An overall cross-table
  roll-up and a `Table:<name>:GSI:<gsi>:…` breakout are noted as **optional future enhancements**, deferred
  to keep the reconcile clean.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | `TableSpec` + multi-table trial runner | **Done** | `TableSpec` + `TableLegRunner` (shared) + `MultiTable{Scenario,TrialRunner}`; single-table byte-identical; aws 131 tests |
| 2 | Per-table aggregation + `Table:` export + MC runner | Planned | streaming == collecting; per-table records on disk; determinism; memory flat |
| 3 | Thermostat 2-table scenario + demo | Planned | end-to-end per-table metrics for both tables; per-flow means ≈ configured |
| 4 | Reconciliation gate + docs + close-out | Planned | reconciliation passes with measured gaps; phase COMPLETE |

## Slices

### Slice 1 — `TableSpec` + multi-table trial runner

Extract a `TableSpec` — everything one table contributes to a trial (table name, `initialTableState`,
`behavior`, `globalSecondaryIndexes` / `localSecondaryIndexes`, `latency`, `systemErrorRate`,
`initialStorageBytesAllTargets`, `arrivals`, `rates`). `SingleTableScenario` yields one (its existing
fields), and `SingleTableTrialRunner`'s table-leg construction becomes a reusable helper. Add a
`MultiTableScenario` (ensemble params + `Vector[TableSpec]`) and a `MultiTableTrialRunner` that materializes
N legs in one graph — each `workload → [gate →] table` folding into its **own** `TrialAccountingState` — and
returns a `MultiTableTrialResult` (per-table `TrialResult`s), with per-table seeds derived from the trial
seed.

**Validated by:** a 2-table trial produces the right per-table results (each table's totals match a
standalone single-table run of the same spec + seed); **every single-table test and gate stays green**.

**Delivered.** New `TableSpec` (per-table unit: name, state, behavior, indexes, latency, rates,
`systemErrorRate`, initial storage, `arrivals`) and a shared `TableLegRunner.run(spec, ticks, w, t, g)` —
the old `SingleTableTrialRunner` body extracted verbatim (arrivals → frame → table `[+ ChaosGate]` →
consumption `Sink.fold`). `SingleTableScenario` gains a defaulted `tableSpec`; `SingleTableTrialRunner` is
now a thin wrapper that derives `(w, t, g) = SeedSequence.derive(seed, 3)` and delegates. New
`MultiTableScenario` (ensemble params + `Vector[TableSpec]`), `MultiTableTrialResult` (per-table
`(name, TrialResult)`, ordered), and `MultiTableTrialRunner` — one independent leg per table (its own
graph + accounting fold, no tag/merge), per-table seeds from `derive(seed, 3 × N)` sliced `(3i, 3i+1,
3i+2)`. The `derive` prefix property makes table 0's seeds equal `derive(seed, 3)` for any `N`, so a table
is independent of its companions and a one-table scenario matches the single-table runner exactly — both
proven by `MultiTableTrialRunnerSpec` (single-table equivalence, independence, per-table results,
determinism). `aws` 131 tests green; the reconciliation gate reports the same gaps (single-table
byte-identical); no engine/legacy change.

### Slice 2 — Per-table aggregation + `Table:` export + Monte Carlo runner

Aggregate **per table** across trials (reuse `IncrementalAggregator`, one per table or keyed by table name)
and add a multi-table export path emitting the `Table:<name>:…` records through the streaming `JsonlWriter`.
Add a `MultiTableMonteCarloRunner` over `MonteCarlo.stream` with a bounded-memory `runToFile` (per-table
records streamed to disk as trials complete, then per-table aggregates) plus a collecting `run` for
tests/gates.

**Validated by:** streaming `runToFile` aggregates == collecting `run` aggregates; the JSONL carries the
`Table:<name>:…` per-table records for every table; determinism; memory flat in the trial count.

### Slice 3 — Thermostat 2-table scenario + demo

Port `twoTableDefault` as a v2 `MultiTableScenario`: two `TableSpec`s built from `ThermostatConfig`
(device-registry with its low-telemetry / high-query rates; device-telemetry = the phase-4
`singleRegionDefault`). A `ThermostatMultiTableDemo` `@main` with a per-table console summary.

**Validated by:** an end-to-end run emits per-table metrics for both tables; per-flow means ≈ configured;
determinism.

### Slice 4 — Reconciliation gate + docs + close-out

Capture the legacy `twoTableDefault` baseline (100 × 1200) via `ThermostatFleetBridge generate --mode
multi-table`, pin it, and reconcile **per table** (RCU / WCU / storage / cost within tolerance). Docs: a
multi-table section in the thermostat guide (or a short `README.multitable-v2.md`) + a catalog note that the
table composes at graph level; roadmap + memory close-out.

**Validated by:** the reconciliation passes with measured gaps reported; a reviewer can understand and run
the multi-table demo; phase COMPLETE.

## Design principles and reuse

- **No engine changes** — multi-table is composition over the existing `DynamoDbTable` and the phase-4/6c
  harness primitives; indexes never appear at graph level, and now neither does per-table wiring beyond N
  parallel legs.
- **Reuse over duplication** — `TableSpec`, `TrialAccountingState`, `IncrementalAggregator`, `JsonlWriter`,
  and `MonteCarlo.stream` are shared by the single- and multi-table paths.
- **Reconcile against legacy** — per-table equivalence on the faithful path; the same clean-reconcile
  discipline as every v2 phase.

## Scope boundary

Single region, on-demand, no throttling; **independent** tables (no cross-table transactions). Provisioned /
throttling / auto-scaling (phase 6), TTL / transactions (phase 7), the 4-table capstone (phase 8), and
multi-region / global tables (phase 9) stay out. Grafana delivery + legacy retirement is phase 10.
