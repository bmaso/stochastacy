# v2/phase4 — Thermostat single-table demo (single-region)

**Status: IN PROGRESS** — six slices (Slices 1–2 done). The thermostat-fleet family begins on the v2 core,
and the thermostat *domain* (behavior + workload) it introduces is reused by every later phase (multi-table,
capstone, multi-region).

Started on branch `v2/phase4`, following `v2/phase3` (Indexed Order-Tracking). This phase ports the
single-region `ThermostatFleetScenarioConfig` — **one on-demand `device-telemetry` table + 3 GSIs + 1 LSI
+ a thermostat telemetry behavior/workload** — onto the *existing* indexed table. It is the first
thermostat scenario, and it leads the remaining program because the legacy multi-table and capstone demos
are all thermostat scenarios (so their clean reconcile needs this domain first).

## Goal

Reproduce the behavior of the legacy single-region thermostat demo on the v2 core — a fleet of IoT
thermostats writing telemetry to one on-demand table, queried by customer and scanned for fleet alerts,
across a Monte Carlo ensemble — as a new demo *domain* on the phase-3 table (Query/Scan + secondary
indexes), plus **one small table-component change** (Slice 2: threading the current tick to the behavior,
so a growing fleet can be modeled). The table's **mixed index projections** (KeysOnly / Include / All)
exercise projection-sized maintenance that order-tracking (All-only) did not.

## Confirmed decisions

- **D-domain-leads — the thermostat domain leads the program.** Porting the single-table thermostat demo
  first (no engine changes) gives the multi-table (phase 5) and capstone (phase 8) phases a legacy
  scenario to reconcile against, because those legacy demos compose thermostat tables.
- **D-shared-harness (DQ-shared-infra) — extract a shared single-table demo harness.** The
  accounting / pricing / result-types / aggregation / JSONL / Monte-Carlo machinery in `ordertracking` is
  generic single-table-demo infrastructure; move it to a neutral package `stochastacy.aws.examples.demo`
  behind a small `SingleTableScenario` trait, and refactor `ordertracking` onto it. So thermostat reuses
  it without depending on ordertracking, and phases 5/8 reuse it too. (Alternative — duplicate or
  cross-import — rejected.)
- **D-faithful-reads (DQ-read-model) — faithful read *counts*; projection-correct read *bytes*.** The
  legacy thermostat query/scan use reasonable limited/paginated *counts* (query ~2–10 items, scan ~50–250),
  which the behavior reproduces (not the phase-3 "whole target" change). But both reads target **non-`All`
  GSIs** (`customer-devices` KeysOnly, `fleet-alerts` Include(64)), so under the phase-3 routing the read
  *bytes* come from the target's **projected** state — correct, and **diverging from the legacy's base-item
  bytes**. So the Slice-6 gate is a **reconciliation** (writes/maintenance equivalent; the GSI-read
  divergence quantified), the same pattern as phase-3 — not a clean phase-1-style equivalence.
- **Carried over.** Re-create the protocol cleanly (already done — thermostat reuses the phase-3 protocol);
  demo-local reporting (now the shared harness); the legacy stays frozen, run only to capture the baseline.

## Open design decisions (resolved at each slice's plan)

- **DD-system-error (Slice 3/6)** — the legacy `systemErrorRate = 0.001`; **defer** (model all-success —
  0.1% is inside reconcile tolerance), noting the negligible gap. A `ChaosGate` wrap is the natural home
  if ever wanted (the throttling/failure phase).
- **DD-initial-state (Slice 3)** — the table's initial item count / bytes and the all-targets storage seed
  for the thermostat table (pinned from the legacy runner when implementing).
- **DD-temporal-slicing (Slice 5)** — build constant-per-device first, then layer the temporal shapes, so
  a no-spike slice reconciles a no-spike config and the temporal slice reconciles the real default.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Shared single-table demo harness (refactor) | **Done** | all 88 tests + both gates unchanged; generic harness in `demo` pkg |
| 2 | Thread the current tick to `TableBehavior` | **Done** | tick threaded but unused; all 88 tests + both gates unchanged |
| 3 | Thermostat behavior + config | **Done** | fleet-saturation insert/update (grows w/ tick); query 2–10 / scan 50–250; projection-correct read bytes; 97 tests |
| 4 | Thermostat workload + demo end-to-end | **Done** | fleet-scaled telemetry + GSI query/scan; `@main`; per-GSI incl. write-only device-status; 104 tests |
| 5 | Temporal shapes (spikes / vortex / bursts) | **Done** | morning/evening triangular spikes, vortex window, alert-storm bursts on telemetry λ; 109 tests |
| 6 | Reconciliation gate + docs + close-out | Planned | v2 vs captured legacy baseline (writes/maintenance equivalent; GSI-read divergence quantified); phase COMPLETE |

## Slices

### Slice 1 — Shared single-table demo harness (refactor)

Extract the generic infra from `ordertracking` into `stochastacy.aws.examples.demo`: `TrialAccounting`,
`OnDemandPricing`, `TrialResult` / `TrialSummary` / `TrialTimeSeriesPoint`, `MonteCarloResult`
(+ `AggregateStatistic` / aggregate types), `MonteCarloAggregation`, `JsonlExport`, and a generic
`SingleTableTrialRunner` + `SingleTableMonteCarloRunner` behind a `SingleTableScenario` trait (scenario id,
ticks / trials / parallelism, initial table state + all-targets storage seed, behavior, latency, GSIs /
LSIs, `arrivals(rng)`). Refactor `ordertracking` onto it — its config implements `SingleTableScenario`;
its behavior / workload / `@main` stay.

**Validated by:** every `ordertracking` test and both gates (equivalence + reconciliation) stay green — a
pure refactor with no behavior change.

**Delivered.** New package `stochastacy.aws.examples.demo`: `SingleTableScenario` trait (scenario id,
ensemble size, `initialTableState`, `behavior`, GSIs/LSIs, `initialStorageBytesAllTargets`, `arrivals`,
defaulted `latency`/`rates`); the generic `SingleTableTrialRunner` + `SingleTableMonteCarloRunner` (from
the order-tracking runners, now driven by a scenario); and the moved generic infra — `OnDemandPricing`,
`TrialResult`/`TrialSummary`/`TrialTimeSeriesPoint` (renamed from `OrderTracking*`), `TrialAccounting`,
`MonteCarloResult` (renamed), `MonteCarloAggregation`, `JsonlExport`. `OrderTrackingConfig` now
`extends SingleTableScenario` (adds `behavior`/`arrivals`); the two `@main`s use the generic runner; the
`TrialAccountingSpec`/`MonteCarloAggregationSpec` moved to the `demo` test package, the order-tracking
runner/gate specs kept and re-imported. `aws` 88 tests green (same count), both gates pass at their pinned
tolerances (numbers identical), JSONL shape unchanged; whole build compiles; no legacy file touched.

### Slice 2 — Thread the current tick to `TableBehavior`

A small, generic table-component change (the core engine is untouched) enabling **time-dependent
behaviors**, needed by the thermostat's growing fleet. `TableState` gains a `currentTick`, set by
`DynamoDbTableSampler.onTick` (previously a no-op) and carried across requests; `TableBehavior.outcomeFor`
gains a `tick: Long` (appended, 4th param) which the sampler passes as `state.currentTick`.
`OrderTrackingBehavior` accepts and ignores it — its draws are tick-independent.

**Validated by:** all `aws` tests + both order-tracking gates stay green (the tick is threaded but unused,
so numbers are identical).

**Delivered.** `TableState(base, indexes, currentTick = 0L)`; `DynamoDbTableSampler.onTick` sets
`currentTick`, and `sample` passes `state.currentTick` to the behavior and preserves it via `state.copy(...)`.
`TableBehavior.outcomeFor` gains `tick: Long`; `OrderTrackingBehavior` + the `DynamoDbTableSpec` stubs
updated to ignore it (`OrderTrackingBehaviorSpec` call sites pass `1L`). `aws` 88 tests green (same count);
both order-tracking gates identical; no core-engine or legacy file touched.

### Slice 3 — Thermostat behavior + config

`ThermostatConfig` implementing `SingleTableScenario` — the fleet (initial device count, growth per tick,
telemetry mean bytes + variance), the 3 GSIs + 1 LSI with their projections (`customer-devices` KeysOnly,
`fleet-alerts` Include(64), `device-status` All, `reading-type-history` LSI All), and the query / scan
rates; *no temporal params yet*. `ThermostatFleetBehavior` — telemetry **insert-or-update by fleet
saturation** `(fleetSize − itemCount)/fleetSize`, a query on `customer-devices` (~2–10 items) and a scan on
`fleet-alerts` (~50–250 items), faithful to the legacy read shapes.

**Validated by:** behavior unit tests — insert/update ratio tracks saturation, read-shape ranges, item
bytes within ±variance. Resolves **DD-system-error**, **DD-initial-state**.

**Delivered.** New package `stochastacy.aws.examples.thermostatfleet`: `ThermostatConfig` (fleet /
telemetry / read params + a `singleRegionDefault` = 3000 devices, 0.25 growth, 300 B ±25 %, 0.033
reports/device, 100 trials × 1200 ticks) `extends SingleTableScenario` — the 3 GSIs (`customer-devices`
KeysOnly, `fleet-alerts` Include(64), `device-status` All) + LSI (`reading-type-history` All),
`initialTableState = empty`, seed 0 (DD-initial-state: starts empty, fills up); `arrivals` throws until
Slice 4. `ThermostatFleetBehavior` — telemetry write is insert/overwrite by fleet saturation
`(fleetSize(tick) − itemCount)/fleetSize(tick)` (uses the Slice-2 tick; write bytes come from the request,
drawn by the Slice-4 workload), a customer-devices query (2–10) and a fleet-alerts scan (50–250), read
bytes from the **passed target** state (projection-correct). `ThermostatFleetBehaviorSpec` (9): empty →
all inserts, saturated → all overwrites, partial ≈ pNew, inserts grow with tick; query/scan ranges;
KeysOnly-projected read bytes; config indexes. `aws` 97 tests green; whole build compiles; no existing code
changed (order-tracking trivially unaffected); no legacy touched.

### Slice 4 — Thermostat workload (fleet-scaled, constant per-device) + demo end-to-end

Workload: telemetry `PutItem` at `telemetryReportsPerDevicePerTick × fleetSize(tick)` (fleet-growth-scaled,
no spikes), a GSI query (`customer-devices`), a GSI scan (`fleet-alerts`); wired through the shared harness;
a `ThermostatFleetDemo` `@main`.

**Validated by:** an end-to-end trial runs — base + 3-GSI + 1-LSI maintenance and per-GSI reporting; per-flow
means ≈ configured; determinism.

### Slice 5 — Temporal shapes

Re-create the temporal machinery in the v2 workload: triangular morning / evening spikes, a polar-vortex
window multiplier, and random alert-storm bursts (a `RandomBurstSampler` equivalent). Config gains the
temporal params; apply them to the telemetry rate.

**Validated by:** rate-profile tests — spikes peak in their tick windows, the vortex window multiplier
applies, bursts fire at ~their probability; determinism. Resolves **DD-temporal-slicing**.

**Delivered.** `ThermostatConfig` gains the temporal params (morning/evening spike multiplier + tick range,
alert-storm probability / duration / write multiplier, polar-vortex multiplier / affected-fraction / tick
range) with `singleRegionDefault` carrying the legacy values (spikes at (420,540)/(1020,1140) ×2.0, storm
0.002/30/×5.0, vortex **off** — `multiplier 1.0`). A private `baseTelemetryLambda` (`Sampler.deterministic`)
composes `reportsPerDevicePerTick × max(morningSpike, eveningSpike) × vortex × fleetSize(tick)` via
`TemporalShapeFunctions.triangularFactor`, wrapped by a public `telemetryRateSampler: RandomBurstSampler[Unit]`
for the additive alert-storm bursts — the exact legacy formula, co-located with its params (**DD-temporal-home**,
config-side). `ThermostatWorkload` samples that stateful rate, threading its `(Int, Unit)` state across the
tick loop (**DD-storm-state-threading**); reads stay constant-rate. `ThermostatWorkloadSpec` split into a
flat-path block (its `longConfig` now shaping-**off**, so the constant fleet-scaled rate still reconciles —
**DD-temporal-slicing**) and a temporal-shaping block (morning-spike ≈1.5× over window / ≈2× at centre;
vortex `1+fraction·(mult−1)` in-window and inert when off; storm bursts ≈5× on ~their expected active
fraction; determinism with shaping on). `aws` 109 tests green; both order-tracking gates unchanged; whole
build compiles; no core-engine or legacy file touched (the `TemporalShapeFunctions` / `RandomBurstSampler`
core samplers already existed).

### Slice 6 — Reconciliation gate + docs + close-out

Capture the legacy `singleRegionDefault` baseline (100 trials × 1200 ticks) and reconcile v2 (overall +
per-GSI RCU / WCU / cost within tolerance — the read model is faithful, so ≈ phase-1's ~2%; storage per the
initial-storage correction). Docs: a thermostat demo guide (`specs/README.thermostat-v2.md`) + a catalog
note that the table is now exercised with mixed projections. Roadmap + memory close-out.

**Validated by:** the reconciliation passes with measured gaps reported; a reviewer can understand and run
the thermostat demo; phase COMPLETE. **Note:** 1200 ticks × 100 trials is heavier than order-tracking's
30 × 100 — the baseline capture and reconcile run take a few minutes; if too slow for CI, reconcile at a
reduced but representative scale and say so.

## Design principles and reuse

- **No engine changes** — thermostat is a new demo domain on the phase-3 table; it exercises the existing
  Query/Scan + secondary-index machinery (now with mixed projections).
- **Shared harness** — the generic single-table demo infrastructure lives once, behind `SingleTableScenario`,
  reused by ordertracking, thermostat, and later phases.
- **Reconcile against legacy** — equivalence on the faithful path (the thermostat reads are faithful, so
  this is closer to phase-1's clean equivalence than phase-3's reconciliation), storage as the documented
  initial-storage correction.

## Scope boundary

Single region, on-demand, no throttling. No transactions, provisioned / auto-scaling, TTL, multi-table, or
multi-region — those are phases 5–9. `systemErrorRate` deferred.
