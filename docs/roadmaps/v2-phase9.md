# v2/phase9 — Thermostat-fleet capstone (single-region)

**Status: PLANNED** — four slices (+ a close-out coda). The **integration proof** of the v2 AWS line: the full
**4-table thermostat fleet** on one region, reconciled against the legacy `ThermostatFleetCapstoneConfig`. It
opens with the last **missing legacy feature** (PITR), then assembles the fleet and lands the **two** reconciles
phase 7 deferred here (TTL and transactions), plus the phase-8 auto-scaling/burst reconcile.

Follows `v2/phase8` (burst + auto-scaling). Single-region; multi-region is phase 11. This is where the phase-6/7/8
deferred reconciles converge on one scenario.

## Goal

Reproduce the legacy 4-table capstone (`ThermostatFleetCapstoneConfig`, single-region) on the v2 core and
reconcile it per table:

- **Registry** — on-demand + GSIs, read-heavy (composes already-reconciled features);
- **Telemetry** — provisioned + **burst + auto-scaling + TTL(720) + PITR**, under the polar-vortex + alert-storm
  workload (the reconcile-heavy table);
- **Commands** — on-demand, **transactions** (`TransactWriteItems`, command dispatch + audit) — its *first*
  legacy reconcile (the payments demo was bespoke);
- **Alerts** — on-demand, storm + vortex spike (composes already-reconciled features).

The fleet is **fixed at 50 000 devices** (`deviceGrowthPerTick = 0`), so auto-scaling is driven by the
**polar-vortex** (5× writes on 40 % of the fleet, ticks 600–700) and alert storms — a different regime from the
phase-8 growth-driven demo. Full run: 100 trials × 1440 ticks.

## Confirmed decisions

- **D-pitr-mechanism (opening slice).** The legacy telemetry table sets `pointInTimeRecoveryEnabled = true`;
  v2 does not model PITR, so the Telemetry **cost** reconcile would otherwise diverge by the continuous-backup
  cost. PITR is the last missing legacy throughput/durability feature — modeled here as a contained
  storage-cost dimension (byte-ticks × a PITR GB-month rate), not deferred.
- **D-two-reconcile-targets.** **Telemetry** (TTL + auto-scaling + burst + PITR) **and Commands** (transactions)
  are both first-time reconcile targets; Registry and Alerts compose already-reconciled features. "Mostly
  assembly" would undersell it — two of four tables carry unreconciled features.
- **D-burst-on-telemetry.** Telemetry sets `burstWindowTicks = 300` to match the legacy's always-on provisioned
  burst (its absence did not perturb the phase-6 mixed-mode reconcile, but it is enabled here for fidelity).
- **D-autoscale-reconcile-risk.** The v2 auto-scaler is a pure `onTick` port of the legacy actor/stream; the
  **capacity-trajectory match** under the vortex is the phase's key reconcile risk. Where v2 is more correct,
  keep it and document the divergence (the phase-2/6 posture — do not reproduce legacy bugs).

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | PITR mechanism | **Done** | continuous-backup cost = storage byte-ticks × PITR rate when enabled; `TotalPitrCost` surfaced only when on; PITR-off byte-identical |
| 2 | Commands: transactions in the thermostat domain | **Done** | a thermostat commands table bills the 2× premium on base+LSI (GSI 1×) vs equivalent singles; determinism |
| 3 | 4-table capstone scenario + demo | **Done** | `ThermostatMultiTableConfig.capstoneDefault` (4 tables) + `@main` + per-table provisioned/PITR/GSI surfacing + smoke test |
| 4 | Legacy reconcile | **Done** | per-table reconcile vs `ThermostatFleetCapstoneConfig`: RCU tight (~2%), Registry/Alerts ~8%, Commands/Telemetry documented divergences (v2 improvements) |
| 4-coda | Phase close-out | Planned | roadmap COMPLETE header, CLAUDE.md, program roadmap, memory-complete, full `sbt test` |

## Slices

### Slice 1 — PITR (Point-In-Time Recovery)
The last missing legacy feature. A `pointInTimeRecoveryEnabled` flag (intrinsic table config: `DynamoDbTable.Config`
+ `TableSpec` + `SingleTableScenario`); a `pitrGbMonthPrice` on `Rates`/`Pricing`; the accounting accrues a
**continuous-backup** cost = the table's storage byte-ticks × the PITR rate (PITR backs up base + indexes, which
the existing byte-ticks already total), added to `TotalEstimatedCost` and surfaced as its own metric. No capacity
consumed. `false` = off = byte-identical.

**Validated by:** unit tests — PITR cost = byte-ticks × rate; a table with PITR off is byte-identical; the cost
surfaces only when enabled.

**Delivered.** `Rates.pitrStoragePricePerGiBSecond` (= $0.20/GiB-month, the legacy rate) + `Pricing.pitrCost`.
`TrialSummary.totalPitrCost`; `TrialAccountingState` gained a `pitrEnabled` flag and folds
`byteTicks × PITR rate` into `totalEstimatedCost` (summary + the per-tick cumulative series). Config threaded on
the **accounting path only** (not `DynamoDbTable.Config` — PITR is no table mechanic): `SingleTableScenario`
(`pointInTimeRecoveryEnabled` + `usesPitr`) → `TableSpec` → `TableLegRunner`. Surfacing mirrors provisioned:
`pitrSummaryMetrics` + `hasPitr(trials)` (batch) / `scenario.usesPitr` (streaming) / `JsonlExport` flag → the
`TotalPitrCost` metric appears only when enabled (PITR-off byte-identical). Tests: `TrialAccountingSpec` (+2:
cost folded / off-identical, priced at the per-GiB-second rate), `MonteCarloAggregationSpec` (+1: surfaced only
when incurred). Full `sbt test` green: **core 512 / aws 227 / examples 244**; every prior spec byte-identical.

### Slice 2 — Commands: transactions in the thermostat domain
Model the Commands table's workload: a device-command **dispatch + audit** written as a 2-item
`TransactWriteItems` (`transactWriteItemsPerItemBytes = Vector(200, 150)`), reusing the phase-7 transaction
mechanics. Extend the thermostat behavior/workload to emit transactional commands (Registry and Alerts reuse the
existing telemetry-style put/query/scan behavior with per-table config — no new behavior).

**Validated by:** unit/end-to-end tests — the commands table bills the ≈2× transaction premium over equivalent
singles; determinism.

**Delivered.** `ThermostatConfig` gained `transactWriteItemsPerItemBytes: Option[Vector[Long]]` (when set, the
write flow is transactional) + `useTransactions: Boolean` (the singles baseline for the proof — defaults keep
every existing preset unchanged). `ThermostatWorkload` branches the write flow: `None` → telemetry puts;
`Some` + `useTransactions` → one `TransactWriteItemsRequest(perItemBytes)` per command; `Some` +
`!useTransactions` → the same items as singles. `ThermostatFleetBehavior` resolves `TransactWriteItemsRequest`
→ `TransactWrite` of **inserts** (status + audit, append-only, `previousItemBytes = None`), each sized from the
configured bytes ± the telemetry variance (matching the legacy `transactWriteItems`); and — **DQ-sub-write-shape
follow-on** — in commands mode a plain `PutItem` is also an insert, so the singles baseline shares the
transactions footprint (otherwise the telemetry saturation model diverged the arms). The e2e proves the 2×
lands on the **base+LSI** portion (`total − Σ GSI`) with **GSI maintenance equal** in both arms (transactions
do not double async GSI back-fill — the phase-8 rule). Tests: `ThermostatFleetBehaviorSpec` +1,
`ThermostatWorkloadSpec` +2, new `ThermostatCommandsSpec` +3. **aws 233 green**; every existing thermostat
scenario byte-identical (`transactWriteItemsPerItemBytes` defaults `None`).

### Slice 3 — 4-table capstone scenario + demo
Assemble **`ThermostatCapstoneConfig`** as a `MultiTableScenario` (phase-5 harness) with the four tables — each a
`TableSpec` carrying its billing mode, indexes, features (Telemetry: provisioned + `burstWindowTicks = 300` +
`autoScalingPolicy` + `ttlPeriodTicks = 720` + PITR + vortex; Commands: transactions; Registry/Alerts: on-demand),
behavior, and workload. A `@main` capstone demo → per-table JSONL + a console summary; an end-to-end smoke test
(the ensemble runs, per-table metrics present, determinism).

**Validated by:** the 4-table ensemble runs to completion with per-table (`Table:<name>:…`) metrics; determinism.

**Delivered.** The **substantive fix** — per-table metric surfacing: `TableSpec` gained `gsiNames` /
`usesProvisioning` / `usesPitr` helpers; `MultiTableMonteCarloRunner` builds each table's aggregator metrics
from *its own* spec (per-GSI breakout + provisioned + PITR) instead of a shared base-only `Vector.empty`; and
`JsonlExport.tableTrialRecords` gained the per-table flags (fed from a `name → spec` map in `runToFile`). So the
provisioned + PITR Telemetry table reports its full metrics alongside its on-demand siblings. `ThermostatConfig`
gained a settable `ttlPeriodTicks` (+ `pointInTimeRecoveryEnabled`). New `ThermostatMultiTableConfig.capstoneDefault`
(4 tables tuned to the legacy: Registry on-demand read-heavy; Telemetry `Provisioned(200,200)` + burst 300 +
auto-scaling + TTL 720 + PITR + vortex + storm; Commands transactions; Alerts storm + vortex). New
`ThermostatCapstoneDemo` (`@main`, per-table console + JSONL) and `ThermostatCapstoneSpec` (smoke: 4 tables run,
Telemetry surfaces provisioned/throttle/PITR/GSI, on-demand tables do not, determinism). One phase-5 test that
asserted the *old* "no per-GSI" limitation was updated to the enriched behavior. Full `sbt test` green: **core 512
/ aws 237 / examples 244**.

### Slice 4 — Legacy reconcile + phase close-out
Reconcile the v2 capstone against the captured legacy `ThermostatFleetCapstoneConfig` baseline, **per table**:
Telemetry (TTL + auto-scaling + burst + PITR — the auto-scaling capacity trajectory is the risk), Commands
(transactions), Registry + Alerts (expected clean). Document any deliberate divergences (the phase-2/6 posture).
Then the phase close-out (roadmap COMPLETE, CLAUDE.md, program roadmap, memory, full `sbt test`).

**Validated by:** each table reconciles within tolerance or with a documented divergence; determinism; phase
COMPLETE.

**Delivered.** Both capstone configs parameterized by fleet size (`ThermostatMultiTableConfig.capstone(count)` /
`ThermostatFleetCapstoneConfig.capstone(count)`; default reduced 50 k → **5 k** — the legacy figure was arbitrary,
so the reconcile runs both sides at one CI-manageable size, no bridge flag needed). **A pre-existing legacy bug
surfaced and was fixed (Brian-approved):** the legacy capstone `generate` threw because the windowed-rollup had no
case for `TablePITRCumulativeCost` (`demo/rollup.scala`) — the capstone had never run end-to-end; a one-line
additive rollup case (same rule as `TableCumulativeEstimatedCost`) unblocked it. Baseline captured from the legacy
bridge (5 k / 30 / 1440) and pinned. New `ThermostatCapstoneReconciliationSpec`: **RCU reconciles tight (~2 %)** on
all four tables; **Registry/Alerts within ~8 %** (WCU/storage/cost — v2 a few % lower: overwrite maintains an
unchanged GSI entry as a no-op more often in a saturated fleet); **documented, bounded, directional divergences —
all v2 improvements:** Commands WCU/cost ~+8 % (transaction **LSI 2×**, AWS-accurate), Telemetry storage ~−43 %
(**TTL frees base+GSI+LSI**), Telemetry cost ~−72 % (**provisioned billing by reservation**, the mixed-mode
pattern), Telemetry WCU ~+15 % (TTL keeps the fleet below saturation → more inserts). **Speed: v2 ~24 s vs legacy
~165 s (≈7× faster).** Full `sbt test` green: **core 512 / examples 244 / aws 241**.

## Scope boundary

Single-region (multi-region is phase 11). No hot-partition / adaptive capacity (phase 10) — the capstone telemetry
is device-keyed and well-distributed, so it does not exercise hot partitions. PITR is modeled as a cost dimension
only (no restore/backup operations). The capstone is the single-region integration proof; the cross-region
capstone is phase 11.
