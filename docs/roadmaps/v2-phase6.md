# v2/phase6 — Provisioned capacity + throttling (auto-scaling deferred to phase 8)

**Status: PLANNED** — four slices. Introduce the first **non-on-demand** billing mode and the first
**load-dependent** admission control, reconciled against the legacy mixed-mode thermostat demo.

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
| 1 | Billing mode + provisioned capacity-hour pricing | Planned | pricing unit tests; provisioned scenario bills capacity-hours; on-demand byte-identical |
| 2 | Throttling (weighted per-tick cap, internal) | Planned | over-ceiling → throttled (no consumption/state); under → admitted; per-tick reset |
| 3 | Scheduled reconfiguration | Planned | mode/capacity change at the scheduled tick; 24 h switch cooldown; throttle follows new ceiling |
| 4 | Mixed-mode scenario + demo + reconciliation gate + docs | Planned | reconcile vs captured legacy mixed-mode baseline; phase COMPLETE |

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

*Sub-decisions:* per-target provisioned capacity (base + per-GSI, base as fallback) vs base-only; where the
provisioned rates live (extend `OnDemandPricing`/`Rates` vs a billing-mode-aware pricing type).

### Slice 2 — Throttling (weighted per-tick cap, internal admission)

Resolve **D-throttle-internal-weighted**. A **reusable weighted-throttle accumulator** (per-tick consumed vs
ceiling, reset at the tick boundary, weighted by the op's demand), held in `TableState` per dimension (read
RCU / write WCU). In the table's `sample`: draw the outcome, compute its demand via the mechanics, check the
demand against the budget — fits → commit (consumption + state); over → emit a **`ThrottledResponse`** (a new
protocol variant, à la `SystemErrorResponse`) with **no consumption and no state mutation**, plus a
throttle-count metric. Active only when the billing mode is `Provisioned`.

**Validated by:** throttle unit tests (over-ceiling → throttled; under → admitted; per-tick reset; throttled
→ zero consumption and unchanged state).

*Sub-decisions:* throttle granularity (base-only vs per-target — determined empirically against the
mixed-mode baseline; base + synchronous-LSI is likely the binding constraint); how the throttle count is
reported (a throttle-count fact on the consumption/metric plane, so the existing fold captures it — preferred
— vs tapping the response plane); whether GSI-maintenance overage throttles the base write (a DynamoDB
nuance — start simple, refine only if the reconcile needs it).

### Slice 3 — Scheduled reconfiguration

A schedule of management events (`SwitchBillingMode`, `UpdateProvisionedCapacity`) applied at tick
boundaries (via `onTick`), with the **24 h (86,400-tick) billing-mode switch cooldown** and the
"capacity-update only while provisioned" guards. Enables the on-demand → provisioned → adjust trajectory; the
per-tick accounting (Slice 1) already follows the mode in force, so pricing tracks the switches, and the
throttle (Slice 2) follows the new ceiling.

**Validated by:** reconfiguration unit tests (mode/capacity change at the scheduled ticks; cooldown
rejection; throttling adopts the new ceiling after a change).

### Slice 4 — Mixed-mode scenario + demo + reconciliation gate + docs + close-out

Port `ThermostatFleetMixedModeConfig` as a v2 scenario (on-demand → `Provisioned(250,125)`@400 →
`Provisioned(100,333)`@800) with a `ThermostatMixedModeDemo` `@main`. Capture the legacy baseline
(`ThermostatFleetBridge generate --mode mixed-mode`), pin it, and reconcile — consumed RCU/WCU, **provisioned
capacity-hour cost**, **throttle counts**, storage — within tolerance (determined empirically, phase-4/5
style). Docs: an AWS-catalog entry for the billing mode + throttling, a demo section; roadmap + memory
close-out.

**Validated by:** the reconciliation passes with measured gaps reported; a reviewer can run the mixed-mode
demo; phase COMPLETE.

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
