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
then isolates and boosts a sustained-hot partition, relieving the throttle after a lag.

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
- **D-adaptive-relief.** Adaptive capacity raises a sustained-hot partition's ceiling toward an adaptive max
  after a reaction lag (DynamoDB reacts in seconds), then relaxes — a per-partition analogue of the phase-8
  auto-scaler, but bounded `O(partitions)`.
- **D-per-partition-burst.** Phase-8 burst was *table-level*; here it is refined so each partition banks its
  own unused capacity — the refinement phase 8 explicitly deferred to this phase.
- **D-reconcile-bespoke.** Reconcile a **bespoke hot-key scenario** against the legacy model run on the same
  scenario (like the session-store / payments bespoke demos); keep the more-correct v2 and document divergences.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Partition topology + per-partition throttle | **Done** | derived `partitionCount`; per-request partition access hashed to a bounded partition set; a concentrated key throttles at `capacity/count` while the table has aggregate spare; no-access byte-identical |
| 2 | Adaptive capacity relief | Planned | a sustained-hot partition's ceiling is boosted after a lag, relieving the throttle; relaxes when load subsides |
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

### Slice 2 — Adaptive capacity relief
Detect a sustained-hot partition (a per-partition hotness window) and raise its ceiling toward an adaptive max
after a reaction lag, relieving the throttle; relax when the partition cools — a bounded per-partition analogue
of the auto-scaler.

**Validated by:** unit tests — a partition hot for the reaction window gets boosted (throttle relieved); it
relaxes when load subsides; the ceiling never exceeds the adaptive max; adaptive-off byte-identical.

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
