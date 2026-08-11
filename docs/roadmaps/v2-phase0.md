# v2 Phase 0 — Core Redefinition + Store Simulator

## Goal

v2 Phase 0 has two intertwined deliverables, developed together so each keeps the other honest:

1. **A redefined `stochastacy.core`** — a reusable set of traits and classes for simulating
   *any* distributed enterprise software system, not just AWS. These are the load-bearing
   abstractions: timed events and timed-event streams (kept largely as-is), the **component**
   abstraction (currently under-defined), the stochastic **sampler** shape shared by every
   component, the machinery for connecting components into graphs, running simulations, and
   collecting their results.

2. **A "store simulator" example application** — a small but non-trivial system built entirely
   against the new `core`, living in the `examples` module. It models a RESTful API in front of
   a backing datastore. Its purpose is to be the **forcing function** for the core design: every
   abstraction that lands in `core` earns its place because building the store simulator required
   it. Nothing is designed in a vacuum.

The domain of the example is deliberately unimportant. What matters is that it exhibits the
behaviors a general simulator must reproduce: stateful components whose cost changes over time,
and non-linear effects (deep-pagination cost cliffs, queueing/throttling) that only emerge from
fine-grained, per-interaction simulation.

### What this phase is NOT

- It is **not** the AWS-code migration. The existing `ips` DynamoDB simulator stays where it is
  for now. Phase 0 builds `core` greenfield and proves it with the store simulator. Relocating
  AWS-specific code behind the new `core` boundary (into its own module) is a later phase.
- It is **not** a rewrite of the workload/`Sampler[S, T]` layer, which is already good. That layer
  is *relocated* into `core` (stripped of AWS imports) rather than redesigned.

---

## Why v2 — lessons learned from `ips`

The `ips` line (Phases 1–8) built a complete, working DynamoDB Monte Carlo simulator. Building it
taught us what the reusable core actually is. The v2 redefinition captures those lessons:

1. **"Distributed system component" was never a first-class concept.** Each AWS resource was a
   hand-wired Pekko graph (`GraphDSL.create()` + `Broadcast`/`Merge` + custom `FanOutShapeN`).
   The composable unit of architecture existed only implicitly. v2 makes **Component** an explicit
   abstraction.

2. **Every stage re-implemented the same protocol plumbing.** `statefulMapConcat` closures that
   hand-handle `Tick`/`EndOfTime` propagation, response timing, and per-outlet fan-out appear all
   over the DynamoDB stages. This is fiddly, protocol-critical, and identical everywhere. Getting
   it wrong silently corrupts every downstream statistic. v2 extracts it once, correctly, as a
   reusable **schedule-and-release transducer**.

3. **The stochastic-summary principle applies to *state*, not to the *wire*.** The `ips` model's
   real invariant was "bounded summary state, no per-key maps" — memory near-constant w.r.t. key
   space. Event *throughput* was always O(volume). v2 makes this distinction explicit and keeps
   inter-component interactions **fine-grained** (see the interaction-plane decision below).

4. **Usage/pricing/Monte-Carlo aggregation is domain-agnostic in shape but was written
   AWS-specifically.** `DynamoDbUsageTotals`, the windowed exporters, the multi-trial aggregator —
   their *shape* (fold consumption facts into per-metric, per-window statistics; aggregate across
   trials) generalizes. v2 lifts that shape into `core` as the **observation plane**.

5. **`usecase: Any` is under-typed for the role it actually plays.** In `ips` it is a coarse
   stream-demux tag. In practice, the "use-case"/intent is *the dispatch key for a component's
   behavior* — it selects which production function the sampler applies. v2 promotes it.

---

## Core design decisions

These were worked out in the v2/phase0 design conversation and are the conceptual backbone of
`core`.

### The three planes

Every simulation is organized into three planes, and knowing which plane a value lives on decides
how it is represented:

1. **Description plane (stochastic, declarative).** The workload and each component's internal
   behavior — `Sampler`, rates, distributions, hit/miss probabilities. "Stochastic" is a
   first-class, composable object here.

2. **Interaction plane (fine-grained, materialized).** The edges between components carry
   individual `TimedElement[E]` — requests, responses, downstream calls. This is where non-linear
   behavior *emerges*: throttling, queue depth, retry storms, deep-pagination cost are all
   functions of the actual ordered event process, not of its marginals. **Interactions are
   fine-grained, never on-wire summaries.**

3. **Observation plane (stochastic, summarized).** Consumption/metric facts, aggregated into
   statistics — mean, stddev, p99 — across ticks and across trials.

The organizing law: **description is stochastic *before* materialization; observation is
stochastic *after* materialization; the interaction plane in between is fine-grained so the
non-linearity survives the trip.**

### Interactions are fine-grained (the retired "stochastic wire" idea)

An earlier design instinct was to make inter-component *interactions* stochastic summaries. This
is retired. The corrected statement: interactions **are** stochastic — but "stochastic" means
*materialized draws from a stochastic process*, not *summaries carried on the wire*. You cannot
recover a p99 from a mean, or a throttle from an average rate. A stochastic workload *description*
must be **materialized into an actual stream of fine-grained events** to characterize a system's
behavior statistically.

Consequence: the redesign **shrinks**. No monoidal interaction-summary algebra on the data plane.
The composition invariant is simply "respect the timed-event protocol" (tick ordering +
`EndOfTime` propagation), which already exists. The genuinely hard problem *moves* to the
observation plane (see below).

If profiling ever shows a provably-linear subgraph dominating cost, that stretch may be collapsed
with a local summary as a **targeted optimization** — never as the architecture.

### State vs. flow

Bounded summary state and fine-grained flow are orthogonal and both hold:

- **State** is a bounded stochastic summary — no per-key maps; memory near-constant w.r.t. key
  space and request volume.
- **Flow** is O(volume) — every interaction is a materialized event.

Fine-grained flow does not violate the constant-work principle, because that principle is about
state size, not event count.

### The universal component shape

A **component** is a stateful, tick-driven machine that consumes input events and produces, per
output plane, zero-or-more **time-delayed** output events, plus updated state. This single shape
covers every component:

- a **datastore** consumes requests, emits responses + consumption facts;
- a **service** additionally *materializes downstream requests* (a cache miss is a scheduled
  downstream `Get`);
- a **workload source** is the degenerate case: no input, emits on `Tick`.

The domain-specific behavior of a component is expressed as a **sampler**. The sampler is the
production function; the surrounding machinery (the transducer) is generic.

### Use-case = intent = the sampler's dispatch key

Each request carries a **use-case** (a.k.a. intent, purpose): one of a finite set of purposes the
component was designed to answer. The use-case selects which production function the sampler
applies. Use-case is *finer* than operation type — two `Get`s can be different use-cases with
different profiles — and operation type falls out of it. This promotes the existing `usecase`
field from an `Any` demux tag to a typed, first-class dispatch key.

### The sampler contract

Domain payloads are **timeless**: the sampler describes *what happened*; *when it is observed* is
the machinery's job (the existing intra-tick `rawOffset` floor/fraction math). The sampler emits
**delays** (fractional ticks), never absolute time.

```scala
type Delay = Double   // fractional ticks; machinery computes eventTime = req.eventTime + floor(intraTick + delay)

final case class Scheduled[+E](event: E, delay: Delay)

final case class Emission[S, Resp, Cons](
  newState:    S,
  response:    Scheduled[Resp],        // exactly one — success or error variant
  consumption: List[Scheduled[Cons]]   // zero or more
)

trait RequestResponseSampler[S, Req, Resp, Cons]:
  def initialState: S
  def sample(req: Req, state: S, rng: UniformRandomProvider): Emission[S, Resp, Cons]
```

- **Errors are response variants**, not a separate `Either` — "exactly one response, which may be
  an error." Enforced by `Scheduled[Resp]` being singular.
- `RequestResponseSampler` is a **specialization**; the general `core` base is "zero-or-more
  scheduled events per outlet." A source, a fan-out router, and a sink are not request/response.
- This trait is intentionally distinct from the workload `Sampler[S, T]`; they serve different
  pipeline stages with different inputs (this was already true in `ips`).

### The schedule-and-release transducer (the crown jewel)

Time-delayed outputs break the naive `statefulMapConcat` model: a response scheduled 3 ticks out
cannot be emitted now without violating tick ordering. So the universal machinery is a
**schedule-and-release loop** over an internal pending buffer (priority queue keyed by
`(eventTime, intraTick)`):

1. **On an input event:** run the sampler, update state, *insert* each scheduled output into the
   pending buffer at its absolute time. Emit nothing yet.
2. **On `Tick(T)`:** drain and emit every pending output due by `T`, in `(eventTime, intraTick)`
   order, then pass `Tick(T)` through.
3. **On `EndOfTime`:** flush in-horizon pending → emit residue + final state on a side outlet →
   propagate `EndOfTime`.

Because step 3 must side-emit on a *different* outlet, this is a real multi-outlet `GraphStage`,
**not** a `statefulMapConcat`. This is the single most reusable behavioral construct in `core`:
the sampler stays purely domain (state + latency distributions); the reorder buffer, tick-boundary
release, intra-tick sub-ordering, and `EndOfTime` flush are owned once, here.

### Materialized value = `TrialResult` (streams carry dynamics, Mat carries results)

The `RunnableGraph`'s materialized value stops being `Done` and becomes a
`Future[TrialResult]`. The stream is run for its *dynamics*; everything you want to *read* comes
out as the materialized value:

- folded observation-plane sketches (per-use-case latency/throughput, p99);
- final component state(s) — a keyed, heterogeneous `Map[ComponentId, ?]`;
- **post-horizon residue** — events scheduled after `EndOfTime`, carried out-of-band because they
  are out-of-band by construction. Default representation is a **diagnostic summary** (count,
  total, by-type) that flags "your horizon may be truncating N events," with full-event capture
  opt-in;
- simulated duration / run metadata.

This makes the multi-trial layer fall out: the executor collects `N` independent
`Future[TrialResult]` and merges their sketches across trials. `Done` is the degenerate empty
`TrialResult`.

Note two mechanical wrinkles this creates: (a) combining Mat across many sinks is Pekko boilerplate
(`GraphDSL.create(sinkA, sinkB, …)(combine)` / `Keep`), which becomes a concrete job for the
composition layer — a `collectResults` combinator; (b) "final state" is plural and heterogeneous,
a genuine typing tension to resolve.

### p99 is not additive — the one genuinely hard problem, on the observation plane

Mean and stddev merge trivially (additive moments / Welford). **Quantiles do not** — you cannot
merge two p99s. Since results aggregate across ticks *and* across trials, the observation plane
needs a **mergeable statistical sketch** (t-digest or HDR histogram) per metric per window, which
you `combine` and then query for p50/p99/p999. This is the surviving form of the "sufficient
statistic" question: it moved off the interaction plane (answer: keep everything) onto the
observation plane (answer: a mergeable sketch).

### On a `Component` base trait

A bare `type Component = Graph[Shape, NotUsed]` alias buys nothing over Pekko. A `Component`
abstraction is worth having **only if it carries structure Pekko doesn't**: role-typed ports that
separate the observation outlet from data ports, and a composition operator that *auto-collects*
observations. Whether that trait earns its place is decided by the observation-plane design — so we
defer committing to it and start with a **port-bundle convention + `connect`/`collectResults`
helpers**, promoting to a trait only once the observation plane forces its shape.

---

## The store simulator

### Two protocols at two edges

- **API protocol** (client → service): entity management, listing, report projections.
- **Datastore protocol** (service → datastore): the CRUD / ordered-retrieval / aggregation
  use-cases.

The **service** is the transducer between them — the first real multi-component graph. First slice
maps them **1:1** (each API call issues one datastore call) with a near-identity service; fan-out
(a list endpoint issuing `count` + `page`) and a service-side concurrency bottleneck come later.

### Operation types vs. parameters — the taxonomy rule

> Mint a new operation **type** only when the datastore does something structurally different — a
> different code path / cost model. Everything that is a knob within one cost model is a
> **parameter**.

So filter/sort/page-size are parameters, not types; even offset-vs-keyset pagination is a
structured field. The combinatorial variety lives in the **workload's sampling distribution over
parameters**, not in a type explosion.

### The three cost signatures (why three families)

| Family | Evaluated (work) | Returned (result) | Behavior it exercises |
|--------|------------------|-------------------|-----------------------|
| **Point (CRUD)** | O(1) | O(1) | size-driven cost; hit/miss |
| **List** | wildly mode-dependent | bounded by page size | index vs sort; **deep-offset non-linearity** |
| **Report** | ≈ entire matched set | small (group count) | expensive-to-compute, cheap-to-return |

### Datastore protocol (as designed)

```scala
// Bounded summary state — no per-key maps.
final case class StoreState(entityCount: Long, totalBytes: Long):
  def meanBytes: Long = if entityCount == 0 then 0 else totalBytes / entityCount

// Query intent = use-case. Each carries its own selectivity LAW, realized against state.
enum SelectivityClass:
  case PointLookup    // ~constant COUNT, independent of N
  case CategoryFilter // ~constant FRACTION of N
  case FullScan       // ≈ all of N

enum SortMode   { case Unordered, IndexOrdered, RequiresSort }
enum Pagination { case Offset(pageIndex: Int, pageSize: Int); case Keyset(pageSize: Int) }

// Requests — these ARE TimedEvents (they arrive on the wire)
sealed trait StoreRequest extends TimedEvent
final case class Get(...) ; final case class Put(sizeBytes: Long, ...) ; final case class Delete(...)
final case class ListQuery(sel: SelectivityClass, sort: SortMode, page: Pagination, ...)
final case class ReportQuery(sel: SelectivityClass, groupCount: Int, sort: SortMode, page: Pagination, ...)

// Response + consumption payloads — TIMELESS (machinery stamps timing from delay)
sealed trait StoreResponse
// GetResult(hit, bytes) | WriteResult(created) | DeleteResult(deleted)
// QueryResult(returnedItems, returnedBytes, evaluatedItems, evaluatedBytes) | ErrorResult(kind)
sealed trait Consumption
// WorkPerformed(items, bytes) | DataReturned(items, bytes) | StorageDelta(bytesDelta)
```

Cost-model highlights (the non-linear behavior the example exists to show):

- `SelectivityClass.matched(state, rng)` realizes intent against **current** cardinality:
  `PointLookup` ≈ constant count (independent of N — a raw fraction cannot express this),
  `CategoryFilter` ≈ constant fraction of N, `FullScan` ≈ N.
- List `evaluated`: `IndexOrdered + Keyset` ≈ page size (flat); `IndexOrdered + Offset(pi, ps)` ≈
  `(pi+1)·ps` (the deep-pagination cliff); `RequiresSort`/`Unordered` ≈ matched.
- Report `evaluated` ≈ matched (aggregation must see the whole set); returned ≈ group count.
- `Put` is a unified upsert with a **create-vs-update draw**; create bumps `entityCount`.

Two emergent, time-varying behaviors validate the architecture: (1) as `Put`s accumulate,
cardinality grows, so list/report cost *rises across the run* at constant request rate; (2)
deep-offset pagination cost climbs with page depth. Neither is visible in a mean.

### Decisions settled

- **Selectivity carried as a use-case/class**, realized in the datastore — not a raw fraction.
  Protocol stays stable while the realization law is enriched later.
- **`Put` = unified upsert** with a stochastic create-vs-update draw.
- **Errors are response variants** produced via a small stochastic error rate.

### Open decisions

- **Consumption timing:** account `WorkPerformed`/`DataReturned` at arrival (`delay = 0`) or at
  completion (`delay = latency`)? Leaning *completion* for windowed-p99 fidelity.
- **Throttling location:** `StoreSampler` is stateless-per-request and cannot see concurrent load,
  so throttling belongs in a separate **admission component** upstream (mirrors `ips`'s
  `TableAdmissionStage` / `TableStorageStage` split). Confirms the datastore is ≥2 components.
- **Heterogeneous final state** typing in `TrialResult`.
- Whether the `Component` trait materializes, or convention + helpers suffice (see above).

---

## Path A plan (example-driven)

Each step names the reusable construct it forces into `core`.

| Step | Build (store simulator) | Forces into `core` |
|------|-------------------------|--------------------|
| 0 | REST + store request event types | Relocate `Sampler`/distributions/combinators/temporal fns, AWS-free |
| 1 | Workload source emits requests | Tick-framing combinator (+ inverse); convenience stream constructors (`empty`, `of`, `fromIterator`, `fromLazyList`) |
| 2 | `StoreSampler` + stateful transform | **Schedule-and-release transducer** (`GraphStage`); `Emission`/`Scheduled`/`Delay`; `RequestResponseSampler` |
| 3 | Emit consumption facts | Generic observation fact type |
| 4 | Wire source → service → datastore | Port-bundle convention + `connect`/`collectResults` |
| 5 | Run one trial, collect | Single-trial runner; observation sink + mergeable sketches (moments + t-digest/HDR) |
| 6 | Monte Carlo | Multi-trial executor + cross-trial sketch aggregator; RNG-seeding utility |

Suggested first slice: Steps 0–2 for a single endpoint with no datastore — relocate `Sampler`,
extract the framing combinator, and build + validate the schedule-and-release transducer by
materializing one endpoint's request stream through one stateful transform. This lands the crown
jewel and validates it before any wiring or observation questions arrive.

---

## Slice Plan

The Path A steps above are the conceptual construct-forcing map. The slices below are the concrete
delivery increments — each independently testable, each carrying its own milestone. Module tag:
`(core)` = reusable abstraction, `(examples)` = store-simulator domain code.

### Status

| Slice | Status | Milestone |
|-------|--------|-----------|
| 1. Core machinery foundation | **Done** | Toy sampler runs through the transducer with correct tick-ordered release + `EndOfTime` flush |
| 2. Store domain + `StoreSampler` | **Done** | `StoreSampler` unit-tested across all three families; no graph |
| 3. First runnable simulation | Planned | source → datastore runs end-to-end; `Future[TrialResult]` completes |
| 4. Observation plane | Planned | `TrialResult` carries real per-use-case p50/p99; sketch `combine` associative |
| 5. Multi-component composition | Planned | 3-component graph runs; observations merge across components; `Component`-trait decision made |
| 6. Admission / throttling | Planned | throttle rate + p99 respond to offered load, not mean rate |
| 7. Monte Carlo | Planned | N-trial aggregate statistics stable; deterministic under a fixed master seed |
| 8. Workload, emergent behavior, reporting | Planned | both emergent behaviors + throttling visibly exhibited; results exported |

### 1. Core machinery foundation — *(core)*

Relocate `Sampler`/distributions/combinators/temporal fns into `stochastacy.core` (AWS-stripped);
stream constructors (`empty`, `of`, `fromIterator`, `fromLazyList`) + the tick-framing combinator
and its inverse; the sampler contract (`Emission`/`Scheduled`/`Delay`/`RequestResponseSampler`);
the schedule-and-release **transducer** (`GraphStage`). The contract lands here because the
transducer is defined in terms of it.

**Validated by:** a toy sampler driven through the transducer — asserts tick-ordered release,
correct intra-tick stamping, and `EndOfTime` flush.

**Delivered** (465 tests green, +9 new):
- `stochastacy.core.sampler` — the six relocated sampler files (package-move only, zero logic
  change); a `stochastacy/workload/samplerExports.scala` top-level `export` shim keeps all `ips`
  consumers compiling untouched (D1a). To be deleted when `ips` is ported.
- `stochastacy.core.stream` — `TimedStream` (`empty`/`of`/`fromIterator`/`fromLazyList`) and
  `TickFraming` (`frame`/`frameSource`/`unframe`), extracted from `WorkloadRequestStream`.
- `stochastacy.core.component` — `Delay`/`Scheduled`/`Emission`/`RequestResponseSampler`
  (`SamplerContract.scala`), the `Timed[E]` output envelope (D4), and `ScheduleReleaseTransducer`
  (D5: `statefulMapConcat` scheduling core + `Broadcast`-to-two-outlets, with an internal
  plane-tag ADT because `Timed[Resp]`/`Timed[Cons]` are erasure-identical).
- Decisions realized as planned: D1a (export shim), D2 (three sub-packages), D3 (`stochastacy.sim`
  left in place), D4 (`Timed[E]` envelope), D5 (`statefulMapConcat`+`Broadcast`; promotion to a
  `GraphStageWithMaterializedValue` deferred to Slice 3 when residue/state route to `TrialResult`).

### 2. Store domain + `StoreSampler` — *(examples)*

The store protocol types (`StoreRequest` families, `StoreState`, `StoreResponse`, `Consumption`,
`SelectivityClass`/`SortMode`/`Pagination`) and `StoreSampler` implementing all three families with
the cost model (selectivity realization, deep-pagination `evaluated`, upsert create/update draw,
stochastic error branch).

**Validated by:** pure unit tests — feed `(request, state)`, assert on the `Emission`. No graph.
Proves the contract is expressive enough for real domain behavior.

**Delivered** (188 examples tests green, +13 new; **zero `core` changes** — the contract held):
- `stochastacy.examples.store` — `StoreProtocol.scala` (requests, `SelectivityClass`/`SortMode`/
  `Pagination`, timeless `StoreResponse`/`Consumption`, `StoreState`), `StoreConfig.scala`,
  `StoreSampler.scala` (`RequestResponseSampler` over `StoreState`).
- Decisions realized: D-A (`usecase: Any` label + typed `sel` field), D-B (consumption at
  completion, `delay = latency`), D-C (latency deterministic from work), D-D (`SelectivityClass`
  pure; realization in `StoreSampler`), D-E (`stochastacy.examples.store`).
- Tests demonstrate the emergent behaviors at the sampler level: deep-offset `evaluated` cliff,
  `PointLookup` constant-count vs `CategoryFilter` constant-fraction, report evaluate-all/return-few,
  and cardinality growth under threaded `Put`s.

### 3. First runnable simulation — *(core run machinery + examples wiring)*

The datastore **Component** (transducer ∘ `StoreSampler`); a minimal workload source emitting
`StoreRequest`s via the relocated `Sampler` + framing; a single-trial **runner**. Introduce
`TrialResult` as the materialized value in skeleton form: final `StoreState`, duration, post-horizon
residue summary.

**Validated by:** source → datastore runs end-to-end; `Future[TrialResult]` completes with sane
final state and a protocol-respecting stream.

### 4. Observation plane — *(core)*

The generic observation fact type; the fold-into-sketches sink — additive moments (mean/stddev)
**and** a mergeable quantile sketch (t-digest/HDR) — folding `Consumption` into per-use-case
latency/throughput/p99. `TrialResult` now carries real statistics.

**Validated by:** a known input distribution yields expected p50/p99 within tolerance; sketch
`combine` is associative.

### 5. Multi-component composition — *(core composition + examples)*

The REST API protocol + the **service** component transducing API calls → datastore calls (1:1).
The port-bundle convention + `connect`/`collectResults` composition helper (fusing Mat across
components). Graph becomes source → service → datastore, observations aggregating from both.

**Checkpoint:** decide `Component` trait vs. convention-plus-helpers now that the observation
plane's shape is known.

**Validated by:** 3-component graph runs; `TrialResult` merges service- and datastore-plane
observations.

### 6. Admission / throttling — *(core pattern + examples)*

A load-aware **admission** component upstream of the datastore, tracking in-flight/concurrency to
emit throttle responses under load — resolving the throttling open decision and confirming the
datastore is ≥2 components. Where the queueing non-linearity becomes real.

**Validated by:** under a burst the admission component throttles; p99 latency and throttle rate
respond to offered load, not just mean rate.

### 7. Monte Carlo — *(core)*

The multi-trial executor (N independent seeds, bounded parallelism) + cross-trial sketch
aggregation (p50/p99/stddev *across* trials + whole-run summaries) + the RNG-seeding utility.

**Validated by:** N trials produce stable aggregate statistics with sensible cross-trial variance;
determinism under a fixed master seed.

### 8. Workload, emergent behavior, reporting — *(examples + light core export)*

A real `WorkloadDefinition` exercising selectivity classes, pagination modes, and sustained writes;
verify the two emergent behaviors (cardinality-driven cost rise over the run; deep-offset cost
cliff) plus throttling under load; export `TrialResult` statistics to JSONL / a summary report.
Declares the store simulator complete.

**Validated by:** the simulation *visibly* exhibits both emergent behaviors and throttling;
exported output is inspectable. (Full Grafana pipeline is optional/deferred — JSONL + summary
report is enough to call phase 0 done.)

### Ordering notes

- Strict dependencies: 1 → 2 → 3 → 4; slice 5 depends on 3–4; slice 8 depends on everything.
- **Slices 6 and 7 are reorderable.** Admission (6) is placed before Monte Carlo (7) so we scale a
  complete-fidelity single-trial system rather than adding a core component after the MC layer
  exists; swap them to exercise cross-trial sketch-merging earlier.
- `TrialResult` is introduced in slice 3 and enriched in 4 (statistics) and 7 (cross-trial) — expect
  it to grow, not to be final at 3.
- The `Component`-trait decision is parked until slice 5's checkpoint.

---

## Deferred work (carried forward)

Consolidated tracker of things intentionally left incomplete, so they are not forgotten. Each
names the slice/phase where it is meant to land.

**Targeted at a specific later slice:**

- **Transducer → `GraphStageWithMaterializedValue`** *(Slice 3)*. The Slice-1 transducer is a
  `statefulMapConcat` + `Broadcast`; promote it so residue/final-state can route to the
  materialized `TrialResult`. Scheduling logic carries over unchanged.
- **Post-horizon residue → `TrialResult` diagnostic** *(Slice 3)*. Slice 1 flushes all pending
  onto the streams at `EndOfTime`; replace with a residue *summary* (count/total/by-type) on the
  materialized value as a "horizon may be truncating N events" signal.
- **Latency distributional jitter** *(post–Slice 2 refinement)*. Slice-2 latency is a deterministic
  function of modeled work; add distributional jitter later if needed (load-induced variance is the
  admission component's job, Slice 6).
- **State-dependent selectivity laws** *(post–Slice 2 refinement)*. Slice-2 realization is
  fixed-fraction / fixed-count / full; richer laws (e.g. a `RecentWindow` class whose selectivity
  drifts with state) can be added without touching the protocol.
- **Request/response chaining uniformity** *(Slice 5)*. Decide whether requests also become
  `Timed[_]` payloads (like outputs) for cross-component uniformity, or stay self-timed `TimedEvent`s.
- **`Component` trait vs. convention + helpers** *(Slice 5 checkpoint)*. Promote only if the
  observation plane's shape justifies it.

**Targeted at the later AWS-extraction phase (post–Phase 0):**

- **Fully type `TimedEvent.usecase`.** Promote from the `Any` demux tag to a typed, first-class
  intent/dispatch key. Slice 2 works around it by carrying `SelectivityClass` as a typed field on
  query requests and leaving `usecase: Any` as a label.
- **Move timed-event types into `stochastacy.core`** (`TimedEvent`, `TimedControlEvent`,
  `TimedElement`, `SimTime`, stream combinators) and **retire `stochastacy.sim`** (see *Interim vs.
  target state* below).
- **Delete the `stochastacy.workload` sampler export shim** (`samplerExports.scala`) once `ips`
  consumers import from `stochastacy.core.sampler` directly.
- **Relocate AWS `ips` code out of `core`** into its own module behind the new `core` boundary.
- **Port or delete the legacy `ips` demos** (order-tracking, thermostat-fleet).

---

## Module organization target

- `core/` (`stochastacy.core` and sub-packages) — **abstractions only.** Timed events + streams,
  the component/sampler shapes, the transducer, stream constructors/combinators, the workload
  `Sampler` layer, the observation plane, run + Monte-Carlo machinery. No AWS, no domain code.
- `examples/` — the **store simulator** application (store-specific requests, `StoreSampler`,
  service, wiring, runner config).
- (Later phase, out of scope for Phase 0) AWS-specific `ips` code relocates out of `core` into its
  own module behind the new `core` boundary.

### Interim vs. target state (the `stochastacy.sim` question)

The target above has **all** timed-event types — `TimedEvent`, `TimedControlEvent`, `TimedElement`,
`SimTime`, and the timed-stream combinators — living in `stochastacy.core`. The package
`stochastacy.sim` is **not** part of the intended long-term core; it survives only as a transitional
measure.

- **Interim (through Phase 0):** `stochastacy.sim` keeps the timed-event types in place, and
  `stochastacy.core` imports from it. This avoids a large ripple (~77 files reference
  `stochastacy.sim`, including the opaque `SimTime`) that would buy nothing during the early slices.
  During this window, `stochastacy.sim` + `stochastacy.core` together constitute the reusable core.
- **Eventual (later phase):** the timed-event types **move into `stochastacy.core`** and
  `stochastacy.sim` is retired (relocated, then its shim deleted). This happens alongside relocating
  the AWS `ips` code out of `core` into its own module.
- **Legacy examples:** the `ips`-era demos (order-tracking, thermostat-fleet) are **ported to the
  new `core` API or deleted** as part of that same later phase. Phase 0 does not touch them.

So D3 in the Slice 1 plan (keeping `stochastacy.sim` where it is) is a deliberate deferral of
timing, not a design endorsement of `stochastacy.sim` as a permanent second root.
