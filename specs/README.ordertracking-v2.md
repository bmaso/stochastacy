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
`OrderTrackingTrialRunner.runTrial` wires `workload → DynamoDbTable`, discards the responses, and drains
the consumption plane. `TrialAccounting` folds it — in a single pass, so the summary and per-tick series
always reconcile — into an `OrderTrackingTrialResult`: totals (RCU, WCU, byte-ticks, final storage, cost)
plus a per-tick series. `OnDemandPricing` supplies the on-demand rates and the cost formula. The fold is
**seeded with the table's initial storage** (§2). The workload and table draw from independent derived
rngs, so the eagerly-generated arrivals and the table's sampling do not share a stream.

### 4.4 The ensemble
`OrderTrackingMonteCarloRunner.run` drives the core `MonteCarlo.run` — `trialCount` reproducible trials
from one master seed, order-stable and parallelism-independent — then `MonteCarloAggregation` reduces the
trials to across-trial mean and (population) standard deviation per metric. `JsonlExport` renders the
per-trial and aggregate records as JSONL.

---

## Source map

| concern | file |
|---|---|
| scenario config (incl. flow rates / byte ranges) | `OrderTrackingConfig.scala` |
| domain behavior | `OrderTrackingBehavior.scala` |
| workload driver | `OrderTrackingWorkload.scala` |
| single-trial runner | `OrderTrackingTrialRunner.scala` |
| accounting + pricing + result types | `TrialAccounting.scala`, `OnDemandPricing.scala`, `TrialResult.scala` |
| Monte Carlo + aggregation | `OrderTrackingMonteCarloRunner.scala`, `MonteCarloAggregation.scala`, `MonteCarloResult.scala` |
| JSONL export | `JsonlExport.scala` |
| `@main` | `OrderTrackingDemo.scala` |
| equivalence gate | `test/.../OrderTrackingEquivalenceSpec.scala` |
| the reusable table component | `stochastacy.aws.dynamodb.*` |

## See also

- [AWS component catalog](aws-component-catalog.md) — the `DynamoDbTable` component as a reusable building block.
- [Core component catalog](component-catalog.md) — the engine the table is built on.
- [Store Demo V2](README.store-demo-v2.md) — the other v2 demo (a gated service edge).
