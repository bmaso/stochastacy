# v2/phase13 — Closed-loop circuits

**Status: IN PROGRESS** (roadmap approved 2026-09-15; Slices 1–3 done). Gives `stochastacy.core` **closed feedback
loops by composition** — including loops that close *inside* a tick — proven by the **MM1 demo** (an M/M/1 queue
with Bernoulli feedback, checked against its closed-form solution). **Immediately after this phase, work resumes on
`tailgate`** (the throttle-comparison simulator for Brian's article, on hold until this lands), so the close-out
republishes the core for it.

Design exploration: `docs/roadmaps/v2-phase13-design.md` (the problem, Concept F1 "registers", Concept K "circuits").

## Why this phase exists

Tailgate models `client → throttle → bounded queue → workers → response → client`, where a rejection or timeout
triggers a retry after a 50 ms / `uniform(0, 50 ms)` backoff. Today's core cannot compose that loop: `onFeedback`
cannot emit a request, fed-back items aren't time-ordered against a window's primary inputs, and a cycle of
tick-barrier transducers deadlocks unless something breaks it. The only workaround — one monolithic event-loop
sampler holding the whole system — was rejected as heroic: tailgate exists to show that stochastacy models loops.
Backlog items **C3** (no time-ordered request feedback) and **C1** (samplers can't see input time) are resolved
here.

## Goal

A **circuit**: a single transducer stage hosting several sampler *nodes* and a wiring among them — **cycles
allowed** — run by an internal discrete-event **calendar** ordered by conceptual time. Outside, a circuit is an
ordinary component (`FanOutShape2`: in → forward out, consumption out), so `Interface.wrap`, `TrialRunner`,
`MonteCarlo`, and accounting sinks work unchanged. Inside, an output routed along an internal edge re-enters the
calendar at `trigger + delay` for **any** delay, including zero, so a loop closes exactly within a tick and ticks
stay coarse. The Pekko graph remains the composition mechanism for pipelines and ≥ 1-tick coupling; circuits are
for tight coupling.

## Design decisions

Confirmed:

- **D-circuit (Concept K).** Loops close inside a circuit's calendar, not across Pekko stages. Chosen over F1
  (≥ 1-tick "registers" between stages), whose artificial one-tick hop would force ~1 ms ticks on tailgate (see the
  design note).
- **D-demo-mm1.** The proof is the **MM1 demo**: M/M/1 with Bernoulli feedback, framed as a paginated client
  against a single FIFO server. Exact closed forms make the pass criterion "Monte Carlo estimate within its
  confidence interval of theory", not a captured baseline.
- **D-no-consumption-feedback.** Loops are driven by forward outputs and taps. Feedback driven by *consumption
  metrics* is out of scope.

Proposed (confirm with the roadmap):

- **D-input-time (resolves C1).** `sample` and `onFeedback` receive the input's **conceptual time** as a new
  parameter (a small `SimInstant(tick: Long, intraTick: Double)` value) — a clean single-contract change rather
  than an additive overload. Migration is mechanical: 15 `sample` implementations — 9 production (4 core gates,
  `DynamoDbTable`, 4 store samplers) and 6 test toys — plus one production `onFeedback` (`DynamoDbTable`).
- **D-feedback-emission.** `onFeedback` returns `FeedbackEmission(newState, output: Option[Scheduled[Out]],
  consumption, taps)`: `None` when a fed-back item answers nothing (phase-11 replicated writes), `Some(request)`
  when it triggers one (a retry, a next page). `sample` stays strictly 1:1. The Pekko loopback stage supports the
  new output (released in time order like any output); its documented limitation — feedback applied in
  wire-arrival order within a window — is unchanged, and circuits are the exact alternative.
- **D-circuit-shape.** One external inlet, one forward outlet, one consumption outlet (multi-inlet deferred).
  Materialized value: `Future[CircuitResult]`, with typed per-node final state via node handles.
- **D-wiring.** Typed builder over erased dispatch (the `ErasedSampler` precedent). An edge connects a node's
  output plane (`out` or `taps`) to a node's port (`in` → `sample`, `fb` → `onFeedback`), with an optional
  partial-function transform that also filters (e.g. `{ case Admit(r) => r }`). Edges add no delay — the emitter's
  `Scheduled` delay is the latency. An output feeds every matching edge in declaration order. External edges route
  the circuit input to a node port and a node plane to the forward outlet; per-node consumption maps into the
  circuit's consumption type.
- **D-calendar.** Events keyed `(tick, intraTick, seq)` — `seq` is global emission order, making ties
  deterministic. Mirroring the transducer's per-tick order: when `Tick(k)` opens a window, every node's
  `onTick(k)` runs (declaration order) before any window-`k` event; at the barrier `Tick(k+1)`, window `k`'s
  buffered external inputs are loaded, events are dispatched until the earliest is beyond window `k`, window `k`'s
  outputs are released in time order, and the tick is forwarded. A per-window event cap fails the stage on a runaway zero-delay cycle.
  Post-horizon events are summarized as residue.
- **D-rng.** Each node gets its own RNG, derived deterministically from the circuit's seed in node-declaration
  order — per-concern random streams (tailgate's common-random-numbers requirement) fall out naturally.
- **D-wiretap.** A wiretap edge copies an internal edge's events onto the consumption outlet, preserving the
  principle that every interaction is an observable timed event. Ships in this phase (small; keeps the principle
  intact from day one).
- **D-demo-home.** `examples/…/stochastacy/examples/mm1`, beside the store demos (core has no demos; MM1 needs no
  AWS). Console + JSONL; no Grafana dashboard.
- **D-demo-arms.** Two arms: **immediate** next-page requests (zero-delay loop) and **think time** (exponential,
  mean 20 ms — a sub-tick nonzero loop delay; still product-form).

## The MM1 demo

Sessions arrive Poisson(λ). The client requests page 1; each page response indicates more pages with probability
`p`, and the client requests the next page (immediately, or after think time). One server, FIFO, exponential
service at rate μ.

```
sessions (Poisson λ) ──▶ CLIENT ──page request──▶ SERVER (FIFO, 1 worker, Exp(μ) service)
                           ▲ fb                          │
                           └──────── page response ◀─────┘
```

With `λ_eff = λ / (1 − p)` and `ρ = λ_eff / μ` (Jackson network, product form):

| Metric | Closed form | λ=40, p=0.6, μ=125 |
|---|---|---|
| Pages per session | geometric, mean `1/(1−p)` | 2.5 |
| Queue length (time-average) | `P(n) = (1−ρ)ρⁿ`, mean `ρ/(1−ρ)` | 4 |
| Mean time per page | `1/(μ − λ_eff)` | 40 ms |
| Mean session duration | `1/((1−p)μ − λ)` (+ `(pages−1) × think` in the think arm) | 100 ms (130 ms) |
| Server busy fraction | `ρ` | 0.8 |

Ticks are 1 s; a session makes several loop round trips inside one tick.

## Slice status

| # | Slice | Status | Proof (target) |
|---|---|---|---|
| 1 | Sampler contract: input time + emitting feedback | **Done** | `at` passed to `sample`/`onFeedback`; `FeedbackEmission` with optional output; both transducer stages updated; every existing scenario **byte-identical** (all baseline specs unchanged); full `sbt test` |
| 2 | Circuit engine | **Done** | calendar stage over erased nodes: zero-delay self-loop ordered exactly; cross-tick loop; `onTick` order; residue; runaway cap; determinism; **a one-node circuit ≡ `componentOf`** byte-identically |
| 3 | Typed circuit builder + wiretap | **Done** | typed ports/edges/transforms; external routing; consumption mapping; per-node states + RNGs; build-time validation; wiretap; tailgate-shaped unit wiring (gate Admit/Reject edges, tap self-edge timeout); `Interface.wrap` around a circuit |
| 4 | Gates in circuits | **Done** | correlated `Reject(request, response)` across all four gates; a continuous-refill token bucket (backlog C2, pulled forward); gate wiring sugar; `Circuit.buildWith` handles; each gate proven in a retry loop; demo JSONLs byte-identical |
| 5 | MM1 demo | Planned | workload + client/server nodes + circuit + trial and Monte Carlo runners + theory module + `@main MM1Demo` (both arms, estimate vs theory, JSONL); smoke-run + determinism |
| 6 | Theory baseline | Planned | `MM1TheoryBaselineSpec`: every metric within its CI of the closed form, both arms, across a ρ sweep; conservation |
| 7 | Docs + close-out | Planned | catalog circuit + gate sections; `README.mm1-demo.md`; CLAUDE.md; backlog C1/C2/C3/C5 closed; program roadmap; memory; full `sbt test`; `sbt publishLocal` for tailgate |

## Slices

### Slice 1 — Sampler contract: input time + emitting feedback

The prerequisite contract change, isolated before anything depends on it. `sample(in, at, state, rng)` and
`onFeedback(fb, at, state, rng)` receive the input's conceptual time; `onFeedback` returns `FeedbackEmission` (state,
optional forward output, consumption, taps). The plain `ScheduleReleaseTransducer` stage passes each input's
`(eventTime, intraTick)`; the loopback stage does the same for primary and fed-back inputs and schedules a
feedback's optional output and taps exactly like a sample's. Migrate every implementation: the four core gates, the
`Interface` plumbing, `DynamoDbTable` (`onFeedback` → `FeedbackEmission(…, None, …)`), the four store samplers, and
test toys.

**Validated by:** new transducer specs — `at` equals each input's conceptual time on both stages; a loopback
`onFeedback` returning `Some(output)` has that output released in time order on the forward plane, with taps from
feedback also forwarded. **Every existing scenario byte-identical**: the store demos' specs, the AWS baseline specs,
and the hot-replica specs pass unchanged. Gate on the **full `sbt test`** (a shared-contract change — grep all three
modules for implementors first).

**Delivered.** New `stochastacy.sim.SimInstant(tick, intraTick)` — ordering, `toDouble`, `of(TimedEvent)`, and `plus(delay)`
(the rawOffset rule, ready for the Slice-2 calendar); a `require` enforces `intraTick ∈ [0, 1)`. The contract is now
`sample(in, at, state, rng)` and `onFeedback(fb, at, state, rng): FeedbackEmission(newState, output: Option[…],
consumption, taps)`; `onTick` and `TickEmission` are unchanged. Both transducer stages pass each input's conceptual time;
the loopback stage schedules a feedback's optional output, consumption, and taps exactly like a sample's. **Feedback-tap
guard (the approved D1 refinement):** because the tap window is released eagerly on `in`'s `Tick(w+1)` — but a window-`w`
fed-back item is always absorbed before `in` can pass that tick — a feedback tap is safe iff stamped at tick `≥ w + 1`;
the stage checks this at emission and fails **deterministically** (`IllegalStateException`, materialized future failed)
rather than detecting a scheduling-dependent late tap. Migrated 9 production samplers (4 gates, `DynamoDbTable` —
`onFeedback` → `FeedbackEmission(…, None, facts, Nil)` — and 4 store samplers) plus 6 test toys; the compile also
surfaced **50 direct `sample`/`onFeedback` call sites in unit specs** (gate, DynamoDB, store specs), updated mechanically
with a fixed `SimInstant(0L, 0.0)`. New tests: `SimInstantSpec` (5), the `at` case in `ScheduleReleaseTransducerSpec`, and
four `LoopbackTransducerSpec` cases (both-path `at`; feedback forward output + consumption released in time order inside
their windows; later-tick feedback taps sustain a multi-hop loop; a same-tick feedback tap fails the stage). Doc signature
references fixed in `CLAUDE.md`, `specs/component-catalog.md`, and `specs/README.store-demo.md`. **Full `sbt test` green
(540)**, and four demo JSONLs — store, store-v2, hot-replica (loopback path), capstone — are **byte-identical** to captures
taken before the change.

### Slice 2 — Circuit engine

The runtime, independent of the typed builder: a `CircuitStage` (`FanOutShape2`) over erased node adapters and a
routing table. Per barrier: node `onTick`s, load the window's external inputs, pop/dispatch/route until the earliest
event leaves the window, release outputs in time order, forward the tick; `EndOfTime` completes the materialized
result with per-node final states and residue. A low-level internal wiring API is enough to test it.

**Validated by:** a zero-delay self-loop interleaves fed-back items exactly between primary inputs by conceptual
time; a loop spanning ticks carries events across barriers; `onTick` runs before a window's events; post-horizon
residue is counted, not emitted; the runaway cap fails the stage with a clear error; repeated runs are identical;
and the anchor invariant — **a one-node circuit wired in → node → out is byte-identical to
`ScheduleReleaseTransducer.componentOf`** on the existing transducer spec fixtures. A throughput microbenchmark
(events/s through the calendar) is recorded for later tailgate sizing.

**Delivered.** Package `stochastacy.core.component.circuit`, purely additive (no existing file changed). Public:
`CircuitResult` (node final states in declaration order, `CircuitResidue`, per-plane `UnroutedCount`s,
`unroutedInputs`) and `CircuitPlane` (`Out` / `Taps` / `Consumption`). Internal (`private[stochastacy]`, for Slice 3's
typed builder): `CircuitPlan` (erased nodes + routes keyed by source, each route a target plus a filtering transform;
structural `require`s — the input feeds only node ports, `Out`/`Taps` feed node ports or the forward outlet,
consumption feeds only the consumption outlet), `ErasedNode` (any `LoopbackComponentSampler` behind a cast adapter,
with its own RNG; stateless — the stage owns state), and `CircuitStage.componentOf(plan)`. The stage places external
inputs on a calendar at their conceptual time; at `Tick(t)` it dispatches every event before `t` in `(tick,
intraTick, seq)` order — events it creates that also land before `t` join the same pass, which is how a loop closes
inside the tick — then releases outlet items before `t`, runs every node's `onTick(t)` (boundary facts stamped at
`(t, 0) + delay`), and forwards the tick: the transducer's own order. Approved decisions as implemented: D1
conceptual-time dispatch of external inputs (a dedicated spec documents the difference from the transducer's
arrival order on unsorted input); D2 a negative delay fails the stage; D3 unrouted emissions are dropped and counted;
D4 a per-window dispatch cap (default 10 M) fails a runaway zero-delay cycle; D5 tight visibility. A node exception
fails the stage naming the node and port. `CircuitStageSpec` (13), `CircuitAnchorSpec` (5 — delayed outputs, residue,
boundary facts, per-tick reset, RNG-drawn delays), and **`CircuitAnchorDynamoDbSpec` (2) — a one-node circuit hosting
the real `DynamoDbTableSampler` is byte-identical to `DynamoDbTable.componentOf`** on the single-region thermostat
table (GSIs + LSI) and the auto-scaling telemetry table (`onTick`-heavy); the table's taps, unrouted, are the only
unrouted plane. Full `sbt test` green (560). Benchmark (`CircuitThroughputBenchmark`, a `main` in core test sources,
not part of `sbt test`; Apple M3 Max, one warm-up pass then one timed pass): **~37 M dispatches/s** through the calendar
(a zero-delay two-node relay, 10,000,020 dispatches verified from node states), and on 1 M framed inputs a one-node
circuit runs **~1.65 M events/s vs ~1.12 M for `componentOf`** — per-element stream overhead, not the calendar,
dominates. Rough tailgate sizing: Stage 2's ~6 × 10⁹ arrivals at ~1.6 M/s is ~1 CPU-hour, before per-request internal
dispatches (cheap by (a)) and trial parallelism.

### Slice 3 — Typed circuit builder + wiretap

The user-facing API: `Circuit.builder { b => … }` with typed node handles (`in`, `fb`, `out`, `taps`), typed edges
with partial-function transforms, `b.input(port)`, `b.output(plane)`, per-node consumption mapping, wiretaps, and
`Circuit.componentOf(circuit, rng)` deriving per-node RNGs. Build-time validation rejects malformed wiring (e.g. an
`fb` edge into a node that has no feedback port, a circuit with no input). `CircuitResult.stateOf(node)` returns a
node's typed final state.

**Validated by:** typed toy circuits; a gate node whose `Admit`/`Reject` outputs split along two filtered edges; a
tap self-edge that fires a timeout probe back into its own node (the tailgate client's shape, unit-level only); a
wiretap delivering an internal edge's events on the consumption outlet in time order; `Interface.wrap` around a
circuit; per-node RNG derivation deterministic and independent of wiring order changes that don't reorder nodes.

**Delivered.** Public API in `stochastacy.core.component.circuit`: `Circuit[In, Out, Cons]` (an immutable, reusable
blueprint; `Circuit.build { b => … }` validates, `Circuit.componentOf(circuit, rng)` materializes an ordinary
`FanOutShape2` component with a `Future[CircuitResult]`), `CircuitBuilder`, and typed `CircuitNode` handles whose
ports (`InPort[-A]`, `FbPort[-A]`) and planes (`OutPlane[+A]`, `TapPlane[+A]`, `ConsumptionPlane[+A]`) carry the node
sampler's types — the variances make mismatched wiring a compile error, a plain sampler's `FbPort[Nothing]` unconnectable,
and consumption impossible to `connect` or wiretap. Wiring: `connect`/`connectVia`, `input`/`inputVia`,
`output`/`outputVia`, `consumption`/`consumptionVia`, `ignore`, `wiretap`, `maxEventsPerWindow` (P1: distinct `…Via`
names for partial-function transforms; P5: the cap as a builder setter). Validation — at the call: a handle from
another builder, routing an ignored consumption plane or ignoring a routed one; at build: no nodes, no input route,
duplicate names, a node with no inbound route (D3), and a node with real consumption neither routed nor ignored (D4 —
decided at compile time by a `ConsumptionDemand` given, so `Nothing`-consumption nodes such as gates need no `ignore`).
RNGs (D2 / P2): a seed is drawn from `componentOf`'s RNG for **every** node in declaration order and each node uses its
optional pinned `rngSeed` or the drawn seed, in a fresh KISS per materialization. `CircuitResult.stateOf(node)` gives a
typed final state. Engine edits: `Route` gained a `wiretap` flag — `Out`/`Taps` → consumption outlet is permitted only
as a wiretap, and a wiretap copy does not count as routing (P4). `TrialRunner.run` now accepts any component `Future[R]`
(P3; `SingleTrialRunner` unchanged). Tests: `CircuitBuilderSpec` (7 — a typed two-node paging loop, every wiring form,
wiretap ordering + unrouted accounting, every build error, `Nothing`-consumption gate, RNG derivation / pinning,
blueprint reuse), `CircuitTailgateShapesSpec` (2 — a `FlatThrottleGate` node's admit/reject split along filtered edges;
a client timeout probe on a tap self-edge that retries at send + 0.5, re-arms, and ignores late or resolved events),
`CircuitInteropSpec` (3 — typed anchor ≡ `componentOf`, behind `Interface.wrap`, under `TrialRunner`). Full `sbt test`
green (572). Findings: handles must be captured outside the `build` block (e.g. a `var`) to call `stateOf` after a run
— an ergonomics gap to settle before the MM1 demo; and core gates reject with a *constant* response that cannot
identify the rejected request (stochastacy backlog C5).

### Slice 4 — Gates in circuits

Added 2026-09-15 at Brian's direction: throttling, rate limiting, and failure injection belong in feedback
simulations, so the shipped gates must work as **circuit nodes in a loop**, not only inline under `Interface.wrap`.
They already compose as nodes (Slice 3 wires a `FlatThrottleGate` and splits its outcomes); what a loop exposes is
that a rejection cannot say *which* request it rejected, and that the rate-limiting gates only move at tick
granularity.

Three changes. **Correlated rejection** (backlog C5): `Reject` carries the request as well as the response
(`Reject(request, response)`), so every gate correlates without per-gate configuration and an edge can read
`{ case Reject(req, _) => Retry(req) }`; `Interface.wrap` still emits only the response, so wrapped scenarios are
unchanged. **A continuous-refill token bucket** (backlog C2, pulled forward from after-tailgate on Brian's call):
samplers now receive `at`, so the bucket can refill in continuous time rather than once per tick — and the property
that bounds it (admissions over any interval `T` never exceed `B + r·T`) is directly testable in core, so tailgate can
consume the gate instead of building its own. **Ergonomics:** gate wiring sugar
(`b.gate(name, gate, admitTo = …, rejectTo = …)`, adding both filtered edges) and `Circuit.buildWith`, which returns
the circuit together with the handles the block yields, so `stateOf` needs no `var`.

**Validated by:** each of the four gates as a node in a **retry loop**, where rejections drive retries and the client
identifies the retried request; the continuous-refill bucket's interval bound as a property test, plus a test that a
once-per-tick refill **fails** it; the tick-granular gates unchanged; `Interface.wrap` behavior unchanged; and the
store, store-v2, hot-replica, and capstone demo JSONLs **byte-identical** to pre-change captures (this touches the
store-v2 and AWS chaos-gate paths). Full `sbt test`.

**Delivered.** `Reject[+Req, +Resp](request, response)` (backlog C5): the three rejecting gates build the rejection
from the request, their constructors unchanged, so all ~30 construction sites still compile; `Interface.wrap` still
emits only the response, so wrapped scenarios are untouched. New `ContinuousTokenBucketGate` (backlog C2, pulled
forward) accrues tokens from elapsed conceptual time (`at.toDouble`) with no `onTick` at all — `TokenBucketGate`
remains the tick-granular one. Builder additions: `b.gate(name, gate)(admitTo, rejectTo)` delivering the whole
`Reject(request, response)`, `b.gateVia(…)(…)(rejectAs)` mapping it, and `Circuit.buildWith[In, Out, Cons] { b => … }`
returning the block's handles beside the circuit (applied in two steps so the handle type is inferred). Migration was
`Interface.scala` + 3 gates + 8 spec sites — including three `Reject[?]` **type** tests that only an exhaustive grep
caught. New tests: `GateCircuitLoopSpec` (5 — every shipped gate in a retry loop; the client retries exactly the
rejected requests **by id**, which is what correlated rejection unlocks) and `ContinuousTokenBucketGateSpec` (4 —
mid-tick refill, **with the tick-granular bucket shown failing the same case**; the `capacity + refill × T` interval
bound over all admission pairs; capacity cap across idle time; correlated rejection). Full `sbt test` green (582:
97 examples + 191 core + 294 aws) and the store, store-v2, hot-replica and capstone JSONLs **byte-identical**. Two
self-inflicted test defects found by the gate and fixed: `buildWith`'s type-parameter arity (which reshaped the API
into its two-step form) and an assertion that ignored `FlatThrottleGate`'s per-tick counter reset.

### Slice 5 — MM1 demo

`stochastacy.examples.mm1`: `MM1Config` (λ, μ, p, optional think-time mean, ticks, trials, seed); a session workload
(exponential inter-arrivals placed within ticks); the `ClientNode` (loopback: `sample(session)` → page 1;
`onFeedback(response)` → next page with probability `p` — immediate or after think time — or a session-complete
record) and the `ServerNode` (FIFO start `max(at, serverFree)`, exponential service as the output delay,
time-weighted queue-length and busy-time facts); the circuit; `MM1TrialRunner` folding consumption into per-trial
statistics; `MM1MonteCarloRunner` (`MonteCarlo.stream` + fold); `MM1Theory` (the closed forms); `@main MM1Demo`
printing estimate vs theory with confidence intervals for both arms, plus per-trial JSONL.

**Validated by:** a demo smoke-run; determinism (same seed → identical output); conservation (sessions in =
completed + in-flight residue; page requests = page responses + residue).

### Slice 6 — Theory baseline

`MM1TheoryBaselineSpec`, the phase's proof: for both arms and a small ρ sweep (e.g. 0.5 / 0.8 / 0.9), every metric —
pages per session, time-average queue length and its geometric distribution, time per page, session duration, busy
fraction — falls within its Monte Carlo confidence interval of the closed form, with trial counts sized so the
check is meaningful and fast. Any failure is investigated to root cause, not tuned away.

### Slice 7 — Docs + close-out

`specs/component-catalog.md` gains a circuits section: what a circuit is, when to use a circuit vs the Pekko graph,
calendar ordering and tie-breaks, the within-window arrival-order limitation of the Pekko loopback stage, the new
`at` / `FeedbackEmission` contract, and wiretaps — plus a **gates** update: correlated rejection, the continuous-refill
bucket, and when to use a gate as a circuit node vs. inline under `Interface.wrap`. New `specs/README.mm1-demo.md`.
CLAUDE.md: engine section, current position, and an MM1 demo workflow. Backlog: C1, C2, C3 and C5 closed (C2 and C5
land in Slice 4). Program roadmap + memory. Full `sbt test`. **`sbt publishLocal`** so tailgate can resume on
the new core (tailgate verifies the dependency resolves before its own work continues).

## Scope boundary

In scope: the contract change, circuits (engine + typed builder + wiretap), **gates usable in feedback loops**
(correlated rejection + a continuous-refill token bucket), and the MM1 demo with its theory baseline. Not in scope:
multi-inlet circuits; loops *between* circuits or Pekko stages (would need F1's ≥ 1-tick registers — recorded in the
design note); feedback driven by consumption metrics; exact ordered dispatch in the Pekko loopback stage; a Grafana
dashboard for MM1; a metric (consumption) plane on gates — a wiretap on a gate's outcome plane already carries
throttle metrics; the artifact-coordinate cleanup (backlog B1–B4). After this phase, work returns to tailgate.
