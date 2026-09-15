# v2/phase13 — Closed-loop circuits

**Status: PLANNED** (roadmap drafted 2026-09-15, awaiting approval). Gives `stochastacy.core` **closed feedback
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
  than an additive overload. Migration is mechanical: 13 `sample` implementations (4 core gates, the interface
  plumbing, `DynamoDbTable`, 4 store samplers, test toys) plus one production `onFeedback` (`DynamoDbTable`).
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
| 1 | Sampler contract: input time + emitting feedback | Planned | `at` passed to `sample`/`onFeedback`; `FeedbackEmission` with optional output; both transducer stages updated; every existing scenario **byte-identical** (all baseline specs unchanged); full `sbt test` |
| 2 | Circuit engine | Planned | calendar stage over erased nodes: zero-delay self-loop ordered exactly; cross-tick loop; `onTick` order; residue; runaway cap; determinism; **a one-node circuit ≡ `componentOf`** byte-identically |
| 3 | Typed circuit builder + wiretap | Planned | typed ports/edges/transforms; external routing; consumption mapping; per-node states + RNGs; build-time validation; wiretap; tailgate-shaped unit wiring (gate Admit/Reject edges, tap self-edge timeout); `Interface.wrap` around a circuit |
| 4 | MM1 demo | Planned | workload + client/server nodes + circuit + trial and Monte Carlo runners + theory module + `@main MM1Demo` (both arms, estimate vs theory, JSONL); smoke-run + determinism |
| 5 | Theory baseline | Planned | `MM1TheoryBaselineSpec`: every metric within its CI of the closed form, both arms, across a ρ sweep; conservation |
| 6 | Docs + close-out | Planned | catalog circuit section; `README.mm1-demo.md`; CLAUDE.md; backlog C1/C3 closed; program roadmap; memory; full `sbt test`; `sbt publishLocal` for tailgate |

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

### Slice 4 — MM1 demo

`stochastacy.examples.mm1`: `MM1Config` (λ, μ, p, optional think-time mean, ticks, trials, seed); a session workload
(exponential inter-arrivals placed within ticks); the `ClientNode` (loopback: `sample(session)` → page 1;
`onFeedback(response)` → next page with probability `p` — immediate or after think time — or a session-complete
record) and the `ServerNode` (FIFO start `max(at, serverFree)`, exponential service as the output delay,
time-weighted queue-length and busy-time facts); the circuit; `MM1TrialRunner` folding consumption into per-trial
statistics; `MM1MonteCarloRunner` (`MonteCarlo.stream` + fold); `MM1Theory` (the closed forms); `@main MM1Demo`
printing estimate vs theory with confidence intervals for both arms, plus per-trial JSONL.

**Validated by:** a demo smoke-run; determinism (same seed → identical output); conservation (sessions in =
completed + in-flight residue; page requests = page responses + residue).

### Slice 5 — Theory baseline

`MM1TheoryBaselineSpec`, the phase's proof: for both arms and a small ρ sweep (e.g. 0.5 / 0.8 / 0.9), every metric —
pages per session, time-average queue length and its geometric distribution, time per page, session duration, busy
fraction — falls within its Monte Carlo confidence interval of the closed form, with trial counts sized so the
check is meaningful and fast. Any failure is investigated to root cause, not tuned away.

### Slice 6 — Docs + close-out

`specs/component-catalog.md` gains a circuits section: what a circuit is, when to use a circuit vs the Pekko graph,
calendar ordering and tie-breaks, the within-window arrival-order limitation of the Pekko loopback stage, the new
`at` / `FeedbackEmission` contract, and wiretaps. New `specs/README.mm1-demo.md`. CLAUDE.md: engine section, current
position, and an MM1 demo workflow. Backlog: C1 and C3 closed; C2 (harvest tailgate's continuous-refill gate) still
scheduled after tailgate. Program roadmap + memory. Full `sbt test`. **`sbt publishLocal`** so tailgate can resume on
the new core (tailgate verifies the dependency resolves before its own work continues).

## Scope boundary

In scope: the contract change, circuits (engine + typed builder + wiretap), and the MM1 demo with its theory
baseline. Not in scope: multi-inlet circuits; loops *between* circuits or Pekko stages (would need F1's ≥ 1-tick
registers — recorded in the design note); feedback driven by consumption metrics; exact ordered dispatch in the
Pekko loopback stage; a Grafana dashboard for MM1; the continuous-refill gate (backlog C2, after tailgate); the
artifact-coordinate cleanup (backlog B1–B4). After this phase, work returns to tailgate.
