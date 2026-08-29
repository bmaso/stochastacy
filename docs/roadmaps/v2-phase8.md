# v2/phase8 — Provisioned throughput dynamics: burst capacity + reactive auto-scaling

**Status: PLANNED** — four slices. Two coupled **temporal** capacity mechanisms on the phase-6
provisioned/throttling foundation: **burst capacity** (banked unused capacity smooths short spikes) and
**reactive auto-scaling** (a rolling-utilization control loop that adjusts provisioned capacity). Burst is
built first — it is what makes the auto-scaler's *spike → lag → throttle* story faithful.

Follows `v2/phase7` (TTL + transactions). Single-region, single-table focus; the full **legacy reconcile is
deferred to the phase-9 capstone** (the phase-7 pattern), whose Telemetry table is the one place burst +
auto-scaling + TTL converge against the legacy.

## Goal

Model the two mechanisms that decide whether a spike actually throttles: **burst capacity** (unused
provisioned capacity banks up to a cap and is spent on brief spikes before throttling) and **reactive
auto-scaling** (sustained load drives `UpdateProvisionedCapacity` within `[min, max]`, with reaction-lag and
scale-up-fast / scale-down-slow). Prove them on a focused single-region provisioned telemetry demo where a
spike is absorbed by burst, a sustained shift triggers a lagged scale-up (throttling in the lag window), and
load-off triggers a slow scale-down. On-demand tables are unaffected (byte-identical).

## Design decisions (to confirm at slice planning)

- **D-burst-aggregate.** Real DynamoDB banks burst **per partition**; partitions are not modeled until
  phase 10, so phase-8 burst is a **table-level (aggregate) approximation** — banking unused *table*
  capacity. Legitimate simplification; burst is *refined* (not corrected) when hot-partition / adaptive land.
- **D-burst-cap / tick↔seconds.** The bank cap is "up to 300 s of unused capacity," so it depends on **what a
  tick represents in seconds**: cap = `per-tick-ceiling × (300 / tick-seconds)`. Pin the tick→seconds mapping
  (matching the thermostat/legacy configs) before implementing the cap. *(Open — needs a value.)*
- **D-autoscale-accounting (the crux).** Provisioned cost currently folds a **static** `billingModeAt`
  schedule; a reactive scaler chooses capacity at runtime, so the schedule fold no longer describes the
  capacity in force. **Recommendation:** the auto-scaler **emits its chosen per-tick capacity as a
  tick-boundary fact via the phase-7 `TickEmission`** (its second use), and `TrialAccounting` integrates that
  trace instead of folding the schedule. *(To confirm — shapes the accounting / pricing.)*
- **D-autoscale-config.** DynamoDB auto-scaling is `(min, max, target-utilization)` + cooldowns, superseding a
  fixed capacity within `[min, max]`. **Recommendation:** an `AutoScalingPolicy` attached to
  `BillingMode.Provisioned`; when present, `onTick` adjusts capacity within bounds *instead of* following a
  `ReconfigurationSchedule` (the two are mutually exclusive per table). *(To confirm.)*
- **D-autoscale-params.** Control-loop parameters — rolling-window length, target utilization (DynamoDB
  default **70%**), scale-up step/threshold + short cooldown, scale-down step + long cooldown — must **match
  the legacy auto-scaler** so the phase-9 Telemetry reconcile closes. Pin against the legacy config at Slice-2
  planning.
- **D-reconcile-deferred.** Phase 8 validates with unit tests + a focused single-region demo; the full legacy
  reconcile lands in the phase-9 capstone.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Burst capacity | **Done** | `ThrottleBudget` banks unused capacity up to `ceiling × burstWindowTicks`; a spike within the bank does not throttle; sustained load drains then throttles; provisioned-only (on-demand + burst-off byte-identical) |
| 2 | Auto-scaler mechanism | Planned | an `AutoScalingPolicy` + `onTick` control loop reads rolling utilization and moves capacity within `[min, max]` with lag / asymmetric cooldowns |
| 3 | Dynamic capacity → accounting | Planned | the scaler's per-tick capacity reaches the accounting (via `TickEmission`); provisioned capacity-hour cost integrates the runtime trace, not the static schedule |
| 4 | Demo + docs | Planned | focused single-region demo: spike → burst absorbs → sustained load → lagged scale-up (throttling in the lag) → slow scale-down; determinism; docs |

## Slices

### Slice 1 — Burst capacity
Extend `ThrottleBudget` from a hard per-tick ceiling into a **carry-forward accumulator**: at each tick
boundary, unused capacity (ceiling − admitted) banks toward a **cap** (`D-burst-cap`), and a tick may admit
demand up to `ceiling + banked`. Provisioned-only; on-demand and the static-schedule path are untouched.

**Validated by:** unit tests — unused capacity banks and is capped; a spike that fits within `ceiling +
banked` is admitted where a bare per-tick ceiling would throttle; a sustained over-ceiling load drains the
bank and then throttles; on-demand tables and existing provisioned reconciles stay byte-identical.

**Delivered.** `ThrottleBudget` gained `readBank` / `writeBank` (per target); `overBudget` admits up to
`ceiling + bank`; `add` now `copy`s so banks survive mid-tick; new `rollForward(provisioned, gsiNames,
burstWindowTicks)` banks each target's `ceiling − admitted` into `[0, ceiling × burstWindowTicks]` (idle GSIs
bank their own ceiling) and clears the admitted tallies. `DynamoDbTable.Config.burstWindowTicks` (default 0);
`onTick` rolls the budget forward using the just-completed tick's provisioned ceilings (before advancing the
mode), falling back to a plain reset off / on-demand. New `BurstCapacitySpec` (9: pure rollForward bank/cap/
drain + GSI, overBudget-with-bank + add-preserves-bank, sampler-level spike-admit / cap / drain-then-throttle
/ burst-off byte-identical / on-demand ignores burst). **aws 209 green**; every phase-6 throttling and
reconciliation spec byte-identical (burst defaults off).

### Slice 2 — Auto-scaler mechanism
An `AutoScalingPolicy` (`min`, `max`, `targetUtilization`, window + cooldowns; `D-autoscale-config`,
`D-autoscale-params`) attached to `BillingMode.Provisioned`. `onTick` reads the tick's admitted/consumed
capacity (the phase-6 `ThrottleBudget`, before reset), maintains a rolling-utilization window in `TableState`,
and moves capacity within `[min, max]` — scale-up-fast (short cooldown) when utilization exceeds target,
scale-down-slow (long cooldown) when it falls well below.

**Validated by:** unit tests — sustained high utilization scales capacity up within bounds after the reaction
lag; sustained low utilization scales down after the longer cooldown; capacity never leaves `[min, max]`;
determinism.

### Slice 3 — Dynamic capacity → accounting
Carry the scaler's chosen per-tick capacity to the accounting (`D-autoscale-accounting`): emit it as a
tick-boundary consumption fact via `TickEmission`, and have `TrialAccounting` integrate the **runtime capacity
trace** for provisioned capacity-hour cost instead of folding the static `billingModeAt` schedule.

**Validated by:** unit tests — the reported provisioned capacity-unit-ticks / cost track the scaler's actual
per-tick capacity (not the initial or scheduled value); a non-auto-scaling provisioned table is byte-identical
to phase 6.

### Slice 4 — Demo + docs
A focused single-region provisioned **telemetry** demo (extending the phase-6 mixed-mode "right-sizing trap"
scenario is the natural base) + a `@main` + end-to-end: a brief spike absorbed by burst (no throttle), then a
sustained shift that drains the bank and triggers a lagged scale-up (throttling in the lag window), then a
slow scale-down after load-off. Docs: catalog/README notes.

**Validated by:** the demo exhibits burst absorption then lagged auto-scaling (fewer throttles than the
bare-ceiling phase-6 run under the same spike); determinism. Full legacy reconcile deferred to the capstone.

## Scope boundary

Single-region, single-table. **Table-level (aggregate) burst** — per-partition burst arrives with
hot-partition / adaptive (phase 10). Reactive auto-scaling only (scheduled scaling is already
`ReconfigurationSchedule`). No multi-region (phase 11). Full legacy reconcile of burst + auto-scaling happens
in the **phase-9 capstone**.
