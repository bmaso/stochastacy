# Payments-Ledger Transactions (DynamoDB on the v2 core) — Engineer's Guide

A small, purpose-built demo that shows what **transactions** cost: a payments service that moves money
between accounts, where each transfer must be atomic. A **transfer** is a single `TransactWriteItems` of two
writes — a **debit** and a **credit** — that both commit or neither does; a **balance check** is a
`TransactGetItems` reading those accounts atomically. Because a DynamoDB transaction is a two-phase commit
(prepare + commit), both operations cost **2×** the capacity of the equivalent single operations — the
premium this demo makes concrete.

The example lives in the `aws/` module, package `stochastacy.aws.examples.payments`; the reusable table
component it drives lives in `stochastacy.aws.dynamodb` (see the
[AWS component catalog](aws-component-catalog.md#the-dynamodb-table)).

---

## 1. What the demo demonstrates

### The fictional domain
A **double-entry ledger** backed by one on-demand DynamoDB table pre-loaded with an account population. Each
tick a Poisson number of **transfers** move money (each a `TransactWriteItems` updating two account balances
in place) and a Poisson number of **balance checks** read accounts (`TransactGetItems`). Transfers overwrite
same-size balances, so **storage stays flat** — the demo isolates the *capacity* premium, not storage growth
(that story belongs to [session-store TTL](README.session-store-ttl.md)).

### The 2× premium — proven by running the same work both ways
The scenario carries a `useTransactions` flag. With it **on**, transfers are `TransactWriteItems` and checks
are `TransactGetItems`; with it **off**, the *identical* work runs as individual `UpdateItem`s and
`GetItem`s. Billing the same workload both ways exposes the premium directly. A representative run:

| | transactional | single-op | ratio |
|---|---:|---:|---:|
| write capacity units | ~59,800 | ~30,000 | **1.99×** |
| read capacity units  | ~36,000 | ~17,900 | **2.01×** |

The transactional writes and reads land at ≈2× their single-operation equivalents — the two-phase-commit
cost. (Per AWS billing, a transaction over secondary indexes would bill base + synchronous LSI maintenance
at 2× but async GSI back-fill at 1×; this index-free demo isolates the base-table premium, and the
target-dependent rule is covered by the mechanism tests.)

## 2. Running the demo

No external services; exports JSONL plus a console summary that prints both runs and the premium ratios.

```bash
sbt 'aws/runMain stochastacy.aws.examples.payments.PaymentsLedgerDemo --output /tmp/payments-ledger.jsonl --trials 100 --ticks 1200 --seed 1'
```

Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>`
`--parallelism <int>`; unset values fall back to `PaymentsLedgerConfig.default`.

## 3. Internals

- **`PaymentsLedgerConfig`** — a `SingleTableScenario`: `accountCount` / `accountBytes` (the pre-loaded
  population), `transfersPerTick` / `balanceChecksPerTick`, `transactWriteItemsPerItemBytes` (the transfer's
  per-item sizes, e.g. `Vector(200, 150)`), and `useTransactions`.
- **`PaymentsLedgerBehavior`** — resolves both forms: a transfer as a `TransactWrite` of same-size balance
  overwrites (storage flat, WCU 2×) or the equivalent `Update`s (1×); a balance check as a `TransactGet`
  (2× strong RCU) or the equivalent `Get`s (1×).
- **`PaymentsLedgerWorkload`** — per-tick Poisson transfer/check counts, emitted in transactional or
  single-operation form per the flag.

## 4. What proves it

- `PaymentsLedgerSpec` (end-to-end): transactional writes and reads each bill ≈2× the identical
  single-operation run; storage stays flat (same-size overwrites); determinism under a fixed seed.
- The transactional mechanics themselves — base+LSI 2× / GSI 1×, atomic all-or-nothing, TTL across
  sub-writes — are covered by `DynamoDbTableTransactionSpec`, `TableMechanicsSpec`,
  `SecondaryIndexMechanicsSpec`, and `ThroughputMathSpec`.
