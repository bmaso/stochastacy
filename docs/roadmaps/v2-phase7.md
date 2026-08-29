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
| 2 | TTL mechanism | Planned | items expire exactly `ttlPeriodTicks` after write; base bytes freed; byte-ticks reflect it; no capacity consumed; TTL-off byte-identical |
| 3 | TTL demo + docs | Planned | storage plateaus (TTL caps growth) rather than rising unbounded; determinism; TTL doc note |
| 4 | Transactions mechanism | Planned | 2× WCU/RCU per item; atomic all-or-nothing; storage + per-index maintenance; determinism |
| 5 | Transactions demo + docs + phase close-out | Planned | transactions bill 2× vs equivalent singles; determinism; phase COMPLETE |

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

### Slice 3 — TTL demo + docs
A single-region thermostat **TTL** preset (the telemetry workload with `ttlPeriodTicks`) + a `@main` +
end-to-end. Docs: a catalog/README TTL note. Reconcile deferred to the capstone (noted).

**Validated by:** storage **plateaus** (TTL caps growth) rather than rising unbounded; determinism.

### Slice 4 — Transactions mechanism
`TransactWriteItems` / `TransactGetItems` protocol variants (carrying multiple item bytes); behavior/mechanics
resolving them at **2× capacity** per item, **atomic** all-or-nothing, with storage and per-index maintenance;
`transactWriteItemsPerItemBytes` config.

**Validated by:** unit tests — 2× WCU/RCU per item; atomic (the whole transaction commits or nothing does);
storage + per-index maintenance; determinism.

### Slice 5 — Transactions demo + docs + phase close-out
A single-region **commands** preset (a transaction flow) + a `@main` + end-to-end. Docs: catalog/README
updates; roadmap + memory close-out.

**Validated by:** transactions bill 2× vs equivalent single writes; determinism; phase COMPLETE.

## Scope boundary

Single-region, single-table. No auto-scaling (phase 8), no multi-region (phase 9). TTL frees base-table
storage (GSI/LSI freed-bytes modeled only if the capstone reconcile later needs it). Transactions are
`TransactWriteItems` / `TransactGetItems` only. Full legacy reconcile of TTL + transactions happens in the
phase-8 capstone.
