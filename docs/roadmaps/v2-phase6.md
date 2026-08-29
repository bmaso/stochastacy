# v2/phase6 — Provisioned capacity + throttling (auto-scaling deferred to phase 8)

**Status: COMPLETE** — five slices delivered. Introduced the first **non-on-demand** billing mode and the
first **load-dependent** admission control (provisioned billing + throttling + scheduled reconfiguration),
reconciled against the legacy mixed-mode thermostat demo — clean on the simulation (~1 %), with the cost a
documented divergence.

Follows `v2/phase5` (multi-table composition). Everything so far has been **on-demand** billing with no
throughput cap → no throttling. This phase adds **provisioned billing** (a reserved RCU/WCU capacity billed
by capacity-hours), **throttling** (requests over the per-tick ceiling are rejected), and **scheduled
reconfiguration** (billing-mode switch + capacity update at chosen ticks). **Auto-scaling is deferred to
phase 8** (its only legacy reconcile target is the capstone, entangled with TTL/PITR/multi-table).

## Goal

Reproduce the legacy `ThermostatFleetMixedModeConfig` on the v2 core — a single-region telemetry table that
**starts on-demand, switches to provisioned, then adjusts its provisioned capacity mid-run**, throttling
when telemetry bursts exceed the (deliberately tight) provisioned ceiling. This is the legacy "right-sizing
trap": provisioning at ~110 % of the mean exposes throttle spikes that on-demand absorbed.

## The legacy target

`ThermostatFleetMixedModeConfig` (single-region, 1200 ticks × 100 trials, `systemErrorRate = 0.001`):
- Starts **on-demand**.
- `SwitchBillingMode` at tick **400** → `Provisioned(250 RCU, 125 WCU)`.
- `UpdateProvisionedCapacity` at tick **800** → `Provisioned(100 RCU, 333 WCU)`.
- **No** burst / adaptive / hot-partition / auto-scaler (all default `None`) — so throttling is the pure
  fresh-per-tick ceiling.
- Otherwise inherits `singleRegionDefault` (the phase-4 telemetry behavior/workload, 3 GSIs + 1 LSI).

Legacy fidelity notes (from the ips implementation): the base throttle is a **fresh per-tick ceiling**
(consumed resets each tick), **not** a banking token bucket — cross-tick banking is the burst model only.
Provisioned pricing is `capacity-ticks ÷ 3600 × hourly rate` (`1 tick = 1 second`), **consumption-independent**;
a mid-run switch is priced by integrating the mode in force **per tick**. Throttled requests emit a
`ThrottledResponse` and a throttle metric, consuming nothing and mutating no state.

## Confirmed decisions

- **D-throttle-internal-weighted — throttling is internal to the table, via reusable weighted accounting.**
  Not an `Interface.wrap` gate: DynamoDB throttles on **capacity units**, and a request's RCU/WCU cost is
  **state-dependent** (index maintenance differs insert-vs-update; scan/query size depends on table state) —
  computed by the table's own mechanics, so an inlet gate can't know it. The throttle is realized as a
  **reusable weighted-throttle accumulator** invoked **inside the table's `sample`, after the demand is
  computed and before consumption/state is committed** — a per-tick consumed-vs-ceiling budget, **reset each
  tick** (flat cap), weighted by the op's actual demand, run over **two dimensions** (read RCU / write WCU).
  The general form (a capacity-weighted `TokenBucketGate` with an injected `tokensRequired: Req => Double`,
  banking across ticks) is a nice **future** addition to the AWS/core component catalog — the banking variant
  is the *burst* model — but is **not required** for this ported demo (mixed-mode has no burst).
- **D-defer-autoscaling — auto-scaling moves to phase 8.** There is no isolated legacy auto-scaler reconcile
  target (the only one is the capstone device-telemetry table, entangled with TTL/PITR/multi-table). Phase 6
  is provisioned + throttling + reconfiguration, cleanly reconciled against mixed-mode; phase 8 adds the
  reactive auto-scaler when it assembles the capstone.
- **D-scope-no-burst — burst / adaptive / hot-partition / dynamic-topology are out**, along with the
  on-demand max-throughput cap. No thermostat reconcile target exercises them; they are added later only if a
  demo needs them.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Billing mode + provisioned capacity-hour pricing | **Done** | `BillingMode` + per-target capacity-hour pricing; provisioned bills capacity-hours (consumption-independent); on-demand byte-identical |
| 2 | Throttling (weighted per-tick cap, internal) | **Done** | per-target `ThrottleBudget` in `TableState`; over-ceiling → `ThrottledResponse` + `RequestThrottled` (no consumption/state); per-tick reset; on-demand byte-identical |
| 3 | Scheduled reconfiguration | **Done** | `ReconfigurationSchedule` applied at `onTick` via shared `billingModeAt`; validation (cooldown, prov-only); throttle + pricing follow the switches |
| 4 | Mixed-mode scenario + demo (end-to-end) | **Done** | `ThermostatConfig.mixedModeDefault` + `@main`; billing-mode-aware provisioned/throttle metrics surfaced (on-demand byte-identical); throttling fires under the right-sized capacity |
| 5 | Reconciliation gate + docs + close-out | **Done** | simulation reconciles ~1% vs legacy; cost a documented divergence; pricing fixed to explicit-GSI-only; docs; phase COMPLETE |

## Slices

### Slice 1 — Billing mode + provisioned capacity-hour pricing

Introduce a `BillingMode` (`OnDemand` default | `Provisioned(readCapacityUnits, writeCapacityUnits,
per-target overrides)`) into the table/scenario config. Make the shared harness accounting + pricing
**billing-mode-aware**: accumulate **provisioned-capacity-ticks** (the ceiling in force that tick, per
target) and price them as **capacity-hours** (`ticks ÷ 3600 × hourly rate`; $0.00013/RCU-hr,
$0.00065/WCU-hr), consumption-independent — while on-demand ticks keep consumption pricing. Track the mode
**per tick** (so a table on-demand for part of the run and provisioned for the rest prices correctly — the
setup for Slice 3's mid-run switch). A provisioned table still admits everything (no throttle yet).

**Validated by:** pricing unit tests (capacity-hour math); a static provisioned scenario bills
capacity-hours; every on-demand demo (order-tracking, single-region/multi-table thermostat) stays
byte-identical.

**Delivered.** New `dynamodb.BillingMode` (`OnDemand` | `Provisioned(rcu, wcu, per-GSI maps)` with base-
fallback `gsiRead/gsiWrite` + `totalRead/WriteCapacity(gsiNames)` helpers). `OnDemandPricing` **renamed to
`Pricing`** (it now prices both modes); `Rates` gains `provisionedRcuHourlyPrice`/`provisionedWcuHourlyPrice`
(AWS $0.00013/$0.00065); new `storageCost`/`consumptionCost`/`provisionedCost` helpers (`ticks ÷ 3600 ×
hourly`). `SingleTableScenario`/`TableSpec` gain `billingMode` (default `OnDemand`). `TrialAccountingState`
is billing-mode-aware: it attributes each tick to the mode in force (a run-static `provisionedPerTick`
precompute) — provisioned ticks accrue **reserved capacity-ticks** (base + every GSI), on-demand ticks the
**consumed** capacity — and prices `consumptionCost + provisionedCost + storageCost`; `TrialSummary` carries
`totalProvisioned{Read,Write}CapacityUnitTicks` (not yet a JSONL metric, so on-demand output is unchanged).
`ProvisionedPricingSpec` (5): capacity-hour formula; provisioned bills capacity-hours + storage with no
consumption component; the reservation is consumption-independent (equal across seeds); per-target (base +
2 GSIs = 3×); on-demand cost unchanged. The table is untouched (billing mode inert until Slice 2's throttle).

### Slice 2 — Throttling (weighted per-tick cap, internal admission)

Resolve **D-throttle-internal-weighted**. A **reusable weighted-throttle accumulator** (per-tick consumed vs
ceiling, reset at the tick boundary, weighted by the op's demand), held in `TableState` per dimension (read
RCU / write WCU). In the table's `sample`: draw the outcome, compute its demand via the mechanics, check the
demand against the budget — fits → commit (consumption + state); over → emit a **`ThrottledResponse`** (a new
protocol variant, à la `SystemErrorResponse`) with **no consumption and no state mutation**, plus a
throttle-count metric. Active only when the billing mode is `Provisioned`.

**Validated by:** throttle unit tests (over-ceiling → throttled; under → admitted; per-tick reset; throttled
→ zero consumption and unchanged state).

**Delivered.** New `dynamodb.ThrottleBudget` — the reusable **weighted per-tick accumulator**, per budget
target (`"base"` = base + LSI; each GSI by name), with `overBudget`/`add` against `Provisioned` ceilings.
Two new protocol variants: `ThrottledResponse` (response) and `RequestThrottled(target)` (a 0-capacity
marker on the consumption plane). `TableState` gains `perTickBudget` (reset in `onTick` alongside
`currentTick`). `DynamoDbTable.Config` gains `billingMode` (default `OnDemand`); `sample` groups the op's
demand per budget target (base + index maintenance, all at admission) and — when `Provisioned` — throttles
if **any** target would exceed its ceiling (emit `ThrottledResponse` + `RequestThrottled`, no consumption,
state/budget untouched), else admits and charges the budget. On-demand is byte-identical (no budget, no
throttle). `TrialAccounting` counts `RequestThrottled` → `TrialSummary.totalThrottledRequests` (field only).
`TableLegRunner` passes `spec.billingMode` into the table config. Decisions per Brian: **per-target**
granularity, `RequestThrottled` metric marker, all-demand-at-admission. Tests: `ThrottlingSpec`
(sampler-level — admit-to-ceiling-then-throttle, per-tick reset, **per-target GSI binds with base headroom**,
on-demand never throttles) + `ThrottlingEndToEndSpec` (tight ceiling → throttles, loose/on-demand → 0). No
mid-run switch yet (Slice 3); billing mode static.

### Slice 3 — Scheduled reconfiguration

A schedule of management events (`SwitchBillingMode`, `UpdateProvisionedCapacity`) applied at tick
boundaries (via `onTick`), with the **24 h (86,400-tick) billing-mode switch cooldown** and the
"capacity-update only while provisioned" guards. Enables the on-demand → provisioned → adjust trajectory; the
per-tick accounting (Slice 1) already follows the mode in force, so pricing tracks the switches, and the
throttle (Slice 2) follows the new ceiling.

**Validated by:** reconfiguration unit tests (mode/capacity change at the scheduled ticks; cooldown
rejection; throttling adopts the new ceiling after a change).

**Delivered.** New `dynamodb.ReconfigurationSchedule` — `ReconfigurationEvent` (`SwitchBillingMode` |
`UpdateProvisionedCapacity`) at scheduled ticks, with `billingModeAt(tick, initial)` (a pure fold — the
shared source of truth) and `validate(initial, ticks)` (horizon, 24 h/86,400-tick switch cooldown,
capacity-update-only-while-provisioned). The **current** billing mode moved into `TableState`
(`billingMode`, seeded by `TableState.initial`); `DynamoDbTable.Config` gains `reconfigurationSchedule`,
`onTick` sets `state.billingMode = schedule.billingModeAt(tick, config.billingMode)` (and still resets the
budget), and `sample` reads `state.billingMode`. The accounting takes the schedule and computes the mode
**per tick** (Slice 1's static precompute → `provisionedPerTick(tick)`), so a mid-run switch bills on-demand
ticks by consumption and provisioned ticks by capacity-hours at the respective capacities. `SingleTableScenario`
/`TableSpec` gain `reconfigurationSchedule` (default empty); `TableLegRunner` threads it to both the table and
the accounting. Tests: `ReconfigurationSpec` (validation, `billingModeAt` boundaries, table starts on-demand
then throttles after a switch and follows a later capacity widening) + a `TrialAccountingSpec` case
(mid-run switch bills each tick by the mode in force). Empty schedule ⇒ constant mode ⇒ byte-identical.

### Slice 4 — Mixed-mode scenario + demo (end-to-end)

Port `ThermostatFleetMixedModeConfig` as a v2 scenario (on-demand → `Provisioned(250,125)`@400 →
`Provisioned(100,333)`@800), assembling Slices 1–3 (provisioned pricing + throttling + reconfiguration) on
the phase-4 telemetry behavior/workload, with a `ThermostatMixedModeDemo` `@main` (a console summary showing
the capacity-hour cost and throttle counts).

**Validated by:** an end-to-end spec — the demo runs the on-demand → provisioned → adjust trajectory (the
billing mode changes at ticks 400/800), provisioned periods bill **capacity-hours**, **throttling fires**
during the telemetry bursts under the tight ceiling, and the run is deterministic. No legacy reconcile yet
(that is Slice 5).

**Delivered.** `ThermostatConfig` gains a `reconfigurationSchedule` param (validated in its `require`);
`ThermostatConfig.mixedModeDefault` = the single-region workload starting on-demand, `SwitchBillingMode`→
`Provisioned(250,125)`@400, `UpdateProvisionedCapacity`→`Provisioned(100,333)`@800. New
`ThermostatMixedModeDemo` `@main` (per-run console: consumed RCU/WCU, provisioned capacity-ticks, throttle
count, cost). **Metric-surfacing** (DQ-metric-surfacing): `MonteCarloAggregation` appends
`TotalProvisioned{Read,Write}CapacityUnitTicks` + `TotalThrottledRequests` **only when the ensemble used
provisioning** — batch derives it from the trials (`hasProvisioning`), the streaming runner from the scenario
(`SingleTableScenario.usesProvisioning`), threaded through `IncrementalAggregator` / `JsonlExport` — so
on-demand output is byte-identical (all three reconciliation gates unchanged). `ThermostatMixedModeSpec`
(small fast config): provisioned capacity reserved + throttling fires under the tight ceiling; the metrics
are surfaced for a provisioned ensemble and **absent** for an on-demand one; determinism. `@main` smoke at
1200 ticks shows the right-sizing trap (~47k throttled requests against the tight provisioned capacity).

### Slice 5 — Reconciliation gate + docs + close-out

Capture the legacy mixed-mode baseline (`ThermostatFleetBridge generate --mode mixed-mode`), pin it, and
reconcile — consumed RCU/WCU, **provisioned capacity-hour cost**, **throttle counts**, storage — within
tolerance (determined empirically, phase-4/5 style). Docs: an AWS-catalog entry for the billing mode +
throttling, a demo section; roadmap + memory close-out.

**Validated by:** the reconciliation passes with measured gaps reported; a reviewer can understand and run
the mixed-mode demo; phase COMPLETE.

**Delivered.** Legacy baseline captured (100 × 1200) via `ThermostatFleetBridge generate --mode mixed-mode`;
pinned in `ThermostatMixedModeReconciliationSpec`. **Pricing fix (DQ-granularity-final):** `BillingMode.
totalRead/WriteCapacity` now reserves `base + explicitly-provisioned GSIs` (`gsi…CapacityUnits.values.sum`,
no base fallback) — matching the legacy (line 1530 `.values.sum`) and more correct (you pay for GSI capacity
you provision); throttling keeps per-target base-fallback ceilings (already matched). **Reconciliation
outcome:** the **simulation reconciles cleanly** — consumed RCU +0.52 %, WCU −0.46 %, storage +0.10 %; **cost
is a documented divergence** (−8.6 %): v2 uses a clean per-tick billing attribution (on-demand→consumption,
provisioned→capacity-hours, no double-count), while the legacy's mixed-cost accounting is internally
inconsistent (its per-tick capacity series 244,930 ≠ its summary total 373,178) — so we keep v2's improved
model and document the gap (phase-2/3 pattern; `TotalStorageByteTicks` excluded for the same legacy
inconsistency the order-tracking gate skips). Throttle count + provisioned capacity-ticks are v2 additions the
legacy summary omits. Docs: a mixed-mode section in `specs/README.thermostat-v2.md` + `aws-component-catalog.md`
updated (billing mode / throttling / reconfiguration, scope). `aws` 170 tests green; all prior gates unchanged.

## Design principles and reuse

- **Throttling is intrinsic, not a gate** — capacity-unit throttling is coupled to the billing mode
  (intrinsic config) and the mechanics-computed demand, so it lives inside the table as reusable weighted
  accounting. The `Interface.wrap` gate family remains the tool for request-*rate* edge limits.
- **Billing mode is intrinsic table config** — like indexes; it changes how a table bills and whether it
  throttles.
- **Per-tick billing integration** — the accounting accrues by the mode in force each tick, so a mid-run
  switch prices correctly (the legacy's approach).
- **Reconcile against legacy** — the mixed-mode config isolates provisioned pricing + throttling +
  reconfiguration, the clean-reconcile discipline of every v2 phase.

## Scope boundary

Single-region, single-table; provisioned + on-demand + mid-run switch; throttling (fresh per-tick ceiling).
**No auto-scaling** (phase 8), **no** burst / adaptive-capacity / hot-partition / dynamic-topology, **no**
on-demand max-throughput cap, **no** TTL / transactions (phase 7), **no** multi-region / global tables
(phase 9). Grafana delivery + legacy retirement is phase 10.
