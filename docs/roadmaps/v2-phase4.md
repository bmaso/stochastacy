# v2/phase4 — Thermostat single-table demo (single-region)

**Status: COMPLETE** — seven slices (1–5, 6a, 6b), all delivered. The v2 single-region Thermostat-fleet demo
reconciles with the legacy demo within ~2 % on every dimension (writes, reads, storage, cost). The
thermostat-fleet family begins on the v2 core, and the thermostat *domain* (behavior + workload) it
introduces is reused by every later phase (multi-table, capstone, multi-region).

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

- **DD-system-error (Slice 3/6a)** — the legacy `systemErrorRate = 0.001`. Originally deferred (model
  all-success, 0.1% inside tolerance); **resolved in Slice 6a** by modeling it faithfully with an inbound
  `ChaosGate` (`Interface.wrap`), which reproduces the legacy "no capacity, no state" semantics and removes
  the gap entirely.
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
| 6a | System-error gate (`Interface.wrap` + `ChaosGate`) | **Done** | inbound chaos gate rejects ~`systemErrorRate` (0.001) with `SystemErrorResponse`; no capacity/state; 113 tests |
| 6b | Reconciliation gate + docs + close-out | **Done** | clean equivalence vs captured legacy baseline — all metrics within ~2% (reads did NOT diverge); README.thermostat-v2 + catalog; 121 tests; phase COMPLETE |

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

### Slice 6a — System-error gate (`Interface.wrap` + `ChaosGate`)

Model DynamoDB's intrinsic transient-failure rate (legacy `systemErrorRate = 0.001`) by wrapping the
`DynamoDbTable` component with a load-independent `ChaosGate` on its inbound inlet — the first use of the
`Interface` decorator on an AWS table. A rejected request produces a `SystemErrorResponse` and never
reaches the table, so it consumes no capacity and mutates no state — exactly the legacy semantics
(`op_events.scala`: "no capacity is consumed and no state is mutated"). This removes the deferred
reconciliation gap (**DD-system-error**) and de-risks Phase 6's throttling gates (same machinery, simpler
load-independent case).

**Validated by:** component-level tests (reject ~`rate` with a `SystemErrorResponse`, 1:1 request/response,
rejected requests bill nothing so consumption scales with the admitted fraction, determinism) + a
runner-level monotonicity test (higher error rate → lower total WCU); order-tracking gates unchanged.

**Delivered.** New `SystemErrorResponse` variant on the `DynamoDbResponse` ADT (was non-error-only).
`SingleTableScenario` gains `systemErrorRate: Double = 0.0`; `ThermostatConfig` overrides it to `0.001`
(so `singleRegionDefault` carries the legacy value). `SingleTableTrialRunner` derives a third (gate) seed —
`SeedSequence.derive(seed, 3)` shares its first two elements with the old `derive(seed, 2)`, so the
workload/table seeds are unchanged — and wraps the table graph with `Interface.wrap(table,
ChaosGate.constant(rate, SystemErrorResponse), gateRng)` **iff `systemErrorRate > 0`** (so order-tracking,
rate 0, keeps the exact unwrapped graph and RNG stream). New `SystemErrorGateSpec` (component-level, empty
indexes → one write per admitted put) + a `ThermostatFleetTrialSpec` monotonicity case. `aws` 113 tests
green; both order-tracking gates unchanged; whole build compiles (no ADT-exhaustiveness warning); no
core-engine or legacy file touched.

### Slice 6b — Reconciliation gate + docs + close-out

Capture the legacy `singleRegionDefault` baseline (100 trials × 1200 ticks) and reconcile v2 (overall +
per-GSI RCU / WCU / cost within tolerance — the read model is faithful, so ≈ phase-1's ~2%; storage per the
initial-storage correction). Docs: a thermostat demo guide (`specs/README.thermostat-v2.md`) + a catalog
note that the table is now exercised with mixed projections. Roadmap + memory close-out.

**Validated by:** the reconciliation passes with measured gaps reported; a reviewer can understand and run
the thermostat demo; phase COMPLETE.

**Delivered.** Legacy baseline captured (100 × 1200) via `ThermostatFleetBridge generate --mode
single-region`; the legacy `DemoMetric.exportName` names match the v2 `MonteCarloAggregation` names exactly,
so the baseline maps 1:1 and is pinned in `ThermostatFleetReconciliationSpec` (aws can't reference legacy).
**Outcome: a CLEAN EQUIVALENCE, not a reconciliation-with-divergence** — every dimension within ~2 %: TotalWCU
−0.18 %, per-GSI WCU ≤0.2 %, TotalRCU +0.47 %, per-GSI RCU customer-devices −0.5 % / fleet-alerts +1.2 %,
FinalStorageBytes +0.11 %, TotalEstimatedCost −0.18 % (bands: WCU/storage/cost 3 %, RCU 5 %; device-status
RCU asserted exactly 0). **FINDING — the anticipated GSI-read divergence did NOT materialize** (revises
D-faithful-reads): v2 charges reads for each GSI's *projected* bytes (KeysOnly ≈128 B, Include ≈192 B) vs the
legacy base 300 B, but the reads are small enough that RCU rounding (4 KB blocks × eventual-consistency
halving) absorbs the difference — so reads reconcile, unlike phase-3's unbounded scans. The Slice-6a
system-error gate closed the last write-path gap (no deferred 0.1 %). Pricing rates verified identical
(`OnDemandPricing.phase1Default` == legacy `phase1Default`). Immaterial differences documented not gated
(constant-vs-±25 % item bytes → same 1 WCU/item + storage; inert vortex `affectedFraction`). Full run is
FAST (~21 s), not slow — no reduced-scale fallback needed. Docs: new `specs/README.thermostat-v2.md`;
`specs/aws-component-catalog.md` updated (realized `Interface.wrap`/`ChaosGate` decoration; mixed-projection
exercise; stale `outcomeFor` signature fixed to include `tick`). `aws` 121 tests green.

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
