# v2/phase2 — AWS components on the v2 core: the Order-Tracking demo, re-implemented

**Status: PLANNED** — eight slices scoped below (plus this roadmap commit). First AWS-specific work on
the domain-agnostic v2 engine.

Started on branch `v2/phase2`, following the conclusion of `v2/phase1` (the reusable interface / gating
components, proven by Store Demo V2). This phase begins re-implementing the AWS DynamoDB simulator on the
new `stochastacy.core` abstractions — starting with the **smallest complete DynamoDB demo**, the
**Order-Tracking Phase-1** scenario, rebuilt as a `ComponentSampler`-based table component driven through
the v2 timed-event protocol.

## Goal

Reproduce the **behavior** of the legacy Order-Tracking Phase-1 demo — a single on-demand DynamoDB table
under a mixed get/put/update/delete workload, reporting per-tick and per-trial capacity consumption,
storage growth, and cost across a Monte Carlo ensemble — using **new v2 core components** rather than the
legacy `stochastacy.aws.dynamodb` three-stage graph. The re-implementation must:

- use a **freshly re-created** DynamoDB request/response protocol (same operation vocabulary — GetItem /
  PutItem / UpdateItem / DeleteItem, extensible to Query / Scan later — but new, clean, timeless payload
  types fit for the v2 wire), and
- exhibit the **same, or insubstantially different,** aggregate behavior as the legacy Phase-1 demo
  (total RCU / WCU, final storage bytes, storage byte-ticks, total cost within a statistical tolerance
  band).

### Why Order-Tracking Phase-1 first

Of the three legacy DynamoDB demos (`stochastacy/demo` scaffolding, `ordertracking`, the
`thermostatfleet` capstone), Phase-1 Order-Tracking is the smallest that forces the essential new
component — a DynamoDB *table* as a `ComponentSampler` with a consumption plane — into existence, while
needing **none** of the advanced machinery: it is single-table, on-demand with **no throughput cap (so
nothing throttles)**, no GSI/LSI, and no hot-partition / burst / adaptive / topology / PITR / TTL /
replication / management. It needs no external services (Docker/Postgres/Grafana are a delivery concern
layered on the JSONL, not simulation behavior). The `thermostatfleet` capstone (multi-table,
multi-region, global tables) is the eventual north star; Phase-1 → capstone mirrors the incremental arc
`v2/phase0` → `v2/phase1` already followed.

## Confirmed decisions

- **D-target — Order-Tracking Phase-1 is the phase-2 demo,** sliced from its single-table on-demand core
  outward. The `thermostatfleet` capstone is a later target once the AWS-on-v2 component library exists.
- **D-additive (temporary) — v2 is new code in a new module; the legacy demo stays frozen for now.** No
  existing `stochastacy.aws` (in `core`) or `stochastacy.examples.ordertracking` (in `examples`) file is
  modified during this phase. **Unlike phase-1, this constraint is temporary:** once v2 re-implements the
  AWS line, **the legacy AWS components and demos will be removed from the repo entirely** — v2 becomes
  the sole implementation, not a permanent parallel.
- **D-module — the AWS line lives in a dedicated `aws` sbt module, in the un-suffixed package.** Both the
  reusable components and the example/demo code live in a new module `aws` (`.dependsOn(core)` only), so
  the whole effort is self-contained and `core`/`examples` shed their AWS code when legacy is deleted.
  Packages: `stochastacy.aws.dynamodb` (library) and `stochastacy.aws.examples.ordertracking` (demo,
  confirmed at Slice 3) — **no `v2` suffix**; "v2" names the effort, not the code. The package name is
  deliberately the *same* as legacy's: `aws`'s classpath also carries `core`'s legacy
  `stochastacy.aws.dynamodb.*`, so the eight op-event request/response names collide by fully-qualified
  name. This is benign in practice — within the `aws` module the new sources shadow the legacy classpath
  entries at compile time and load first at runtime (verified: `aws/clean` + `aws/test` green), and
  nothing depends on `aws`, so no consumer sees the ambiguity — and the duplication disappears when
  legacy is deleted. **Implication:** legacy `stochastacy.aws.dynamodb.*` types are *unreferenceable*
  from the `aws` module (shadowed), so the Slice-7 equivalence gate compares against a **captured legacy
  baseline** rather than co-running legacy in-process.
- **DD-proto — the protocol is re-created cleanly, not reused.** New, timeless request/response payload
  types (no embedded `eventTime`/`intraTick`/`usecase` — those belong to the `Timed[E]` wrapper on the v2
  wire), covering the same operation vocabulary as the legacy `op_events.scala`. The legacy protocol
  types are *not* imported. (Decided against reuse because the legacy types are being removed and carry
  vestigial time fields the v2 wire supplies.)
- **DD-state — new immutable `TableSummaryState`.** The legacy `SummaryTableState` is mutable (`var`s +
  `Unit`-returning recorders), which cannot be the functionally-threaded state a `ComponentSampler`
  requires. Same summary semantics (item count + total bytes, average derived), immutable transitions.
- **DD-admission — omit throttling/admission entirely.** Phase-1 is on-demand unbounded, so nothing
  throttles; the v2 table has no admission stage. (Throttling returns in a later phase — a natural fit
  for the phase-1 Interface-gate machinery.)
- **DD-workload — a new thin v2 arrivals builder.** The four Phase-1 Poisson flows are generated by a
  small purpose-built driver emitting `Timed[<request>]`, not by adapting the ips `WorkloadDsl` /
  `WorkloadRequestStream`. Because "same behavior" allows *insubstantial* difference, identical RNG
  streams are unnecessary — the same distributions (lambdas, item-byte uniforms) suffice.
- **DD-output — match the legacy demo's output shape.** The v2 runner produces per-trial results and an
  aggregated JSONL of the **same record shape** as the legacy Phase-1 demo (so the existing report/Grafana
  contract holds and the equivalence gate can compare like-for-like), while executing the ensemble via
  core `MonteCarlo.run`. Whether that reuses `stochastacy.demo`'s `TrialResult`/`DemoMetric`/exporter by
  import or re-homes equivalents is settled at Slice 5/6 plan time (the legacy `stochastacy.demo`
  scaffolding may itself be in scope for eventual removal). Reporting on core `Statistics[K]`
  (store-v2 style) is a possible later enhancement, not required here.
- **DD-latency — reproduce per-op latency.** Each operation carries a per-op latency (default log-normal,
  as legacy), threaded as a scheduled-output delay. It affects only intra-window placement, not totals.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| — | Roadmap | **Done** | this document + project memory |
| 1 | Immutable table state + per-op kernels (new `aws` module) | **Done** | `aws` 20 tests: RCU/WCU chunking, state evolution, per-op resolution vs hand-computed values |
| 2 | `DynamoDbTable` ComponentSampler + transducer | **Done** | single request → timed response (latency) + execution-time consumption; state threads to final Mat; 5 tests |
| 3 | Order-Tracking behavior (v2) + reusable config | **Done** | behavior-draw tests: hit ≈0.85, update ≈0.9, delete ≈0.75, byte band, empty-table; 9 tests |
| 4 | v2 workload driver (4 Poisson flows) | Planned | per-tick counts ≈ Poisson; seeded-deterministic; tick-framed |
| 5 | v2 single-trial runner | Planned | one deterministic trial's usage/cost totals |
| 6 | v2 Monte Carlo + reporting + JSONL + `@main` | Planned | ensemble aggregation; JSONL record shape matches legacy |
| 7 | Behavior-equivalence gate | Planned | legacy vs v2 aggregate metrics agree within tolerance band |
| 8 | Docs + component catalog + close-out | Planned | `specs/README.ordertracking-v2.md`; catalog entry; roadmap/memory |

## Slices

### Slice 1 — Immutable table state + per-op kernels

The pure heart, decoupled from Pekko. New package `stochastacy.aws.dynamodb` (in the `aws` module): the re-created protocol
payload types (timeless GetItem / PutItem / UpdateItem / DeleteItem requests + their responses, and the
consumption-fact type — RCU consumed, WCU consumed, storage-bytes delta); an immutable `TableSummaryState`
(item count + total bytes, average derived, pure `applyWrite` / `applyDelete`); and pure per-op resolver
functions `(request, state, rng) → (response, List[consumption], newState)` computing RCU (4 KB chunks,
strong ×1) / WCU (1 KB chunks) / storage deltas. (DDB throughput math re-created here, small and
well-specified.)

**Validated by:** unit tests pin RCU/WCU per operation against hand-computed values (a 768-byte strong
read = 1 RCU; a 768-byte write = 1 WCU; etc.), storage deltas on put/update/delete, and state evolution
across a sequence of writes and deletes. No graph, no timing.

**Delivered.** New `aws` sbt module (`stochastacy-aws`, `.dependsOn(core)`; added to the root aggregate),
holding package `stochastacy.aws.dynamodb`: `protocol.scala` (timeless `DynamoDbRequest`/`Response` —
`GetItemRequest`/`DeleteItemRequest` case objects, `PutItemRequest`/`UpdateItemRequest(itemBytes)`, four
response types), `consumption.scala` (`ReadConsistency` enum + `ReadCapacityConsumed` /
`WriteCapacityConsumed` / `StorageBytesDelta` — no target dimension), `TableSummaryState.scala` (immutable
`applyWrite`/`applyDelete` matching the legacy recorder semantics; `empty`/`initial` helpers),
`ThroughputMath.scala` (re-created 4 KB-read / 1 KB-write chunking, strong ×1 / eventual ×0.5,
one-chunk minimum), and `TableMechanics.scala` (rng-free `OperationOutcome` enum + `Resolution` +
`resolve` — the deterministic response/consumption/next-state kernel, storage delta emitted only when the
byte total moves). Tests (20, all green): `ThroughputMathSpec`, `TableSummaryStateSpec`,
`TableMechanicsSpec`. Whole build compiles; no legacy file touched.

### Slice 2 — `DynamoDbTable` ComponentSampler + transducer

Wrap the Slice-1 kernels as a reusable v2 component:
`DynamoDbTableSampler extends ComponentSampler[TableSummaryState, <Request>, <Response>, <Consumption>]`,
parameterized by a **domain behavior** (the get-hit / item-byte / existing-item draws, injected — kept
separate from the table mechanics so the component stays generic and reusable) and a **latency sampler**.
`sample` builds the `Emission` — the response scheduled with a per-op latency delay, plus the consumption
facts — and threads the new state. Materialized through `ScheduleReleaseTransducer` into a running graph
stage.

**Validated by:** a single request driven through the running stage yields the correct timed response and
consumption facts at the expected conceptual time (latency applied); a multi-request sequence threads
state correctly (storage grows, averages track). Determinism under a fixed seed.

**Delivered.** In `stochastacy.aws.dynamodb`: `TableBehavior` (the injected `request + state + rng →
OperationOutcome` seam — v2 counterpart to the legacy `UseCaseSampler`) and `object DynamoDbTable` with
`Config(initialState, behavior, latency: StatelessSampler[Double], readConsistency)`, the
`DynamoDbTableSampler extends ComponentSampler[TableSummaryState, DynamoDbRequest, DynamoDbResponse,
DynamoDbConsumption]`, and a `componentOf(config, rng)` factory materializing it through
`ScheduleReleaseTransducer` (Mat `Future[ComponentResult[TableSummaryState]]`). `sample` draws the
outcome, resolves via `TableMechanics`, and emits the response after the drawn per-op latency
(`Scheduled(resp, latency)`) with the consumption facts at execution time (`delay 0`) — DQ-b; state stays
pure `TableSummaryState` with latency sampled at a fixed tick — DQ-c; `onTick` is the inherited no-op.
`DynamoDbTableSpec` (5 tests, modeled on `ScheduleReleaseTransducerSpec` with a scripted deterministic
behavior): get response at `tick+0.5` with RCU at execution time, put WCU + storage delta, three-op state
threading to the final Mat (`TableSummaryState(10, 7844)`, zero residue), `EndOfTime` on both planes,
determinism. `aws` 25 tests green; whole build compiles; no legacy file touched.

### Slice 3 — Order-Tracking behavior (v2)

New package `stochastacy.examples.ordertracking.v2`: the domain behavior, ported to the v2 behavior
interface from Slice 2 — get-hit probability 0.85, item-byte sampling, update-existing 0.9,
delete-existing 0.75. The order-tracking *domain* knowledge, injected into the generic table component.

**Validated by:** behavior-draw unit tests (hit/miss rates, byte ranges, existing-item probabilities)
over a fixed seed.

**Delivered.** New package `stochastacy.aws.examples.ordertracking` (confirming **DC-demopkg** — demo code
lives in the `aws` module): `OrderTrackingConfig` (the reusable v2 scenario — `scenarioId`, `tableName`,
`simulationTicks`, `trialCount`, `parallelism`, `initialItemCount`, `initialAverageItemBytes`, the three
probabilities, `readConsistency`; `phase1Default`; `initialTableState` helper) and
`OrderTrackingBehavior extends TableBehavior` — a faithful port of the legacy `UseCaseSampler` matching on
the four Phase-1 request types (get miss on empty-or-failed-coin else `±25%`-jittered bytes; put always
new; update/delete target an existing item at their probabilities), dropping the irrelevant
`LogicalPartitionAccess`. `OrderTrackingBehaviorSpec` (9 tests): request→outcome mapping, populated-table
draw rates (hit ≈0.85, update-existing ≈0.9, delete-existing ≈0.75 over 200k draws ±0.01), get-hit byte
band `[576, 960]`, and empty-table degenerate cases (always miss / upsert / no-op). Per the user, the
reusable `OrderTrackingConfig` was introduced now (vs a behavior-local config). `aws` 34 tests green.

### Slice 4 — v2 workload driver (4 Poisson flows)

A thin arrivals builder for the Phase-1 workload: put-item (λ 0.8, item-bytes U(672, 1120)), get-item
(λ 2.5), update-item (λ 1.2, item-bytes U(768, 1280)), delete-item (λ 0.4), emitted as a tick-framed
`Timed[<request>]` stream terminated by `EndOfTime`, seeded and deterministic. Reuses the core distribution
samplers.

**Validated by:** per-tick request counts follow the configured Poisson means; item-byte draws fall in
range; the stream is correctly tick-framed and seed-deterministic.

### Slice 5 — v2 single-trial runner

Wire workload → table component → collect the consumption plane; fold it into usage totals (scalar RCU/WCU
+ storage) and time-based totals (storage byte-ticks integrated over ticks), then price it (on-demand
RCU/WCU cost + storage cost). Emit a per-trial result — the per-tick time series (RCU / WCU / storage /
cumulative cost) and the summary totals — in the **legacy output shape** (DD-output).

**Validated by:** a single deterministic trial produces the expected usage and cost totals for a fixed
seed and config.

### Slice 6 — v2 Monte Carlo + reporting + JSONL + `@main`

Drive N trials through core `MonteCarlo.run`; aggregate into the demo's report bundle; export JSONL of the
same record shape as the legacy demo; add a runnable `@main` entry point (JSONL out — the Grafana
stage/view bridge is optional and can be reused from the legacy CLI if wanted).

**Validated by:** the ensemble aggregates across trials; the JSONL records match the legacy schema
(pooled / per-trial as applicable); the `@main` runs end to end.

### Slice 7 — Behavior-equivalence gate

The "insubstantial difference" proof: run the legacy Phase-1 demo and the v2 Phase-1 demo at matched
config (same ticks / trials / initial state / probabilities) and assert their **aggregate** metrics —
total RCU, total WCU, final storage bytes, storage byte-ticks, total cost — agree within a statistical
tolerance band. (Exact numeric match is neither expected nor required — the workload drivers use
independent RNG streams, DD-workload — so the gate asserts closeness of ensemble means, not identity.)

**Validated by:** the comparison spec passes at the chosen tolerance; any metric outside band is a real
behavioral discrepancy to investigate.

### Slice 8 — Docs + component catalog + close-out

`specs/README.ordertracking-v2.md` (the v2 demo's engineer's guide — the table component, the workload,
the usage→cost fold); a `specs/component-catalog.md` entry for the v2 DynamoDB table `ComponentSampler`
(purpose / signature / properties / when to use / composition / exercised-by); roadmap + project-memory
update. Declares the phase complete.

**Validated by:** a reviewer can, from the guide alone, understand the v2 table component and run the v2
Order-Tracking demo.

## Design principles and reuse

- **Re-create, don't reuse, the protocol** (DD-proto) — the legacy AWS types are being removed; the v2
  protocol is timeless and wire-native.
- **Table mechanics stay domain-agnostic**; the order-tracking domain enters only through an injected
  behavior sampler — the same generic-component / injected-behavior split the legacy `DynamoDbTable` +
  `UseCaseSampler` used, carried onto the v2 `ComponentSampler`.
- **Immutable, functionally-threaded state** (DD-state) — the `ComponentSampler` contract; no mutable
  table vars.
- **Reuse the v2 core machinery**: `ComponentSampler` / `ScheduleReleaseTransducer` (running stage),
  the timed-event protocol (tick framing, intra-tick latency stamping), the distribution samplers
  (Poisson / Uniform / LogNormal), `MonteCarlo.run` / `SeedSequence` (the ensemble).
- **Build only what the target demo needs** (DD-admission) — no throttling, indexes, or advanced models
  this phase.

## Deferred / future (beyond this phase)

Order-Tracking **Phase-2** (Query / Scan + GSI / LSI + index maintenance); throttling / admission (likely
via the Interface gates); provisioned billing, hot-partition / burst / adaptive capacity, PITR / TTL,
autoscaling; multi-table and multi-region / global tables (the `thermostatfleet` capstone). Each builds on
the v2 table component this phase establishes. **Removal of the legacy AWS components and demos** follows
once the v2 line reaches parity.
