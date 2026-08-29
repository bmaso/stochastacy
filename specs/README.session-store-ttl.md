# Session-Store TTL (DynamoDB on the v2 core) — Engineer's Guide

A small, purpose-built demo that shows what **item TTL** does to a DynamoDB table's storage and cost: a
login service that writes one session item per sign-in, each expiring after a fixed idle timeout. Because
every write is a **new** session (never an overwrite), items **accumulate** — and TTL is what bounds them.
With TTL on, stored bytes climb for `ttlPeriodTicks` ticks and then **plateau** (creations per tick ≈
expiries per tick); with TTL off they rise unbounded.

The example lives in the `aws/` module, package `stochastacy.aws.examples.sessionstore`; the reusable table
component it drives lives in `stochastacy.aws.dynamodb` (see the
[AWS component catalog](aws-component-catalog.md#the-dynamodb-table)).

---

## 1. What the demo demonstrates

### The fictional domain
A **session store** backed by one on-demand DynamoDB table. Each tick a Poisson number of sessions are
**created** (`PutItem`, a fresh id — always an insert) and a Poisson number are **validated** (`GetItem`).
One KeysOnly GSI, `user-sessions` (sessions by user), rides along — so the demo exercises **base *and*
index** TTL freeing, not just the base table. Each session is given an idle-timeout TTL of `ttlPeriodTicks`.

This domain is deliberately different from the thermostat fleet, which is a *device registry* bounded by
fleet size (writes overwrite existing devices, so items never accumulate and TTL has nothing to cap). A
session store is the canonical **accumulate-then-expire** shape TTL exists for.

### The shape of the simulation
```
workload (2 Poisson flows: create + validate) → [ DynamoDbTable + ttlPeriodTicks ] → consumption plane → cost
```
On each tick boundary the table drains the cohort of sessions written `ttlPeriodTicks` ago and frees their
base and GSI storage (negative, target-tagged `StorageBytesDelta`, **no capacity consumed**), via the core's
tick-boundary consumption emission. The accounting folds that metric plane into per-tick and final storage,
so the plateau appears directly in the storage-byte series.

### What TTL does to storage
With a 600-tick TTL over an 1800-tick run, the mean stored-bytes series climbs to the TTL horizon, then
holds flat as creations and expiries balance:

| tick | mean stored bytes |
|-----:|------------------:|
| 300 (mid-climb)   | ~6.3 M  |
| 600 (TTL horizon) | ~12.7 M |
| 1800 (end)        | ~12.7 M |

An otherwise identical run with TTL off keeps climbing to ~3× that final figure. TTL turns unbounded
storage growth into a bounded, steady-state cost.

## 2. Running the demo

No external services; exports JSONL plus a console summary (which prints the three-tick storage-plateau
sample above).

```bash
sbt 'aws/runMain stochastacy.aws.examples.sessionstore.SessionStoreDemo --output /tmp/session-store-ttl.jsonl --trials 100 --ticks 1800 --seed 1'
```

Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
`--parallelism <int>`; unset values fall back to `SessionStoreConfig.default`
(`ttlPeriodTicks = Some(600)`).

## 3. Internals

- **`SessionStoreConfig`** — a `SingleTableScenario`: `sessionsPerTick` / `validationsPerTick` /
  `sessionBytes` / `ttlPeriodTicks`, the empty initial table, and the `user-sessions` KeysOnly GSI. Runs on
  the shared single-table demo harness (`SingleTable{Trial,MonteCarlo}Runner`).
- **`SessionStoreBehavior`** — writes are always inserts (`previousItemBytes = None`); a validation is a
  strongly-consistent `GetItem` of the table's average size.
- **`SessionStoreWorkload`** — per-tick Poisson create/validate counts at random intra-tick offsets.
- **`ttlPeriodTicks`** is passed straight through `SingleTableScenario.tableSpec` to `DynamoDbTable.Config`;
  the TTL mechanics themselves are generic table config (see the catalog).

## 4. What proves it

- `SessionStoreSpec` (end-to-end): storage climbs toward the TTL horizon then plateaus; a no-TTL run of the
  same workload/seed accumulates far more; determinism under a fixed seed.
- `DynamoDbTableTtlSpec` / `TtlRingBufferSpec` (mechanism): expiry timing, base + per-index projection-sized
  freeing with no capacity consumed, **an item deleted before its TTL is freed exactly once** (the expiring
  cohort shrinks — no double-free), and TTL-off byte-identity.

The full legacy reconcile of TTL happens in the phase-8 capstone; this demo validates the mechanism and its
storage effect directly.
