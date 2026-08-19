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
| 2 | `SecondaryIndexMechanics` + index config + write-side maintenance | Planned | base write emits correct per-index maintenance; index states evolve |
| 3 | Query/Scan routing to a GSI | Planned | GSI-targeted query consumes GSI RCU from the GSI state; routing correct |
| 4 | Indexed behavior + workload + demo config | Planned | per-target flow means ≈ λ; read-shape draws in range; end-to-end indexed trial |
| 5 | Per-index reporting + MC + JSONL + `@main` | Planned | per-index records w/ legacy names + counts; reproducible + parallelism-independent |
| 6 | Equivalence gate + docs + close-out | Planned | v2 vs captured legacy baseline within tolerance (incl. per-GSI); phase COMPLETE |

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

### Slice 3 — Query/Scan routing to a GSI

Serve queries/scans that target a GSI, reading the GSI's own (projected) storage and consuming GSI RCU.
The table routes a query/scan by its `DynamoDbReadTarget` to the targeted index's `TableSummaryState`; the
read-shape reads that index's summary; RCU is tagged to the index target. (LSI read routing is included if
it falls out cheaply, but the demo never queries the LSI, so this slice is really about GSIs.)

**Validated by:** a GSI-targeted query consumes GSI RCU computed from the GSI's state; base-vs-GSI routing
is correct; determinism under seed.

### Slice 4 — Indexed Order-Tracking behavior, workload, and demo config

Assemble the demo scenario on the now index-capable table. `OrderTrackingBehavior` extended with
query/scan read-shape outcomes (faithful to the legacy `sampleReadShape` — evaluated / returned per
target). `OrderTrackingConfig` gains index declarations + query/scan flow rates and an **`indexedDefault`**
(the `order-tracking-phase2` equivalent: GSIs `customerId-status` + `sellerId-createdAt`, LSI
`createdAt-priority`; base query λ0.8 / scan λ0.25; per-GSI query λ0.75 / scan λ0.30). `OrderTrackingWorkload`
emits the query/scan flows targeting base + each GSI.

**Validated by:** per-target flow means ≈ λ; read-shape draws in range; one indexed trial runs end-to-end
producing base *and* per-index consumption.

### Slice 5 — Per-index reporting, aggregation, JSONL, `@main`

Surface per-index metrics, matching the legacy metric names. `TrialAccounting` folds per-`target`
consumption into per-index metrics named as the legacy does (`GSI:<name>:ReadCapacityUnits`,
`GSI:<name>:WriteCapacityUnits`, and totals); `MonteCarloAggregation` and `JsonlExport` include them; a new
`IndexedOrderTrackingDemo` `@main` runs the indexed scenario (leaving the phase-1 demo intact).

**Validated by:** per-index records present with the right names and counts; ensemble reproducible +
parallelism-independent; `@main` runs end-to-end. Resolves **DD-demo-shape**.

### Slice 6 — Equivalence gate + docs + close-out

Prove parity with legacy Indexed Order-Tracking; document; close the phase. Capture the legacy
`OrderTrackingPhase2` aggregate baseline (including per-GSI RCU/WCU); an equivalence gate asserting v2
within tolerance on RCU/WCU/cost **and** the per-GSI metrics, with storage handled by the initial-storage
correction as in phase-2; update the demo guide (an indexed section) and add a `SecondaryIndexMechanics`
entry to `specs/aws-component-catalog.md`; roadmap + memory close-out.

**Validated by:** the gate passes with measured gaps reported; a reviewer can understand and run the
indexed demo; phase COMPLETE.

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
