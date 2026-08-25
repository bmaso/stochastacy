# Order-Tracking (DynamoDB on the v2 core) — Engineer's Guide

The first AWS demo re-built on the domain-agnostic v2 engine: a **single on-demand DynamoDB table** under
a mixed read/write workload, estimating its capacity consumption, storage growth, and cost across a Monte
Carlo ensemble. It re-implements the legacy `ordertracking` Phase-1 demo on the new `stochastacy.core`
abstractions, and is proven — by an equivalence gate — to reproduce the legacy demo's aggregate behavior.

The example lives in the `aws/` module, package `stochastacy.aws.examples.ordertracking`; the reusable
table component it drives lives in `stochastacy.aws.dynamodb` (see the
[AWS component catalog](aws-component-catalog.md)).

---

## 1. What the demo demonstrates

### The fictional domain
An **order-tracking service** backed by one DynamoDB table, `orders`. Orders are created (`PutItem`),
looked up (`GetItem`), amended (`UpdateItem`), and cancelled (`DeleteItem`). The table starts with a small
seed population and grows as the workload runs. There are no secondary indexes and no throttling — this is
the smallest complete DynamoDB scenario, chosen to establish the table component before the advanced
machinery.

### The shape of the simulation
```
workload (4 Poisson flows) → [ DynamoDbTable ] → consumption plane → usage → on-demand cost
                                    │
                                    └→ responses (discarded by this demo)
```
Each request flows through the table, which emits a response and the **consumption facts** it produced —
read/write capacity units and a storage-byte delta. The runner folds that metric plane into per-tick and
per-trial totals, prices it, and a Monte Carlo runner repeats the whole thing across many seeded trials to
get the *distribution* of outcomes, not just one run.

The demo surfaces four things:

- **Capacity consumption** — RCU from reads (4 KB chunks, strongly consistent ×1) and WCU from writes
  (1 KB chunks), summed per tick and per trial.
- **Storage growth** — the table's byte total evolves as puts add items, updates resize them, and deletes
  remove them; storage is integrated over ticks into byte-ticks.
- **On-demand cost** — capacity units × unit price + storage byte-ticks × storage price.
- **Run-to-run variance** — the Monte Carlo ensemble yields across-trial mean and standard deviation for
  every metric.

---

## 2. Results

### Equivalence with the legacy demo
The demo's reason for existing is parity: it must behave like the legacy Phase-1 demo. The gate
(`OrderTrackingEquivalenceSpec`) runs the v2 ensemble at the legacy's configuration (100 trials × 30
ticks) and compares across-trial means to a **captured legacy baseline**:

| metric | v2 vs. legacy | band |
|---|---|---|
| mean total read capacity units | **2.4%** | ±5% |
| mean total write capacity units | **1.8%** | ±5% |
| mean total estimated cost | **1.2%** | ±5% |
| mean final storage bytes (vs. legacy + initial) | **0.9%** | ±10% |

The ~2% gaps are consistent with the sampling error of two 100-trial ensembles drawn from *independent*
RNG streams (v2 and legacy share the workload rates and per-op math, so only their expectations coincide,
not their exact draws). This is genuine equivalence, not a loose pass.

### A legacy bug, fixed
The storage row is compared against **legacy + initial storage** on purpose. The legacy demo integrated
storage from zero and only ever moved it by operation deltas — so the table's *pre-loaded* items (10 ×
768 B = 7 680 B) were **never billed**: `FinalStorageBytes`, byte-ticks, and storage cost all reflected
only the net change during the run, not the bytes actually stored. Against real DynamoDB (which bills all
stored data) that undercounts. The v2 accounting **seeds the fold with the table's initial storage**, so
it bills correctly. The gate confirms the correction: v2's final storage exceeds the legacy's by ≈ the
7 680-byte initial term. (`TotalStorageByteTicks` is intentionally *not* gated — the legacy's own summary
and time-series paths disagree on their accrual count, so it carries no clean signal.)

---

## Indexed Order-Tracking (Query/Scan + secondary indexes)

A second scenario, `indexedDefault`, extends the same table with the read side of an order-tracking
service: **Query and Scan** over **two GSIs** — `customerId-status` (a customer's orders by status) and
`sellerId-createdAt` (a seller's orders by time) — and **one LSI**, `createdAt-priority`. It re-implements
the legacy `order-tracking-phase2` scenario.

### What it adds
- **Secondary indexes, declared on the table.** Each write fans out maintenance to every index (its own
  WCU + storage, GSI asynchronously / LSI synchronously), sized by the index's projection (`All` here).
  Indexes are configured on the `DynamoDbTable`, never wired as graph nodes — see the
  [AWS component catalog](aws-component-catalog.md#dynamodbtable).
- **Query/Scan with per-index metrics.** Reads target the base table or a GSI (GSI reads are eventually
  consistent). The JSONL breaks out per-GSI capacity under the legacy names
  `GSI:<name>:ReadCapacityUnits` / `WriteCapacityUnits` (and `Total…`).

### The improved read model (why v2 diverges from legacy, on purpose)
A read consults **the target's own state**: a **scan evaluates the whole target** (its item count and
projected bytes — so scan cost *grows with the table*), and a **query** evaluates a bounded page (a Poisson
selectivity draw capped at the target's population). The legacy modeled every read as a capped few items
(4–6) off the base table's size — so its scan cost never grew with the data, and projection was ignored.
We deliberately fixed this; the assumptions are explicit config (`queryEvaluatedItemsMean`,
`returnedFraction`), not magic constants.

### Reconciliation with legacy
Because the read model changed on purpose, the gate (`OrderTrackingIndexedReconciliationSpec`) is a
**reconciliation, not a blind match** — equivalence on the faithful path, quantified divergence where we
improved (100 trials × 30 ticks):

| metric | v2 vs legacy | verdict |
|---|---|---|
| total **write** capacity units | **−1.5%** (band ±5%) | equivalent — writes + index maintenance replicate the legacy math |
| per-GSI **write** capacity units | **−1.6%** (band ±10%) | equivalent — index maintenance matches |
| total **read** capacity units | **+41%** | *deliberate* — scans now read the whole target |
| final storage bytes | ≈ legacy **+ all-targets initial** (≈30.7 KB), within ±15% | *corrected* — v2 bills every target's pre-loaded storage the legacy dropped |
| total estimated cost | **+1.7%** | mostly write-driven; the read divergence is a small share of the bill |

### Running it
```bash
sbt 'aws/runMain stochastacy.aws.examples.ordertracking.IndexedOrderTrackingDemo --output /tmp/order-tracking-indexed.jsonl --trials 100 --ticks 30 --seed 1'
```
Same flags as the Phase-1 demo; the console summary adds a per-GSI RCU/WCU line. To run the reconciliation
gate: `sbt 'aws/testOnly stochastacy.aws.examples.ordertracking.OrderTrackingIndexedReconciliationSpec'`.

---

## 3. Running the demo

No external services — the demo writes JSONL plus a console summary.

```bash
sbt 'aws/runMain stochastacy.aws.examples.ordertracking.OrderTrackingDemo --output /tmp/order-tracking-phase1.jsonl --trials 100 --ticks 30 --seed 1'
```

Flags (all optional; unset values fall back to `OrderTrackingConfig.phase1Default`): `--output`, `--seed`,
`--trials`, `--ticks`, `--parallelism` (does not affect results). The scenario itself — table size, hit /
existing-item probabilities, flow rates, item-byte ranges — lives in `OrderTrackingConfig`; edit
`phase1Default` (or `.copy(...)`) to explore other regimes.

The JSONL carries four record kinds — `trial-time-series`, `trial-summary`, `aggregate-time-series`,
`aggregate-summary` — keyed by `scenarioId` / `trialId` (or `trialCount`) / `tick` / `metric` /
`statistic`, in the legacy demo's record shape, so the existing Grafana queries bind unchanged. Metric
names are `ReadCapacityUnits` / `WriteCapacityUnits` / `StorageBytes` / `CumulativeEstimatedCost`
(per-tick) and `Total…` / `FinalStorageBytes` / `TotalEstimatedCost` (summary); aggregate statistics are
`mean` and `stddev`.

To run the equivalence gate:
```bash
sbt 'aws/testOnly stochastacy.aws.examples.ordertracking.OrderTrackingEquivalenceSpec'
```

---

## 4. Internals

### 4.1 The table and its domain
The table is the reusable `DynamoDbTable` component (`stochastacy.aws.dynamodb`) — generic mechanics with
an injected `TableBehavior`. This demo supplies `OrderTrackingBehavior`, a faithful port of the legacy
`UseCaseSampler`: a get hits with probability 0.85 (returning a jittered ±25 % item size, else a miss); a
put always writes a new item; an update / delete targets an existing item with probability 0.9 / 0.75
(else an upsert / no-op). The behavior draws the *outcome*; `TableMechanics.resolve` turns it into the
response, the RCU/WCU/storage facts, and the next `TableSummaryState`. See the
[AWS component catalog](aws-component-catalog.md) for the component's contract.

### 4.2 The workload
`OrderTrackingWorkload.arrivals` generates the Phase-1 traffic directly (no ips `WorkloadDsl`): four
Poisson flows — put (λ 0.8, items U(672, 1120) B), get (λ 2.5), update (λ 1.2, items U(768, 1280) B),
delete (λ 0.4) — emitted per tick with a uniform-random intra-tick position, tagged with the scenario id.
`TickFraming` frames the arrivals into the `Tick`-windowed, `EndOfTime`-terminated stream the table
consumes.

### 4.3 One trial
The demo runs on a **shared single-table harness** (`stochastacy.aws.examples.demo`): `OrderTrackingConfig`
implements `SingleTableScenario`, and the generic `SingleTableTrialRunner.runTrial` wires
`workload → DynamoDbTable`, discards the responses, and folds the consumption plane **incrementally as it
flows** (a `Sink.fold`, so a trial never holds its raw facts) into a `TrialResult`: totals (RCU, WCU,
byte-ticks, final storage, cost) plus a per-tick series that always reconciles with the summary.
`OnDemandPricing` supplies the on-demand rates and the cost formula. The fold is **seeded with the table's
initial storage** (§2). The workload and table draw from independent derived rngs, so the eagerly-generated
arrivals and the table's sampling do not share a stream.

### 4.4 The ensemble
`SingleTableMonteCarloRunner` drives the core `MonteCarlo.stream` — `trialCount` reproducible trials from
one master seed, order-stable and parallelism-independent — folding each completed trial into an
`IncrementalAggregator` (running moments per metric → across-trial mean and population standard deviation)
and then releasing it. The `@main`'s `runToFile` streams each trial's records to disk through a `JsonlWriter`
as it completes and appends the aggregates at the end, so memory stays flat in the trial count and the file
grows during the run; a collecting `run` variant (returning every trial) backs the tests and gates.

---

## Source map

The demo-specific pieces live in `stochastacy.aws.examples.ordertracking`; the runner / accounting /
aggregation / export are the **shared single-table harness** in `stochastacy.aws.examples.demo`.

| concern | file |
|---|---|
| scenario config (incl. flow rates / byte ranges) | `ordertracking/OrderTrackingConfig.scala` (implements `demo/SingleTableScenario`) |
| domain behavior | `ordertracking/OrderTrackingBehavior.scala` |
| workload driver | `ordertracking/OrderTrackingWorkload.scala` |
| single-trial runner (streaming fold) | `demo/SingleTableTrialRunner.scala` |
| accounting + pricing + result types | `demo/TrialAccounting.scala`, `demo/OnDemandPricing.scala`, `demo/TrialResult.scala` |
| Monte Carlo + incremental aggregation | `demo/SingleTableMonteCarloRunner.scala`, `demo/IncrementalAggregator.scala`, `demo/MonteCarloAggregation.scala`, `demo/MonteCarloResult.scala` |
| JSONL export (streaming writer + records) | `demo/JsonlWriter.scala`, `demo/JsonlExport.scala` |
| `@main` | `ordertracking/OrderTrackingDemo.scala`, `ordertracking/IndexedOrderTrackingDemo.scala` |
| equivalence / reconciliation gates | `test/.../OrderTrackingEquivalenceSpec.scala`, `test/.../OrderTrackingIndexedReconciliationSpec.scala` |
| the reusable table component | `stochastacy.aws.dynamodb.*` |

## See also

- [AWS component catalog](aws-component-catalog.md) — the `DynamoDbTable` component as a reusable building block.
- [Core component catalog](component-catalog.md) — the engine the table is built on.
- [Store Demo V2](README.store-demo-v2.md) — the other v2 demo (a gated service edge).
