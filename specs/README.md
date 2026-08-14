# Store Simulator — Engineer's Guide

A worked example that drives the domain-agnostic `stochastacy.core` simulation engine. It models a
RESTful entity store (a thin API service in front of a datastore) and runs a **Monte Carlo ensemble**
of a realistic workload, then summarizes the statistics that make three cost phenomena *visible*.

The example lives in `examples/src/main/scala/stochastacy/examples/store/`. It was built in phase 0
(`docs/roadmaps/v2-phase0.md`) as the forcing function that proves the core abstractions carry a real
domain with **no AWS-specific code** in the engine.

---

## 1. What the demo demonstrates

The store is deliberately simple — `Get`, `Put`, `Delete`, list queries, and aggregate reports over a
bounded-summary state (`StoreState(entityCount, totalBytes)` — no per-key maps). Its value is that it
exhibits three **emergent cost behaviors** that are individually familiar to engineers but rarely seen
*together, quantified, and with their run-to-run variance made explicit*.

### The fictional domain

Concretely, picture a **product-catalog service** for an online store. Each *entity* is a catalog item
(a product), and the API offers the usual lifecycle plus two read patterns:

- **Point operations** — `GetEntity` / `CreateEntity` / `UpdateEntity` / `DeleteEntity`: fetch or mutate
  a single product by id (O(1) work).
- **List queries** (`ListEntities`) — browse a *category* of products one page at a time, ordered by an
  indexed key. This is where the pagination strategy (keyset vs. offset) matters.
- **Report queries** (`GetReport`) — a merchandising/analytics report that **scans the whole matched
  set** to compute grouped aggregates (say, item counts per sub-category), returning only a small paged
  group summary.

The workload sustains a stream of `CreateEntity`s, so the catalog **grows over the run** — which is
precisely what makes report queries progressively more expensive (see 1a). A terminology note to head
off a collision: below, **"report" (noun) always means the `GetReport` query type**; where the document
means the verb — the demo *summarizing* its run — it says "summarize."

### 1a. Cardinality-driven cost rise (over a run)

Aggregate **report queries** (`GetReport` → a full scan) must evaluate the **whole matched set** to
compute their result, even though they return only a small paged summary. As the workload sustains
writes, the catalog grows, so every subsequent report query evaluates more rows and costs more. The cost of an *individual*
report request is therefore not stationary — it **rises over the life of the run** as cardinality
climbs. This is the classic "the report that was fast in staging is slow in production six months
later" effect, reproduced deterministically.

### 1b. The deep-offset pagination cliff

List queries paginate. The demo issues the *same* category-filter list under **two pagination
strategies** — keyset and offset — as two separately-labeled workload streams (`list.keyset` and
`list.offset`). They return identical pages of data but do wildly different amounts of work, and the
gap widens with page depth. This is the headline experiment; see the aside below.

### 1c. Load-driven throttling

An admission gate enforces a per-tick capacity. When offered load exceeds it, requests are throttled
and surface to the client as `ApiError("throttled")` (a 429). Because throttling keys off the
*instantaneous per-tick arrival count*, a workload whose **mean** rate is under capacity still throttles
during Poisson bursts — the mean-vs-tail gap, made observable.

### 1d. Pooled vs. across-trial statistics (why Monte Carlo)

Every run is one draw of a stochastic world. The demo runs **N independent trials** from one master
seed and reports two genuinely different aggregations:

- **Pooled** — merge every trial's observations into one population. Answers *"what does a random
  request from a random run look like?"* (the per-event distribution).
- **Across-trials** — reduce each trial to one scalar (e.g. its p99), then summarize those N scalars.
  Answers *"how does this metric vary run-to-run?"* — reliability / worst-case-run / the Monte Carlo
  variance. Because a single workload seed drives an entire run, our trials are internally correlated,
  so these two answers genuinely diverge.

The across-trial distribution is the thing a **simulator** produces cheaply from i.i.d. seeds but that
repeated real-world runs practically cannot: reality gives you one non-repeatable, drifting draw at a
time, so you can never cleanly separate "the system's run-to-run variance" from "the world changed."

### Aside — keyset vs. offset pagination

Both strategies fetch "the next page of `pageSize` rows" from an index-ordered set, and both return the
**same rows**. They differ only in *how the database finds the start of the page*.

- **Offset pagination** — `... ORDER BY k LIMIT pageSize OFFSET pageIndex*pageSize`. To return page *N*,
  the engine must walk the index from the beginning and **evaluate-then-discard** every row before the
  offset. Returning page 10 of size 20 evaluates `(10+1)*20 = 220` rows to hand back 20. Cost grows
  **linearly with page depth**.
- **Keyset pagination** (a.k.a. *cursor* or *seek* pagination) — the client passes the sort-key value
  of the last row it saw: `... WHERE k > :lastKey ORDER BY k LIMIT pageSize`. The engine **seeks
  directly** to that key through the index and reads only `pageSize` rows. Cost is **flat, independent
  of depth**.

Why the experiment is interesting: the two are functionally interchangeable — same results — so the
choice looks cosmetic. But their cost curves diverge, and a naive benchmark at *page 1* sees no
difference at all; the cliff only appears deep in the pagination, exactly where real users hit "jump to
page 500." A simulator sweeps the depth (and the table's growth) cheaply and shows the cliff without
needing a production incident to reveal it. In the model this is a single expression —
`StoreSampler.evaluatedForList`:

```
IndexOrdered + Keyset(pageSize)      → evaluated = min(matched, pageSize)              // flat
IndexOrdered + Offset(pageIndex,ps)  → evaluated = min(matched, (pageIndex+1)*ps)      // linear in depth
```

`evaluated` drives both the work accounting (`WorkPerformed`) and the latency
(`queryBaseLatency + latencyPerEvaluatedItem * evaluated`), so keyset and offset separate in every
cost metric.

---

## 2. Results

Running the capstone (`StoreDemo`, defaults: 8 trials × 200 ticks, 50-tick windows, seed 1) prints:

```
Store simulator — Monte Carlo summary (8 trials)
  cardinality rise : report latency window 0 mean=0.8901 -> window 3 mean=1.4119 (+58.6%)
  deep-offset cliff: list.offset latency p99=0.3332 vs list.keyset p99=0.2100 (1.6x)
  throttling       : create=22.5%, get=23.4%, list.keyset=24.1%, list.offset=22.1%, report=23.7%
```

and writes 272 JSONL records to the output file. Relating each line back to the goals:

### Cardinality rise (goal 1a)

The run is split into four 50-tick windows. Pooled `report` latency climbs monotonically as sustained
creates grow the table:

| window (ticks) | report latency mean | evaluated rows (`work.items`) |
|---|---|---|
| 0 (1–50)    | 0.8901 | ~1,200 |
| 1 (51–100)  | 1.0639 | ~1,500 |
| 2 (101–150) | 1.2357 | ~1,600 |
| 3 (151–200) | 1.4119 | ~2,500 |

A **+58.6%** rise in the *same request type* over one run, purely from the table growing underneath it.
The windowing is what makes this visible — a whole-run average would blur the trend away.

### Deep-offset cliff (goal 1b)

Same category-filter list, two pagination strategies, measured in the same run:

| stream | evaluated rows (`work.items`) | latency mean | latency p99 |
|---|---|---|---|
| `list.keyset` | **20** (flat = page size) | 0.2100 | 0.2100 |
| `list.offset` | **220** (= `(10+1)*20`) | 0.3100 | 0.3332 |

Offset evaluates **11× the work** of keyset (220 vs 20) at page index 10 — the cliff, exactly as the
cost model predicts. In *latency* the ratio is a milder ~1.6×, because a fixed `queryBaseLatency`
(0.20) dominates the per-item term at this page depth; `work.items` is the truer cost signal and is the
one the test suite asserts on. Note the two contrasting signatures: keyset stays flat at 20 regardless
of table size *or* depth; offset stays flat at 220 regardless of table size (its page depth is fixed in
this scenario) — while `report` (full scan) is the one metric that tracks cardinality.

### Throttling (goal 1c)

Offered load is ~23 req/tick against an admission capacity of 18, so every use-case is throttled at
roughly the same **~22–24%** rate (admission is use-case-agnostic — a hard total cap). Throttled
requests still return exactly one client response (a 429), preserving 1:1 request/response integrity.

### Across-trial variance (goal 1d)

The JSONL carries `acrossTrials` records alongside the `pooled` ones. For example the per-trial `report`
latency p99 has a non-zero stddev across the 8 trials — the run-to-run spread that the pooled number
alone cannot show.

---

## 3. Running the demo

No external services are required (the full Grafana/Postgres pipeline is intentionally out of scope for
this example — it exports JSONL + a text summary).

```bash
sbt 'examples/runMain stochastacy.examples.store.StoreDemo --output /tmp/store-demo.jsonl --trials 8 --ticks 200 --window 50 --seed 1'
```

### Command-line parameters

All are `--key value` and optional; defaults in parentheses:

| flag | default | meaning |
|---|---|---|
| `--output` | `/tmp/store-demo.jsonl` | path the JSONL statistics are written to |
| `--seed` | `1` | master seed; the whole ensemble is deterministic given this |
| `--ticks` | `200` | simulated ticks per trial (the run horizon) |
| `--trials` | `8` | number of independent Monte Carlo trials |
| `--window` | `50` | ticks per statistics window (governs the cardinality-rise resolution) |
| `--parallelism` | `4` | max trials run concurrently; **does not affect results**, only speed |

The **scenario itself is fixed in code**, not on the command line: the workload is
`ApiWorkloadConfig.capstone` (the labeled request streams) and the datastore/admission knobs are set in
`StoreDemo.scala` (`initialEntities = 1000`, `createRate = 0.9`, `latencyPerEvaluatedItem = 5e-4`,
admission `capacityPerTick = 18`). A small `initialEntities` with sustained creates is what makes the
write-driven cardinality rise visible over the run. Edit those to explore other regimes.

To run the assertions that verify the three behaviors (and the JSONL round-trip):

```bash
sbt 'examples/testOnly stochastacy.examples.store.StoreCapstoneSpec'
```

### Output format

The JSONL has one object per line, two `kind`s:

```json
{"kind":"pooled","usecase":"report","metric":"latency","window":3,"count":313,"mean":1.4119,"p50":1.4141,"p99":1.5511,"stddev":0.0504}
{"kind":"acrossTrials","usecase":"report","metric":"latency","window":3,"scalar":"p99","trials":8,"count":8,"mean":...,"stddev":...}
```

`pooled` records are the per-`(usecase, metric, window)` population statistics; `acrossTrials` records
are the run-to-run distribution of a representative per-trial scalar (p99 for latency-like metrics, mean
for the 0/1 `throttled` metric).

---

## 4. Internals

### 4.1 The three planes

The engine separates three concerns, and the store example instantiates each:

- **Description plane** (stochastic, declarative): the *workload* — what requests arrive, when, at what
  rate. `ApiWorkload` / `ApiWorkloadConfig` (`RequestStream`s).
- **Interaction plane** (fine-grained, materialized): every request/response/consumption event is a
  concrete `Timed[_]` element flowing through a Pekko Streams graph. No summarized "stochastic wire" —
  actual events.
- **Observation plane** (stochastic summaries): consumption events are folded into mergeable
  `Statistic`s keyed by `StoreStatKey`.

### 4.2 The component contract

Every stage is a `ComponentSampler[S, In, Out, Cons]`
(`core/component/SamplerContract.scala`):

```scala
trait ComponentSampler[S, In, Out, Cons]:
  def initialState: S
  def sample(in: In, state: S, rng): Emission[S, Out, Cons]   // per input: new state, one forward output, N consumption facts
  def onTick(tick: Long, state: S): S = state                 // per tick boundary (e.g. admission resets its counter)
```

`In`/`Out`/`Cons` are **timeless payloads**; timing is added by the generic **schedule-and-release
transducer** (`ScheduleReleaseTransducer`), which wraps a sampler into a Pekko `GraphStage`. The
transducer:

1. unwraps the `Timed[In]` envelope and runs `sample` on the payload,
2. stamps each scheduled output's absolute `(eventTime, intraTick)` from its `delay`
   (`rawOffset = inIntraTick + delay`),
3. buffers outputs in a priority queue and **releases them in time order at tick boundaries**,
4. summarizes any post-horizon residue into its materialized `Future[ComponentResult[S]]`.

Because every wire element is a uniform `Timed[_]`, components chain with no adapters. The store's
samplers: `IngressSampler` (ApiRequest→StoreRequest), `AdmissionSampler` (the load gate),
`StoreSampler` (the datastore cost model), `EgressSampler` (StoreResponse→ApiResponse).

### 4.3 The pipeline graph

`StoreTrialRunner.run` wires one trial. The forward path forks at admission and rejoins before egress:

```
 ApiWorkload  Timed[ApiRequest]
      │  (TickFraming: Tick(1)…Tick(N), EndOfTime)
      ▼
   Ingress ───────────────────────────── obs: ingress.latency ┐
      │ Timed[StoreRequest]                                    │
      ▼                                                        │
  Admission ─────────────────── obs: admission.latency, throttled ┤
      │ Timed[AdmissionOutcome]                                │
  Broadcast(2)                                                 │
    ├─ Admitted → Timed[StoreRequest] ─► Datastore ── obs: latency, work.*, returned.* ┤
    │                                       │ Timed[StoreResponse]                     │
    └─ Throttled → Timed[ErrorResult] ─┐    │                                          │
                                       ▼    ▼                                          │
                          MergeTimedEventGraph  (tick-aligned: one Tick/window,        │
                                       │         single EndOfTime)                     │
                                       ▼                                               │
                                   Egress ─────────────── obs: egress.latency ─────────┤
                                       │ Timed[ApiResponse]                            │
                                       ▼                              all obs streams ──┘
                                   Sink.seq[ApiResponse]                     │
                                                              Merge(4) → Sink.fold
                                                                      │
                                                          Statistics[StoreStatKey]
```

Key design points:

- **The fork** uses `Broadcast(2)` (not `Partition`) so **both** branches carry every control event
  (`Tick`/`EndOfTime`); each branch `collect`s only its payload kind (admitted requests vs. throttled
  errors). Two independently well-framed streams result.
- **The rejoin** uses `MergeTimedEventGraph` — a *tick-aligned* merge that emits exactly one `Tick` per
  window and a single terminal `EndOfTime`, which a plain `Merge` cannot (it would double control events
  and complete early). This is what lets a throttle re-enter the response stream cleanly and surface as
  `ApiError("throttled")` via the existing `EgressSampler` — **no new response type, 1:1 preserved**.
- **Throttles skip the datastore** but still pay ingress + admission + egress latency, mirroring a real
  fast-rejected 429.

### 4.4 Statistics collection

Each transducer's consumption outlet is a `Timed[Cons]` stream. Per-stage **normalizing flows** convert
those into a common currency `(StoreStatKey, Double)` and a single `Merge(4)` feeds one `Sink.fold`:

```scala
StoreStatKey(usecase, metric, window)   // usecase from the Timed envelope; window from eventTime
```

- `usecase` is the workload stream's label, propagated on every derived event's envelope — this is why
  `list.keyset` and `list.offset` (identical shape, different label) land in different keys.
- `metric` comes from `StoreStats.observations` / `admissionObservations` (`latency`, `work.items`,
  `work.bytes`, `returned.*`, `ingress.latency`, `egress.latency`, `admission.latency`, `throttled`).
- `window = (eventTime.ticks - 1) / windowTicks` — the coarse time bucket that makes the cardinality
  rise observable. The default `windowTicks = Long.MaxValue` collapses everything to window 0, so
  non-capstone callers and tests are unaffected.

The fold target, `Statistics[StoreStatKey]`, is a keyed map of `Statistic` — additive moments
(count/sum/sumSq → mean/stddev) plus a **mergeable log-bucket `Histogram`** for quantiles. The histogram's
`combine` is exactly associative, which is the property that makes cross-tick and cross-trial
aggregation a simple fold.

### 4.5 Monte Carlo and final summation

`StoreMonteCarloRunner.run` layers the ensemble on top:

- `SeedSequence.derive(masterSeed, N)` fans the master seed into N prefix-stable per-trial seeds.
- `MonteCarlo.run(trialCount, masterSeed, parallelism)(seed => Future[R])` executes trials with bounded
  parallelism via **order-preserving `mapAsync`** — so results are byte-identical for any parallelism,
  given seed-deterministic trials.
- Each trial is projected to its `Statistics` immediately (`StoreTrialRunner.run(...).map(_.stats)`), so
  the ensemble never retains full trial results.

`StoreMonteCarloResult(trialCount, perTrial: Vector[Statistics[StoreStatKey]])` provides the two
aggregations (Slice 7):

```scala
def pooled: Statistics[StoreStatKey]                                   // (a) merge all trials' observations (associative combine)
def acrossTrials(key, scalar: Statistic => Double): Statistic          // (b) N per-trial scalars → a fresh Statistic (its mean/stddev/p50/p99 = the run-to-run distribution)
```

`StoreReport` renders both to JSONL and computes the text summary (finding the report-latency window
trend, the offset-vs-keyset ratio, and the per-use-case throttle rates from the pooled statistics).
`StoreDemo` is the `@main` bridge that ties it together.

The division of labor is deliberate: **`core` stays domain-agnostic** — it supplies the sampler
contract, the transducer, the mergeable statistics, the seed utility, and the Monte Carlo executor. The
store example supplies everything problem-specific: the protocol, the cost model, the workload, the
`StoreStatKey`, the reduce-to-scalar step, and the report format. `core` imposes no observation
semantics.

---

## Source map

| concern | file |
|---|---|
| API & datastore protocols | `ApiProtocol.scala`, `StoreProtocol.scala` |
| workload (labeled streams) | `ApiWorkload.scala` |
| service stages | `IngressSampler.scala`, `EgressSampler.scala`, `ServiceModel.scala` |
| admission gate | `AdmissionSampler.scala`, `AdmissionModel.scala` |
| datastore cost model | `StoreSampler.scala`, `StoreConfig.scala` |
| observation vocabulary | `StoreStats.scala` (`StoreStatKey`, metric extractors) |
| single-trial pipeline | `StoreTrialRunner.scala` |
| Monte Carlo | `StoreMonteCarloRunner.scala`, `StoreMonteCarloResult.scala` |
| export & bridge | `StoreReport.scala`, `StoreDemo.scala` |
| verification | `test/.../store/StoreCapstoneSpec.scala` (+ per-component specs) |
| engine (core) | `core/component/*`, `core/stats/*`, `core/run/*`, `core/stream/*` |
