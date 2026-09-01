# v2/phase10 — Hot-partition throttling + adaptive capacity

**Status: IN PROGRESS** — slices 1 / 2 / 2b done, Slice 3 dropped, Slice 4 next (+ a close-out coda). The **spatial** capacity dimension: how a table's
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
total), and **split-for-heat** splits a sustained-hot partition's key range across more partitions.

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
  slower, separate mechanism that splits a sustained-hot partition's key range across more partitions
  (permanent; single-item isolation is only the limit case; the LSI limitation governs sort-key splits below
  our partition-key granularity, so it is documented, not gated) — is Slice 2b.
- **D-per-partition-burst.** Phase-8 burst was *table-level*; here it is refined so each partition banks its
  own unused capacity — the refinement phase 8 explicitly deferred to this phase.
- **D-reconcile-bespoke.** Reconcile a **bespoke hot-key scenario** against the legacy model run on the same
  scenario (like the session-store / payments bespoke demos); keep the more-correct v2 and document divergences.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Partition topology + per-partition throttle | **Done** | derived `partitionCount`; per-request partition access hashed to a bounded partition set; a concentrated key throttles at `capacity/count` while the table has aggregate spare; no-access byte-identical |
| 2 | Instant adaptive capacity | **Done** | per-partition ceiling = physical max (3000/1000), instant + always-on, bounded by the table check; a hot partition is relieved to the physical max; a toggle → fair-share (adaptive-off) baseline |
| 2b | Split-for-heat | **Done** | a partition sustained-hot for `windowTicks` splits (permanent effective-count bump, capped), re-hashing a hot key range across more partitions so it escapes a single partition's physical max toward the table total; a lone key can't spread |
| 3 | Per-partition burst refinement | **Dropped** | subsumed by the corrected instant-adaptive model (per-partition ceiling already = physical max); table-level burst (phase 8) already covers table-total spikes — see below |
| 4a | Hot-key demo | **Done** | bespoke standalone concentrated-key scenario + `@main`; three arms (hot adaptive-on / hot adaptive-off / well-distributed control) showing per-partition throttle, adaptive relief, split-for-heat growth; `HotKeySpec` (5) |
| 4b | Hot-key hybrid reconcile | **Done** | control arm tight vs the table-level-only path (transitive to the phase-6/8 legacy reconcile); hot arm directional (on ≪ off, count grows) + documented analytical comparison to the legacy lagged model; `HotKeyReconciliationSpec` (3) |
| 4c | Docs | **Done** | `aws-component-catalog` hot-partition/adaptive/split entries + burst-dropped + scope; `README.hot-key`; CLAUDE.md workflow entry |

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

### Slice 2b — Split-for-heat (partition splitting on sustained heat)
The slower, *separate* mechanism, verified against the AWS docs: DynamoDB splits a **hot partition's key
range** into child partitions, redistributing a *subset of items* into each, so the heat spreads across more
partitions (each still capped at the physical max). Single-key isolation is only the *limit* case, and splits
are **permanent** (never merged). Modeled at partition-key granularity as a **permanent bump to the effective
partition count**: on `windowTicks` consecutive ticks of a partition at/above the physical-max trigger, grow
the effective count by one (capped), so a hot range of many keys re-hashes across the split-created partitions
and escapes a single partition's physical-max ceiling toward the table total; a lone super-hot key can't spread
(the AWS single-item limit). A faithful analogue of the legacy `maybeGrowTopology` (`partitionCount += 1` on
`consecutiveHotTicks ≥ window`) → a clean Slice-4 reconcile. **LSI limitation** — AWS blocks splits *within an
item collection* (sort-key granularity) under an LSI; that is *below* this partition-key-granularity model, so
it is documented as a scope boundary, **not** gated.

**Validated by:** unit tests — a partition saturated for the window splits (permanent count bump); it keeps
splitting under sustained heat, capped at `maxPartitionCount`; a cool tick resets the counter; a table-level
(not partition) bottleneck does not split; with more partitions a hot key range admits beyond a single
partition's physical max; no policy is byte-identical to Slice 2; a policy without adaptive capacity is rejected.

**Delivered.** New `HeatSplit.scala`: `HeatSplitPolicy(windowTicks, maxPartitionCount, read/writeTriggerPerPartition
= physical max)`, threaded `HeatSplitState(bump, consecutiveRead/WriteHotTicks)`, and the pure `HeatSplit.step`
tick-boundary transition (reads the completed tick's max per-partition admitted demand → increments/resets the
sustained counters → splits, capped, resetting the window). `TableState` gained `heatSplit: HeatSplitState`;
`DynamoDbTable.Config` gained `heatSplitPolicy: Option[HeatSplitPolicy] = None` + `require(heatSplitPolicy.isEmpty
|| adaptiveCapacity)`. `sample` uses the effective count `min(derive + bump, cap)` for `partitionOf`; `onTick`
calls `HeatSplit.step` (provisioned + policy). `HeatSplitSpec` (9 tests) proves the above. **aws 261 green**; every
existing scenario byte-identical (`heatSplitPolicy = None`, no real behavior emits partition access). *(Threading
into `TableSpec` + the hot-key demo + a topology-change metric is Slice 4.)*

### Slice 3 — Per-partition burst refinement — **DROPPED**
Originally: refine the phase-8 (table-level) burst bank so each partition banks its own unused capacity and a
per-partition spike is absorbed before it throttles.

**Why dropped.** Verified against the AWS burst/adaptive docs. Per AWS, burst capacity is banked unused
capacity that lets a partition briefly exceed its *allocation*, but **never the per-partition physical max
(3000 RCU / 1000 WCU)**; the doc's own example shows a hot partition first spending burst, then having its
ceiling raised by adaptive capacity. This slice was scoped in phase 8 under the *old, lagged* adaptive
assumption (a partition sat at its fair-share ceiling until adaptive reacted minutes later, and per-partition
burst filled that gap). Our corrected Slice 2 makes adaptive **instant and always-on**, so the per-partition
ceiling is *already* the physical max — the fair-share → physical-max gap that burst filled is closed
instantly, and burst cannot raise a partition past the physical max. So at the per-partition level burst is
**subsumed by instant adaptive**; table-total spikes remain covered by the **table-level** burst bank shipped
in phase 8. Per-partition burst would bite only in the *adaptive-off* baseline (a teaching configuration, not
real DynamoDB), which does not justify the machinery. The phase therefore models the complete, accurate
picture with slices 1 / 2 / 2b + phase-8 table-level burst, proven by the Slice-4 demo.

### Slice 4 — Hot-key demo + hybrid reconcile + docs
Split into three committed sub-slices (as Slice 2/2b were). A **bespoke standalone** hot-key demo (its own
`Config`/`Behavior`/`Workload`/runner — *not* the `SingleTableScenario`/`TableSpec` harness, which is fat and
adds nothing here), then a hybrid reconcile, then docs. The legacy is **unreferenceable** from this module and
hashes differently (`MurmurHash3` vs `String.hashCode`), so the reconcile is internal + transitive + documented,
not a live legacy run.

#### Slice 4a — Hot-key demo
Package `stochastacy.aws.examples.hotkey`: `HotKeyConfig` (provisioned; a hot-key skew via `hotFraction`/
`hotKeyCount`; `adaptiveCapacity` + `heatSplitPolicy` set directly on `DynamoDbTable.Config`), `HotKeyBehavior`
(`partitionAccessFor` draws the skewed key token), `HotKeyWorkload` (Poisson arrivals), a **standalone**
`HotKeyTrialRunner` (bespoke graph — folds the response plane to throttled/admitted counts and captures the final
`ComponentResult[TableState]` for the effective partition count), `HotKeyMonteCarloRunner`, and `@main HotKeyDemo`
running three arms (hot adaptive-on / hot adaptive-off / well-distributed control) with per-tick streaming JSONL.

**Validated by:** `HotKeySpec` — the hot arm throttles while the table has aggregate spare; the well-distributed
control throttles far less; adaptive-on admits more than adaptive-off; the effective partition count grows under
sustained heat; determinism (a fixed seed reproduces the run).

**Delivered.** Package `stochastacy.aws.examples.hotkey`: `HotKeyConfig` / `HotKeyBehavior` / `HotKeyWorkload`, a
standalone `HotKeyTrialRunner` (bespoke Pekko graph combining two mat values — per-tick response counts + final
`TableState`), `HotKeyMonteCarloRunner` (across-trial means + per-tick JSONL), `@main HotKeyDemo` (three arms).
`HotKeySpec` (5 tests). A representative run (20 trials × 100 ticks, single hot key): adaptive-on **29.5 %** vs
adaptive-off **41.3 %** throttle rate (**28.6 % relief**), well-distributed **0 %**, effective partition count
**5 → 20** (split-for-heat; a lone key is the AWS single-item limit — grows without further relief). Two fixes
surfaced: the adaptive-off arm must clear `heatSplitPolicy` (the Config `require`), and the default is a single
concentrated hot key (a spread-out default throttled nothing).

#### Slice 4b — Hot-key hybrid reconcile
`HotKeyReconciliationSpec`: the **control arm** reconciles *tight* against the same config with
`partitionAccessFor = None` (the table-level-only path — transitively the phase-6/8 legacy reconcile), proving the
per-partition machinery doesn't perturb a well-distributed workload; the **hot arm** asserts the expected
*direction* (adaptive-on throttles strictly fewer than adaptive-off; the effective count grows) with the legacy
lagged-adaptive comparison **documented** (it lands between v2-off and v2-on; legacy configures the partition
count v2 derives).

**Delivered.** `HotKeyConfig` gained `partitionAccessEnabled: Boolean = true` (honored by `HotKeyBehavior` →
`None` when off, the table-level-only path). `HotKeyReconciliationSpec` (3 tests): the control arm (distributed,
table-saturating) reconciles access-on vs access-off within ~2 %; the hot arm asserts adaptive-on throttled <
adaptive-off and effective count > base; a documented header records the divergences (instant-vs-lagged adaptive,
derived-vs-configured topology, split as topology growth). **aws 269 green**; existing scenarios byte-identical.

#### Slice 4c — Docs
`specs/aws-component-catalog.md` entries (per-partition throttle, instant adaptive, split-for-heat, incl. the
LSI-granularity boundary); `specs/README.hot-key.md`; a CLAUDE.md "Demo workflows" entry.

**Delivered.** Catalog: rewrote the "Hot-partition throttling" paragraph for instant adaptive (physical-max
ceiling; `adaptiveCapacity` toggle), added a "Split-for-heat" paragraph (permanent count growth, single-item
limit, LSI sort-key boundary documented-not-gated), corrected the burst paragraph's stale "deferred" note to
**dropped**, updated Scope + Exercised-by + Quick-reference. New `specs/README.hot-key.md` (three arms, the
representative run, the mechanisms, the single-key nuance, the hybrid reconcile, running it). CLAUDE.md gained a
hot-key "Demo workflows" entry. Docs-only — no code, aws still 269 green.

## Scope boundary

Single-region (multi-region is phase 11). Physical-partition granularity only (no sub-partition / item-collection
modeling beyond what the legacy has). Full legacy reconcile is against a bespoke hot-key scenario (no existing
demo hot-partitions). The close-out (roadmap COMPLETE, CLAUDE.md, program roadmap, memory, full `sbt test`) is a
separate coda.
