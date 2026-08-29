# v2/phase7 — TTL + transactions (with a core enhancement: tick-boundary emission)

**Status: PLANNED** — five slices. Opens with the approved **core enhancement** (tick-boundary consumption
emission), then two mostly-independent single-table capabilities — **TTL** (needs the core change) and
**transactions** (does not) — each as a mechanism slice + a demo slice.

Follows `v2/phase6` (provisioned capacity + throttling). This is the **first deliberate v2 core change since
the engine redefinition** — taken because the old `onTick: S` contract *blocked* (not supported) TTL, whose
storage expiry is inherently a tick-boundary effect. The domain-agnostic contract is otherwise respected;
core changes only ever proceed after explicit discussion and sign-off.

## Goal

Land the tick-boundary consumption-emission core change cleanly (every `onTick` call site migrated, all
existing components byte-identical), then model **TTL** (item expiry frees base-table storage over ticks —
the Telemetry pattern) and **transactions** (`TransactWriteItems` / `TransactGetItems`, 2× capacity, atomic —
the Commands pattern).

## Confirmed decisions

- **D-tick-emission (approved).** `ComponentSampler.onTick` returns `TickEmission[S, Cons]` — the advanced
  state plus zero-or-more scheduled **consumption** facts; **never** forward outputs, so the 1:1
  request/response invariant holds by construction (a tick boundary has no request to respond to). The
  `ScheduleReleaseTransducer` stamps each fact at the boundary time `(t, 0)`, buffers it in the same pending
  queue, and releases it in time order like any tick-`t` output (released at `Tick(t+1)`, ordered first in
  tick `t`'s window). All timed-event invariants hold (tick framing, single `Tick` per window, `EndOfTime`
  last, intra-tick monotonicity). **Correction to the initial sketch:** the legacy TTL is *deterministic* (a
  ring buffer draining the slot written `ttlPeriodTicks` ago), not stochastic, so `onTick` does **not** need
  `rng` — the minimal signature is `onTick(tick, state): TickEmission[S, Cons]`. Adding `rng` later, if a
  stochastic tick-effect ever needs it, is a separate discussion.
- **D-reconcile-deferred.** The legacy sets TTL and transactions **only in the capstone** (4-table, phase 8),
  and its bridge has no single-region TTL/txn preset to capture — so phase 7 validates both capabilities with
  unit + end-to-end tests and focused demos; the **full legacy reconcile happens in the capstone** (as
  auto-scaling's did).

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Core: tick-boundary consumption emission | **Done** | `onTick` returns `TickEmission[S, Cons]`; transducer stamps `(t,0)`/buffers/releases; ~6 sites migrated; boundary-fact test; every existing component/gate byte-identical |
| 2 | TTL mechanism | **Done** | items expire exactly `ttlPeriodTicks` after write; **base + GSI + LSI** bytes freed; byte-ticks reflect it; no capacity consumed; TTL-off byte-identical |
| 3 | TTL demo (session-store) + delete/expire coverage + docs | **Done** | bespoke session-store demo: storage plateaus (creations ≈ expiries) vs unbounded no-TTL; delete-before-TTL freed exactly once; determinism; TTL doc note |
| 4 | Transactions mechanism | **Done** | base+LSI 2× / GSI 1× (AWS-accurate) per item; atomic all-or-nothing; storage + per-index maintenance + TTL over sub-writes; determinism |
| 5 | Transactions demo (payments ledger) + docs | **Done** | transactions bill ≈2× vs equivalent singles (write + read); storage flat; determinism |
| 5-coda | Phase close-out | Planned | roadmap COMPLETE header, CLAUDE.md, program roadmap, memory-complete |

## Slices

### Slice 1 — Core: tick-boundary consumption emission
Introduce `TickEmission[S, Cons](newState: S, consumption: List[Scheduled[Cons]])`; change
`ComponentSampler.onTick` to return it (default `TickEmission(state, Nil)`). The transducer's `Tick(t)`
handler runs `onTick`, stamps each emitted fact at `(t, 0) + delay`, buffers it, and releases it with the
rest. Migrate the ~5 existing overrides (`DynamoDbTable` + the four `core/component/gate/*` gates) — each a
one-line wrap of its `state` result — plus the transducer's one call site.

**Validated by:** a core test (a sampler that emits a tick-boundary consumption fact — it lands on the
consumption plane in tick `t`'s window, ordered first; framing/ordering/`EndOfTime` intact); every existing
`core` and `aws` test and gate stays green and byte-identical (existing components emit nothing on tick).

**Delivered.** `core/…/component/SamplerContract.scala`: new `TickEmission[S, Cons](newState, consumption)`
(+ `TickBoundaryUsecase` sentinel); `ComponentSampler.onTick` now returns `TickEmission[S, Cons]` (default
`TickEmission(state, Nil)`), no `rng`. The transducer's `Tick(t)` handler runs `onTick`, sets state, and
**stamps each boundary fact at `(t, 0) + delay`, buffers it** in the same `pending` queue — so it is not in
`drainBelow(t)`, is released at `Tick(t+1)`, and sorts first (intraTick 0) in tick `t`'s window; post-horizon
boundary facts fall into the existing residue summary. Migrated the 5 `onTick` overrides
(`DynamoDbTable` + `TokenBucket`/`FlatThrottle`/`Chaos`/`Latency` gates — each a one-line `TickEmission(_, Nil)`
wrap, `Cons = Nothing` for gates) and 7 test call sites (`.onTick(…)` → `.onTick(…).newState`, plus one test
sampler's override). New `ScheduleReleaseTransducerSpec` case proves a boundary fact lands in-window ordered
first, carries `TickBoundaryUsecase`, keeps request/response 1:1, and that a post-horizon boundary fact is
residue. No domain features (TTL/txn are Slices 2–5).

### Slice 2 — TTL mechanism
A deterministic ring-buffer expiry model — writes-per-tick tracked in a `ttlPeriodTicks + 1`-slot history
threaded in the **immutable** `TableState` (so it stays pure/deterministic); at each tick boundary drain the
slot written `ttlPeriodTicks` ago → the items expiring now. A `TableBehavior` TTL hook produces the expiry;
`DynamoDbTable.onTick` emits the negative base-table `StorageBytesDelta` and shrinks `state.base`.
`ttlPeriodTicks` config on the scenario/table.

**Validated by:** unit tests — items expire exactly `ttlPeriodTicks` after their write; base bytes freed;
byte-ticks reflect the reduction; no capacity consumed; TTL-off (no `ttlPeriodTicks`) byte-identical.

*Design point:* pick a ring-buffer representation whose per-write copy cost stays acceptable at
`ttlPeriodTicks = 720`.

**Delivered.** A new immutable `TtlRingBuffer` (`aws/…/dynamodb/TtlRingBuffer.scala`) — the functional
counterpart of the legacy mutable `SimpleTtlSampler`: `ttlPeriodTicks + 1` `Vector`-backed slots of
`(count, bytes)`; `recordWrite` / `recordDelete` (soonest-to-expire approximation) / `expire` each return a
new buffer. `Vector.updated` is ~O(log n), settling the copy-cost design point. It threads through the
**immutable** `TableState` as `ttl: Option[TtlRingBuffer]` (`None` = off); `TableState.initial` gains
`ttlPeriodTicks` (pre-loaded items carry no write tick, so they never TTL-expire — matching legacy).
**TTL is generic table mechanics, not a behavior hook** (the expiry is deterministic): `DynamoDbTableSampler.sample`
feeds the buffer from the write footprint it already computes (insert → write; overwrite → delete+write,
re-aging; delete → delete) in the two admitted branches only (a throttle-reject touches nothing), and
`onTick` drains the cohort written `ttlPeriodTicks` ago, shrinking `state.base` **and each index** and
emitting negative, target-tagged `StorageBytesDelta` facts (base + GSI + LSI, projection-sized via
`SecondaryIndexMechanics.projectedEntryBytes` — the exact inverse of write-time maintenance), consuming
**no** capacity. `TableSummaryState.applyExpiry(count, bytes)` bulk-shrinks (bytes clamped ≥ 0). The
accounting is untouched — it already folds target-tagged `StorageBytesDelta` into total storage, so the
boundary-stamped freeing (released first in the tick's window) flows into byte-ticks and `finalStorageBytes`.
Config threaded end-to-end (`DynamoDbTable.Config.ttlPeriodTicks`, `TableSpec.ttlPeriodTicks`,
`TableLegRunner`). New `TtlRingBufferSpec` (7 pure cases) + `DynamoDbTableTtlSpec` (4 stream cases, incl.
per-index freeing and TTL-off byte-identical). **core 512 / aws 181 green**; every existing reconciliation
spec byte-identical (TTL defaults off).

### Slice 3 — TTL demo (session-store) + delete/expire coverage + docs
A **bespoke session-store** scenario (not the thermostat — that is a *device registry* bounded by fleet
size, where writes overwrite and items never accumulate, so TTL has nothing to cap). A session store is the
canonical **accumulate-then-expire** shape: each sign-in inserts a new session that expires after a fixed
idle timeout, so storage plateaus. Plus a **delete-vs-expire** mechanism test (the OTP/early-logout case) —
an item deleted before its TTL must be freed exactly once.

**Validated by:** storage **plateaus** (creations ≈ expiries once the TTL horizon is reached) far below an
identical no-TTL run; a pre-TTL delete shrinks the expiring cohort (no double-free); determinism.

**Delivered.** New `examples/sessionstore` package: `SessionStoreConfig` (`SingleTableScenario`:
`sessionsPerTick`/`validationsPerTick`/`sessionBytes`/`ttlPeriodTicks`, empty table, one KeysOnly
`user-sessions` GSI so base **and** index freeing are exercised), `SessionStoreBehavior` (writes always
insert; validate = strong `GetItem`), `SessionStoreWorkload` (per-tick Poisson create/validate),
`SessionStoreDemo` (`@main`, JSONL + console incl. a three-tick storage-plateau sample). The only harness
change: `SingleTableScenario` gained `ttlPeriodTicks: Option[Int] = None`, passed through `tableSpec` — the
thermostat is **untouched**. Tests: `SessionStoreSpec` (plateau climbs then flattens; no-TTL caps far
higher; determinism) + a delete-vs-expire case in `DynamoDbTableTtlSpec` (3 inserts, 1 deleted early, TTL=2
→ expiry frees the 2 survivors, not all 3; table returns to empty). Docs: new
`specs/README.session-store-ttl.md` + TTL entry/scope in `specs/aws-component-catalog.md`. Demo run
(20 trials, 1800 ticks, TTL 600): storage 6.3 M @300 → 12.7 M @600 → 12.7 M @1800 (flat). **aws 185 green**,
every existing reconciliation spec byte-identical.

### Slice 4 — Transactions mechanism
`TransactWriteItems` / `TransactGetItems` protocol variants (carrying multiple item bytes); behavior/mechanics
resolving them at **2× capacity** per item, **atomic** all-or-nothing, with storage and per-index maintenance.

**Capacity rule researched against AWS, not the legacy** (Brian: follow AWS first; ignore the legacy where it
diverges). A transaction is a two-phase commit, so the doubling is **target-dependent**: the base-table write
and its **synchronous, co-located LSI** maintenance are billed **2×**; a **GSI** back-fill propagates
*asynchronously after* commit and is billed **1×** (standard); transactional reads are 2× strongly consistent
per item. The legacy billed both index types at 1× — we diverge on **LSI** deliberately. (Sources: AWS
transactions doc — "two underlying writes of every item" + "changes start propagating to GSIs… gradually.")

**Validated by:** unit tests — base+LSI 2× / GSI 1× per item; atomic (the whole transaction commits, or under
a tight provisioned budget nothing does); storage + per-index maintenance + TTL recording across all
sub-writes; determinism.

**Delivered.** `ThroughputMath.transactionalWriteMultiplier(target)` (Gsi→1, base/Lsi→2) +
`transactionalReadCapacityUnits` (2× strong). Protocol: `TransactWriteItemsRequest(perItemBytes)` /
`TransactGetItemsRequest(itemCount)` + responses. `TableMechanics`: `TransactWriteItem` + `OperationOutcome.
TransactWrite/TransactGet`; `resolve` threads the base summary through all sub-writes (base 2× WCU + per-item
storage delta) / bills 2× strong RCU per get. `SecondaryIndexMechanics.maintain` gained a defaulted
`transactional` flag (LSI 2× / GSI 1×; default false → byte-identical). `DynamoDbTable.sample` generalized
its single `writeFootprint` to a **list** so index maintenance *and* TTL recording iterate over every
sub-write (single-op = one-element list → byte-identical); the admit/throttle path already treats a
transaction's summed demand as a unit → all-or-nothing. `OrderTrackingBehavior` gained an explicit
unsupported-op rejection (it enumerated request types without a catch-all). Tests: `ThroughputMathSpec` +2,
`TableMechanicsSpec` +2, `SecondaryIndexMechanicsSpec` +2, new `DynamoDbTableTransactionSpec` +5 (base/LSI 2×
+ GSI 1× + per-target storage; atomic throttle-reject applies nothing; TTL over sub-writes expires the whole
set; 2× strong txn-get; determinism). Catalog updated with the AWS-accurate rule. **aws 196 green**; single-op
paths byte-identical. *(No `@main`/demo/scenario config — that is Slice 5, per DQ-txn-config-scope.)*

### Slice 5 — Transactions demo (payments ledger) + docs
A bespoke **payments / double-entry ledger** scenario (not a thermostat "commands" preset — like TTL, the
existing domains don't fit): each money transfer is an atomic `TransactWriteItems` of two balance writes
(debit + credit), each balance check a `TransactGetItems`. A `useTransactions` toggle runs the *identical*
workload as individual `UpdateItem` / `GetItem` operations, so billing both ways shows the exact premium.

**Validated by:** transactional writes **and** reads bill ≈2× the equivalent single operations; storage flat
(same-size overwrites); determinism.

**Delivered.** New `examples/payments` package: `PaymentsLedgerConfig` (`SingleTableScenario`; pre-loaded
account population, `transactWriteItemsPerItemBytes` = the Slice-4-deferred knob, `useTransactions` toggle,
no indexes), `PaymentsLedgerBehavior` (transfer → `TransactWrite` of same-size overwrites / `Update`s;
balance check → `TransactGet` / `Get`s), `PaymentsLedgerWorkload` (per-tick Poisson transfers + checks,
emitted per the toggle), `PaymentsLedgerDemo` (`@main`; runs both modes, prints the premium ratios). Demo
run (12 trials, 300 ticks): **write premium 1.99×, read premium 2.01×**, storage flat at 40 MB.
`PaymentsLedgerSpec` (write ≈2×, read ≈2×, storage flat, determinism). Docs: new
`specs/README.payments-transactions.md` + a `README.md` demo entry + catalog "Exercised by" cross-link.
**aws 200 green.** *(Phase close-out — roadmap COMPLETE header, CLAUDE.md, program roadmap, memory-complete —
is the separate Slice-5 coda.)*

## Scope boundary

Single-region, single-table. No auto-scaling (phase 8), no multi-region (phase 9). TTL frees base-table
**and secondary-index** storage (the symmetric inverse of index maintenance). Transactions are
`TransactWriteItems` / `TransactGetItems` only. Full legacy reconcile of TTL + transactions happens in the
phase-8 capstone.
