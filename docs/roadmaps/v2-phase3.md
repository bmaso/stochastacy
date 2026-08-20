# v2/phase3 — Indexed Order-Tracking: Query/Scan + secondary indexes

**Status: PLANNED** — six slices scoped below. The second AWS increment on the v2 core: the Order-Tracking
table gains Query/Scan access patterns over secondary indexes.

Started on branch `v2/phase3`, following the conclusion of `v2/phase2` (the single on-demand DynamoDB
table + the Order-Tracking Phase-1 demo). This phase re-implements the legacy `order-tracking-phase2`
scenario — the **Indexed Order-Tracking demo** — on the v2 core, extending the table with Query/Scan and
two GSIs + one LSI.

## Goal

Reproduce the **behavior** of the legacy Indexed Order-Tracking demo — the `orders` table under a mixed
workload that now includes **Query and Scan** over **two GSIs** (`customerId-status`, `sellerId-createdAt`)
and **one LSI** (`createdAt-priority`) — using **new v2 core capabilities** rather than the legacy
three-stage table. The result must exhibit the **same, or insubstantially different,** aggregate behavior
as the legacy demo (RCU/WCU/cost and the per-GSI capacity metrics within a statistical tolerance band;
storage per the phase-2 initial-storage correction).

## Confirmed decisions

- **D-target — Indexed Order-Tracking is the phase-3 demo.** It is the smallest real increment on
  phase-2: Query/Scan + secondary indexes, the machinery every richer DynamoDB demo (the thermostat-fleet
  capstone included) later reuses. "Indexed Order-Tracking" is our name for the behaviors the legacy
  `order-tracking-phase2` scenario implements; the v2 demo must exhibit substantially or completely the
  same behaviors.
- **D-index-intrinsic — indexes are intrinsic table structure, declared *on* the table, never
  graph-wired.** A table *with* an index accepts different requests and has different response behavior
  than the same table without it, so the index is declared in the table's `Config` (builder methods
  `withGlobalSecondaryIndex` / `withLocalSecondaryIndex`) — but a secondary index is never a graph-level
  component a trial-runner wires. Rationale: an index is not cross-cutting *edge* behavior (so it does not
  fit the `Interface.wrap` decorator idiom, which is reserved for gates), and it is not independently
  reusable (a GSI exists only relative to its base table). The composable graph-level unit stays the
  **table**; a multi-table demo composes several tables, indexes internal to each. This gives one API
  rule: **decoration for cross-cutting edge behavior, configuration for intrinsic table structure.** The
  static-topology simplification below removes the only real payoff of explicit wiring.
- **D-static-topology — no dynamic index architectures.** Index topology is fixed at graph-construction
  time; the simulation never adds/removes a GSI or LSI mid-run. This is a deliberate scope cut that avoids
  significant design complexity, and it is what makes declarative config a faithful representation of the
  topology.
- **D-index-modules — index logic lives in reusable, testable internal modules.** `SecondaryIndexMechanics`
  is the rng-free resolution module for an index — the sibling of `TableMechanics` — operating on the
  index's own `TableSummaryState` (an index is summarized the same way as a table: item count + projected
  bytes, so no new state type). The modularity the "separate components" intuition wants is preserved;
  only the *graph-node* mechanism is rejected.
- **D-improved-reads — model reads from the target's own state, not the legacy's base-state + magic
  caps** (decided 2026-08-19, after comparing the models). The legacy `sampleReadShape` derives every
  read's item count / bytes from the *base* table plus hard-coded per-target caps (4/6/5) and fractions —
  which (a) models a scan like a query (a capped few items, so scan cost never grows with the table),
  (b) ignores projection for read bytes, and (c) ignores the index's own population. Instead: a read
  consults the **target's own maintained `TableSummaryState`** — a **scan evaluates the whole target**
  (projection-correct bytes, cost that grows with data), a **query** evaluates a config-driven selectivity
  draw bounded by the target's population, and the assumptions become explicit config rather than magic
  constants. This is a deliberate quality improvement that **diverges from legacy** (materially on scans),
  so the Slice-6 gate becomes a *reconciliation* (equivalence on writes/gets/maintenance; the read-model
  change quantified as a documented correction — the same pattern as the phase-2 storage-bug fix).
- **Carried over from phase-2.** Re-create the protocol cleanly (Query/Scan + targets are new timeless
  types in `stochastacy.aws.dynamodb`; no legacy import). Demo-local reporting. The legacy code stays
  frozen — run only once to capture the equivalence baseline.
- **D-regression — this phase evolves our own v2 code.** The table/protocol/mechanics gain Query/Scan +
  index *capability* additively. The existing non-indexed Order-Tracking demo and its equivalence gate
  must stay green every slice — the regression guard.

## Open design decisions (resolved at each slice's plan)

- **DD-internal-structure** (Slice 2/3) — the table sampler as *one multi-target sampler* (threads the
  base state + one `TableSummaryState` per index; routes by request/target inside `sample`) vs. an
  internal per-target `Broadcast`/`Merge` of sub-samplers. Both present the same external API (one
  configured `DynamoDbTable`). **Lean: single multi-target sampler** — it fits the `ComponentSampler` /
  `FanOutShape2` model with no new graph machinery; a write simply emits more consumption facts.
- **DD-lsi-capacity** (Slice 2) — an LSI shares the *base table's* capacity (real DynamoDB) vs. has its
  own; match whatever the legacy does (checked when its index-maintenance model is read). The demo never
  *queries* the LSI, so the LSI is write-side / storage only.
- **DD-projection** (Slice 2) — model index *projections* (a projected-attribute subset → smaller index
  item bytes, affecting index storage and query read bytes), matching the legacy `IndexProjection`, vs.
  full-item projection.
- **DD-gsi-async-delay** (Slice 2) — the GSI maintenance propagation delay; affects only intra-window
  placement, not totals — a small default.
- **DD-demo-shape** (Slice 4/5) — a *new* `indexedDefault` config + a *new* `@main`, leaving the phase-1
  demo intact (lean), vs. a flag on the existing demo.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Query/Scan + read-shapes on the base table | **Done** | read RCU from evaluated bytes vs hand-computed; `target` dimension; phase-1 gate green; 64 tests |
| 2 | `SecondaryIndexMechanics` + index config + write-side maintenance | **Done** | base write emits target-tagged per-index maintenance (GSI/LSI); composite state evolves; 72 tests |
| 3 | Read routing — a read consults its target's state | **Done** | GSI scan reads the index's projected state (not base); RCU tagged; 73 tests |
| 4 | Indexed behavior (improved reads) + workload + demo config | Planned | scan = whole target; query selectivity; per-target flow means ≈ λ; end-to-end indexed trial |
| 5 | Per-index reporting + MC + JSONL + `@main` | Planned | per-index records w/ legacy names + counts; reproducible + parallelism-independent |
| 6 | Reconciliation gate + docs + close-out | Planned | equivalence on writes/gets; read-model divergence quantified; phase COMPLETE |

## Slices

### Slice 1 — Query/Scan + read-shapes on the base table

Extend the v2 table to serve `Query` and `Scan` against the base table, and give the consumption plane a
`target` dimension so it is index-ready. Re-created protocol: `QueryRequest` / `ScanRequest` (timeless,
carrying a `DynamoDbReadTarget`) and `QueryResponse` / `ScanResponse` (evaluated / returned item counts +
bytes); a `DynamoDbTarget` (`Table` | `Gsi(name)` | `Lsi(name)`) added as a field on `DynamoDbConsumption`;
new `OperationOutcome` cases + `TableMechanics` handling for reads (RCU from *evaluated* bytes; no state
change). The table serves query/scan on the base; existing get/put/update/delete now tag `target = Table`.

**Validated by:** Query/Scan RCU vs. hand-computed values; response shapes; the existing non-indexed demo
and its equivalence gate stay green (the regression guard, every slice).

**Delivered.** Protocol (`protocol.scala`): `DynamoDbTarget` (`Table` | `Gsi(name)` | `Lsi(name)`),
`QueryRequest`/`ScanRequest` (target + consistency), `QueryResponse`/`ScanResponse` (evaluated/returned
counts + bytes). `DynamoDbConsumption` gains a `target` field on all three facts. `TableMechanics`
(DQ-resolve-signature resolved): a `ReadShape`, new `OperationOutcome.Query`/`Scan` (target + consistency
+ shape) and `Get` now carrying its consistency; `resolve(outcome, state)` is uniform (dropped the
consistency parameter) — reads compute RCU from *evaluated* bytes and tag the `target`, writes tag
`Table`. `DynamoDbTable.Config` dropped `readConsistency` (now behavior-owned). Ripple: `OrderTrackingBehavior`
sets `Get`'s consistency from its config and throws on query/scan (a "Slice 4" placeholder — the phase-1
workload emits none); `OrderTrackingTrialRunner` builds the slimmer config; `TrialAccounting` sums across
targets (base only today). Tests: `TableMechanicsSpec` + `DynamoDbTableSpec` extended with Query/Scan
(e.g. 20×768 B strong → 4 RCU; eventual halves it; GSI-tagged); `TrialAccountingSpec` /
`OrderTrackingBehaviorSpec` updated for the new signatures. `aws` 64 tests green; whole build compiles;
**phase-1 equivalence gate still green** (get/put/update/delete RCU/WCU unchanged, `Get` still strong); no
legacy file touched.

### Slice 2 — `SecondaryIndexMechanics` + index config + write-side maintenance

Make indexes intrinsic table configuration, and have base writes maintain them. `DynamoDbTable.Config`
gains `withGlobalSecondaryIndex(...)` / `withLocalSecondaryIndex(...)`, holding `GlobalSecondaryIndex` /
`LocalSecondaryIndex` descriptors (name, projection). `SecondaryIndexMechanics` (rng-free, sibling of
`TableMechanics`, over the index's own `TableSummaryState`): given a base write, resolve each index's
maintenance — its WCU + storage-delta on the index target — GSI **asynchronously** (a `Scheduled` delay),
LSI **synchronously** (delay 0). The table sampler threads the base state + one per index; a write's
`Emission` now carries base + per-index maintenance facts. Faithful to the legacy index-maintenance
capacity/storage model.

**Validated by:** a base put/update/delete emits the correct per-index maintenance facts (target-tagged,
GSI delayed); per-index state evolves; a table with no indexes behaves exactly as before. Resolves
**DD-internal-structure**, **DD-lsi-capacity**, **DD-projection**, **DD-gsi-async-delay**.

**Delivered.** Read the legacy `IndexMaintenanceMath` to fix the model. `SecondaryIndex.scala`:
`IndexProjection` (`All` | `KeysOnly` | `Include(n)`) + `GlobalSecondaryIndex(name, projection = All,
propagationDelayTicks = 0.0)` / `LocalSecondaryIndex(name, projection = All)` descriptors (a
`SecondaryIndex` trait with `target` + `maintenanceDelay`). `SecondaryIndexMechanics.scala` (rng-free,
sibling of `TableMechanics`): `projectedEntryBytes` (key floor = 128 B) and `maintain(index, newBase,
prevBase, indexState)` → insert/replace/delete/no-op → WCU on the written/deleted entry +
target-tagged storage delta + next index state. `TableState` (base summary + `Map[name, TableSummaryState]`;
`TableState.initial` seeds each index from the base's pre-loaded items, projected). `DynamoDbTable.Config`
gains `globalSecondaryIndexes`/`localSecondaryIndexes` + `withGlobalSecondaryIndex`/`withLocalSecondaryIndex`
builders; the sampler now threads `TableState` and, for a base write, folds per-index maintenance into the
`Emission` (base + LSI at delay 0, GSI at its propagation delay). Tests: `SecondaryIndexMechanicsSpec`
(projections; insert/replace/delete/no-op incl. a projection-collapses-to-no-op case);
`DynamoDbTableSpec` gains a GSI+LSI maintenance test + composite `finalState`. **Resolved decisions:**
DD-internal-structure = one multi-target sampler + composite state; DD-projection = all three modeled
(demo uses `All`); DD-lsi-capacity = LSI maintenance identical math to GSI, tagged `Lsi(name)`, no
separate capacity pool (irrelevant on-demand); DD-gsi-async-delay = `propagationDelayTicks` default 0
(async hook off for legacy fidelity; doesn't affect gated per-GSI WCU); **+ new: indexes seed initial
state from the base's items (projected).** `aws` 72 tests green; whole build compiles; phase-1 gate green;
no legacy file touched.

### Slice 3 — Read routing: a read consults its target's state

The enabling slice for the improved read model (D-improved-reads): a Query/Scan's read-shape must derive
from the state of the target it hits, so the `DynamoDbTable` sampler routes each read to the target's
`TableSummaryState` — an index's own summary for a GSI/LSI query/scan, the base summary for a table read
and every write/get. The `TableBehavior` interface is unchanged (it receives "the state of the thing this
request hits"); `resolve` is unchanged (reads ignore the state; RCU is computed from the outcome's shape
and tagged with its target). Small, focused; the read-shape draws themselves are Slice 4.

**Validated by:** with a state-reading stub behavior, a scan targeting a `KeysOnly` GSI evaluates the GSI's
*projected* bytes (128 B/entry), not the base's 768 — proving the read consults the index's state; RCU
tagged with the GSI; base reads still consult base; non-indexed + phase-1 behavior unchanged.

**Delivered.** `DynamoDbTableSampler.sample` now passes `readTargetState(in, state)` (private helper:
GSI/LSI query/scan → `state.index(name)`, else `state.base`) to the behavior instead of `state.base`
unconditionally — the whole change. `DynamoDbTableSpec` gains a `StateReadingBehavior` stub + the
`KeysOnly`-GSI routing test (base scan 7680 B → 2 RCU; GSI scan 1280 B → 1 RCU, tagged `Gsi`). `aws` 73
tests green; phase-1 gate green; no legacy touched.

### Slice 4 — Indexed Order-Tracking behavior (improved reads), workload, and demo config

Assemble the demo scenario on the now index-capable table, with the **improved read model**.
`OrderTrackingBehavior` gains query/scan read-shape outcomes computed from the **target's** state (routed
in by Slice 3): a **scan evaluates the whole target** (its item count + projected bytes → cost that grows
with data), a **query** evaluates a config-driven selectivity draw bounded by the target's population, and
a returned fraction filters each — the assumptions are explicit config, not the legacy's magic caps.
`OrderTrackingConfig` gains index declarations + query/scan flow rates + selectivity/returned params and an
**`indexedDefault`** (the `order-tracking-phase2` equivalent: GSIs `customerId-status` +
`sellerId-createdAt`, LSI `createdAt-priority`; base query λ0.8 strong / scan λ0.25; per-GSI query λ0.75 /
scan λ0.30, eventually consistent). `OrderTrackingWorkload` emits the query/scan flows targeting base +
each GSI.

**Validated by:** per-target flow means ≈ λ; scan evaluates the whole target while a query evaluates a
bounded page (both ≥ returned); one indexed trial runs end-to-end producing base *and* per-index
consumption.

### Slice 5 — Per-index reporting, aggregation, JSONL, `@main`

Surface per-index metrics, matching the legacy metric names. `TrialAccounting` folds per-`target`
consumption into per-index metrics named as the legacy does (`GSI:<name>:ReadCapacityUnits`,
`GSI:<name>:WriteCapacityUnits`, and totals); `MonteCarloAggregation` and `JsonlExport` include them; a new
`IndexedOrderTrackingDemo` `@main` runs the indexed scenario (leaving the phase-1 demo intact).

**Validated by:** per-index records present with the right names and counts; ensemble reproducible +
parallelism-independent; `@main` runs end-to-end. Resolves **DD-demo-shape**.

### Slice 6 — Reconciliation gate + docs + close-out

Reconcile with legacy Indexed Order-Tracking; document; close the phase. Capture the legacy
`OrderTrackingPhase2` aggregate baseline (including per-GSI RCU/WCU). Because the read model was
deliberately improved (D-improved-reads), the gate is a **reconciliation, not a blind match**: assert
equivalence on the parts kept faithful (writes / gets / index maintenance — WCU, per-GSI write metrics)
and **quantify the read-model divergence** as a documented correction (scans now grow with the table;
projection-correct read bytes), storage handled by the initial-storage correction as in phase-2. Update
the demo guide (an indexed section) and add a `SecondaryIndexMechanics` entry to
`specs/aws-component-catalog.md`; roadmap + memory close-out.

**Validated by:** the gate passes with measured gaps reported and the read-model divergence explained; a
reviewer can understand and run the indexed demo; phase COMPLETE.

## Design principles and reuse

- **Indexes intrinsic, config-declared, internally modular** (D-index-intrinsic, D-index-modules) —
  `SecondaryIndexMechanics` mirrors `TableMechanics`; per-index state reuses `TableSummaryState`.
- **The table stays one `FanOutShape2` component** — indexes add consumption facts (target-tagged, some
  delayed), not new graph machinery (DD-internal-structure lean).
- **Reuse the phase-2 spine** — the `DynamoDbTable` sampler + transducer, the workload/runner/MC/JSONL
  scaffolding, and the equivalence-gate pattern (there is a legacy `OrderTrackingPhase2` demo to capture a
  baseline from).
- **Regression guard** — the non-indexed demo and its gate stay green throughout (D-regression).

## Scope boundary

Still **on-demand, no throttling**; no provisioned / auto-scaling, TTL, transactions, multi-table, or
multi-region — those remain the thermostat-fleet capstone's later phases. This phase is exactly Query/Scan
+ secondary indexes.
