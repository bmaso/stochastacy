# v2/phase10 — Hot-partition throttling + adaptive capacity

**Status: PLANNED** — four slices (+ a close-out coda). The **spatial** capacity dimension: how a table's
provisioned capacity distributes across physical partitions. A coupled pair — **hot-partition throttling** (the
problem) and **adaptive capacity** (the mitigation) — orthogonal to phase-8's *temporal* auto-scaler. The last
of the three missing legacy throughput features (after burst and auto-scaling).

Follows `v2/phase9` (the capstone). Single-region. No existing thermostat demo hot-partitions (device-keyed →
well-distributed), so this phase brings its own **bespoke hot-key scenario** and reconciles against the legacy
`HotPartitionModel` / `AdaptiveCapacityModel` there.

## Goal

Model the per-partition capacity structure DynamoDB actually has: provisioned capacity is split across
`partitionCount` physical partitions (~3000 RCU / 1000 WCU / 10 GB each), so a **hot partition** throttles when
its share exceeds the per-partition ceiling *even while the table has aggregate spare*; **adaptive capacity**
then *instantly* relieves that throttle (a hot partition may use up to the physical max, bounded by the table
total), and **split-for-heat** isolates a sustained-hot key onto its own partition.

**Modeling approach — decision A (confirmed).** Port the legacy mechanism as a **bounded per-partition
summary**: the workload/behavior emits a per-request **partition access** (a key token), the model **hashes it
to one of `partitionCount` physical partitions**, and the `ThrottleBudget` accumulates demand *per partition*
(not per key). State is `O(partitions × targets)` — keys are transient, only partition demand accumulates — so
it stays cheap at any realistic fleet size and reconciles directly with the legacy. This deliberately reuses
the legacy's structure (`resolve` + per-partition ceilings + adaptive max) so the reconcile is like-for-like.

## Design decisions (to confirm at slice planning)

- **D-partition-count (open, the key input).** `partitionCount` sets the per-partition ceilings
  (`capacity / partitionCount`), so it must be modeled. **Recommendation:** *derive* it from the table's
  provisioned capacity and storage (the greater of capacity-based and storage-based partition counts, ~3000
  RCU / 1000 WCU / 10 GB per partition), as an **evolving topology** (it grows with auto-scaling / storage) —
  matching the legacy's versioned `PartitionTopologySnapshot`. A config override stays available for tests.
- **D-partition-access (the workload addition).** The behavior/workload must emit a per-request key token
  drawn from a **key-access distribution** (uniform for a well-distributed table; skewed — e.g. a hot-fraction
  or Zipfian — for the hot-key scenario). The hash → partition mapping mirrors the legacy `resolvePartitionId`.
- **D-adaptive-relief (corrected against the AWS docs).** DynamoDB adaptive capacity is **instant and
  always-on** — *not* lagged, *not* configurable ("enabled automatically for every table… you don't need to
  explicitly enable or disable it"). A hot partition instantly borrows idle capacity, bounded by **(a) the
  table's total provisioned capacity and (b) the per-partition physical max (3000 RCU / 1000 WCU)**. Since (a)
  is already enforced by the table-level `overBudget` check, the accurate per-partition ceiling is simply the
  **physical max**. So Slice 1's *fair-share* ceiling (`capacity/count`) is the **without-adaptive** behavior
  (a comparison baseline, not real DynamoDB); the realistic default (Slice 2) is the physical-max ceiling. A
  toggle disables adaptive → back to fair-share, for the Slice-4 demo comparison. **Split-for-heat** — the
  slower, separate mechanism that *isolates* a sustained-hot item onto its own partition (full 3000/1000;
  blocked by an LSI) — is Slice 2b.
- **D-per-partition-burst.** Phase-8 burst was *table-level*; here it is refined so each partition banks its
  own unused capacity — the refinement phase 8 explicitly deferred to this phase.
- **D-reconcile-bespoke.** Reconcile a **bespoke hot-key scenario** against the legacy model run on the same
  scenario (like the session-store / payments bespoke demos); keep the more-correct v2 and document divergences.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Partition topology + per-partition throttle | **Done** | derived `partitionCount`; per-request partition access hashed to a bounded partition set; a concentrated key throttles at `capacity/count` while the table has aggregate spare; no-access byte-identical |
| 2 | Instant adaptive capacity | **Done** | per-partition ceiling = physical max (3000/1000), instant + always-on, bounded by the table check; a hot partition is relieved to the physical max; a toggle → fair-share (adaptive-off) baseline |
| 2b | Split-for-heat | Planned | a sustained-hot single key is isolated onto its own partition (full 3000/1000), increasing effective capacity for that key; blocked by an LSI |
| 3 | Per-partition burst refinement | Planned | each partition banks its own unused capacity; a per-partition spike is absorbed before throttling; table-level-only configs unchanged |
| 4 | Hot-key demo + legacy reconcile | Planned | bespoke concentrated-key scenario; per-partition throttle + adaptive relief reconcile vs the legacy `HotPartitionModel`/`AdaptiveCapacityModel` (documented divergences) |

## Slices

### Slice 1 — Partition topology + per-partition throttle
Derive `partitionCount` from capacity + storage (evolving); add a per-request **partition access** (key token)
to the behavior/workload, hashed to a physical partition; extend `ThrottleBudget` with a per-partition
sub-dimension (per target), throttling when a partition's admitted-plus-demand exceeds its per-partition
ceiling — even with table-level headroom.

**Validated by:** unit tests — a concentrated key (many requests → one partition) throttles while the table has
aggregate spare; a well-distributed workload does not; state stays `O(partitions)`; a config with no
hot-partition ceilings is byte-identical (the phase-6/8 table-level throttle path unchanged).

**Delivered.** New `PartitionTopology` (`derive(readCap, writeCap, storageBytes)` = `max(⌈RCU/3000 + WCU/1000⌉,
⌈storage/10 GiB⌉, 1)`; `partitionOf(key, count)` = `floorMod(hash, count)`). `TableBehavior` gained a defaulted
`partitionAccessFor(request, rng): Option[String] = None` (default ignores `rng` → existing behaviors
byte-identical). `ThrottleBudget` gained `readPartition` / `writePartition` (base-target, per partition) +
`partitionOverBudget` (fair-share ceiling `capacity / count`) + `addPartition`; they clear each tick via the
existing `rollForward` / `empty`. `DynamoDbTable.sample` derives the topology per request (provisioned + partition
access present), attributes the base demand to its partition, and adds the per-partition check as an *additional*
throttle constraint (admit charges the partition tally too). Tests: `PartitionTopologySpec` (5), `HotPartitionSpec`
(3, sampler-level: concentrated throttles at 800 = 4000/5 while table has 4000 spare / uniform admits more /
no-access at 4000), `BurstCapacitySpec` +2 (per-partition tally + tick reset). **aws 251 green**; every existing
scenario byte-identical. *(Base target only; adaptive relief = Slice 2, per-partition burst = Slice 3.)*

### Slice 2 — Instant adaptive capacity
Model DynamoDB's **instant, always-on** adaptive capacity: a hot partition's effective ceiling is the
**per-partition physical max (3000 RCU / 1000 WCU)**, not the fair share — instantly, no lag, no hotness window.
The table-level `overBudget` check already enforces the "bounded by table total" constraint, so the partition
check simply raises the ceiling from `capacity/count` (Slice 1's fair share) to the physical max. A config toggle
`adaptiveCapacity: Boolean` (default **on** — the realistic behavior) can be flipped **off** to restore the
fair-share ceiling, giving the Slice-4 demo its without-adaptive comparison baseline.

**Validated by:** unit tests — with adaptive on, a concentrated key admits up to the physical max (1000 WCU on
the `HotPartitionSpec` billing) bounded by the table's 4000, where Slice 1's fair share throttled it at 800;
with adaptive off it still throttles at the fair share (Slice-1 behavior); on-demand / no-partition-access
byte-identical.

**Delivered.** `DynamoDbTable.Config` gained `adaptiveCapacity: Boolean = true` (the DynamoDB default). In
`sample`, the per-partition ceiling is now flag-driven: adaptive on → the physical max
(`PartitionTopology.RcuPerPartition` 3000 / `WcuPerPartition` 1000); adaptive off → the fair share
(`capacity / count`). `partitionOf` still uses the derived `count`; only the ceiling changed — no new state, no
lag, no hotness window (the boost is intrinsic to the ceiling). The table-level `overBudget` check already
enforces the "bounded by table total" half, so the two checks together reproduce DynamoDB (throttle iff
partition > physical max **or** table > capacity). `ThrottleBudget.partitionOverBudget` was unchanged (it takes
the ceiling as a parameter — doc generalized). `HotPartitionSpec` restructured: adaptive-on concentrated key →
**1000**, adaptive-off → **800** (Slice-1 baseline), well-distributed → **> 1000** (table-bound), no-access →
**4000**. **aws 252 green**; every existing scenario byte-identical (all real behaviors return
`partitionAccessFor = None`, so the default flip is inert). *(Threading `adaptiveCapacity` into `TableSpec` +
the hot-key demo is Slice 4; split-for-heat is Slice 2b.)*

### Slice 2b — Split-for-heat (isolate frequently accessed items)
The slower, *separate* mechanism: a **sustained-hot single key** is isolated onto its own physical partition, so
its effective ceiling is the full physical max even as the table's `partitionCount` (and thus the fair share)
would otherwise shrink its share — modeled as a bounded per-key "hot set" promoted after a sustained-hotness
window. **Blocked by an LSI** (DynamoDB will not split an item collection when the table has an LSI). Bounded
state (a small hot-key set, not per-key maps).

**Validated by:** unit tests — a key hot for the sustained window is isolated (its ceiling reaches the physical
max independent of `partitionCount`); it de-promotes when it cools; a table with an LSI does not split
(byte-identical to Slice 2); the hot set stays bounded.

### Slice 3 — Per-partition burst refinement
Refine the phase-8 burst bank so each partition carries its own `[0, per-partition-ceiling × burstWindowTicks]`
bank; a per-partition spike is absorbed from that partition's bank before it throttles.

**Validated by:** unit tests — a partition banks and spends its own unused capacity; a table-level-only config
(no hot-partition model) is byte-identical to phase 8.

### Slice 4 — Hot-key demo + legacy reconcile
A bespoke **hot-key** scenario (a workload concentrating access on a small key set) + a `@main` + an end-to-end
reconcile against the legacy `HotPartitionModel` / `AdaptiveCapacityModel` on the same scenario. Document
divergences (the phase-2/6 posture). Docs.

**Validated by:** the hot-key scenario throttles on hot partitions and is relieved by adaptive capacity;
reconciles with the legacy within tolerance / documented divergence; determinism.

## Scope boundary

Single-region (multi-region is phase 11). Physical-partition granularity only (no sub-partition / item-collection
modeling beyond what the legacy has). Full legacy reconcile is against a bespoke hot-key scenario (no existing
demo hot-partitions). The close-out (roadmap COMPLETE, CLAUDE.md, program roadmap, memory, full `sbt test`) is a
separate coda.
