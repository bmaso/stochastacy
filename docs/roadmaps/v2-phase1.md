# v2/phase1 — Interface components: composable admission/rejection gating

Started on branch `v2/phase1`, following the conclusion of `v2/phase0` (domain-agnostic core proven by
the Store demo). This phase adds a reusable **interface component** to `stochastacy.core` — a
transparent, stackable decorator that sits on a request/response edge and makes an admit-or-reject
decision per request — and proves it out with **Store Demo V2**, a second store demo whose service edge
is built from a *stack of gates* (latency, flat-throttle, burst, chaos) instead of the bespoke admission
component the original demo used.

## Goal

A generic gating layer that composes. An **interface** wraps a downstream `Req → Resp` component and
presents the *same* interface upstream. For each request an **interface sampler** decides:

- **admit** → forward to the downstream; return its response; optionally add latency;
- **reject** → short-circuit, returning a sampler-supplied response of the *same* `Resp` type (a 429, a
  503, …), never touching the downstream.

Because rejections are **in-band** (a normal `Resp` variant), 1:1 request/response integrity holds for
free and no new response types are needed — the pattern proven in phase-0 Slice 6b. Because the wrapped
component is **shape-preserving** (same `Req → Resp` interface as what it wraps), interfaces **stack**:
`latency → throttle → burst → chaos → datastore`, each adding one behavior.

## Confirmed decisions

- **D-core — reusable gates live in `core`.** The `Interface.wrap` machinery *and* the concrete gates
  (latency / flat-throttle / burst / chaos) are domain-agnostic and go in `stochastacy.core`. A gate
  carries no domain knowledge — only its own config plus a **domain-supplied reject response** (the
  store supplies `ErrorResult("throttled")`, `ErrorResult("unavailable")`, …). This is a deliberate step
  past phase-0's "keep core thin," justified because a reusable gating library *is* the deliverable.
- **D-additive — Store Demo V2 is new code; the original demo is frozen.** No existing store file is
  modified. V2 reuses the store **datastore** (`StoreSampler`), **protocol** (`StoreProtocol` /
  `ApiProtocol`, incl. `ErrorResult`), and **workload** (`ApiWorkload`) by import only, and adds new
  types (a new edge runner, reporting, demo bridge, specs). The original `AdmissionSampler`-based demo
  stays intact for comparison; V2 achieves the same throttling through the generic interface component.

## Open design decisions (resolved at each slice's plan time)

- **DD-1 — how a gate surfaces its observations.** Two candidates, decided in Slice 1: (a) gates emit no
  consumption plane, and their effects are read *from the response stream* (rejections are responses;
  latency from response timing) — trivially shape-preserving; or (b) gates emit consumption facts in the
  downstream's own `Cons` type via a domain-supplied recorder, and `Interface.wrap[Req, Resp, Cons]`
  tick-aligned-merges both the response and consumption planes so the wrapped shape still equals the
  downstream's. Core must not acquire a forced observation type (phase-0 Slice 4 principle).
- **DD-2 — stack construction.** Whether stacking is nested `wrap(wrap(ds, a), b)` (true decorator) or a
  runner that wires a sequence of interface stages; the shape-preserving goal favors nesting.

## Slice status

| # | slice | status | proof |
|---|---|---|---|
| 1 | Interface machinery + flat-throttle gate + minimal V2 edge | **Done** | new V2 pipeline throttles; exact 1:1 incl. rejections; original demo untouched (core 491, examples 235) |
| 2 | Latency gate + two-gate stack | **Done** | admit-all decorator; `latency → throttle` stacks; latency accumulates (core 499, examples 237) |
| 3 | Burst (token-bucket) gate + headline experiment | **Done** | bucket absorbs a burst a flat cap rejects (0 vs 13); bounded advantage under overload (core 505) |
| 4 | Chaos gate + orthogonality + full-stack integrity | **Done** | 429 rate climbs with load while 503 ≈ constant (~0.1); full stack gives one terminal outcome each (core 511) |
| 5a | V2 edge assembly + MC + reporting + bridge | **Done** | configurable gate stack; per-gate outcome rates via MC; `@main` bridge (core 511, examples 242) |
| 5b | Burst-vs-flat + orthogonality experiments + docs | Planned | demo-scale experiment assertions; specs/README.store-demo-v2.md + root README |
| 6 | Component catalog / engineer's guide | Planned | a guide cataloguing the reusable components, their properties, use-cases, and example demos |
| — | Circuit-breaker gate (stretch) | Deferred | response-feedback path; likely a later phase |

## Slices

### Slice 1 — Interface machinery + flat-throttle gate + minimal V2 edge

Core: the `InterfaceSampler` / `InterfaceOutcome (Admit(Req) | Reject(Resp))` contract, the
shape-preserving `Interface.wrap(downstream, gate, rng)` factory (encapsulating the `Broadcast` +
tick-aligned `MergeTimedEventGraph` rejoin currently open-coded in `StoreTrialRunner`), and the first
gate, `FlatThrottleGate` (per-tick cap; `onTick` resets the counter). Examples: a **new** minimal Store
Demo V2 pipeline (`datastore` wrapped by the throttle interface) plus its runner — reusing the store
datastore/protocol/workload, modifying nothing.

Resolves **DD-1** and **DD-2**.

**Validated by:** the V2 pipeline throttles under load; every request yields exactly one terminal
response (served or rejected); determinism under a fixed seed; the original store demo's files and tests
are untouched. *May split into 1a (core machinery + gate, unit-tested in isolation) / 1b (minimal V2
pipeline) at plan time.*

**Delivered** (core 491 +7, examples 235 +4; every phase-0 store test unchanged). **DD-1 resolved:**
gates carry consumption type `Nothing` — no metric plane; a rejection *is* a `Resp`, so throttle rate is
read from the response stream and `wrap` passes the downstream's consumption through untouched. **DD-2
resolved:** `wrap` returns the *same shape and Mat type* as its downstream, so wraps nest (Slice 2
stacks with no new mechanism). Core (`stochastacy.core.component`): `InterfaceOutcome (Admit | Reject)`,
`trait InterfaceSampler[S,Req,Resp] extends ComponentSampler[S,Req,InterfaceOutcome[Req,Resp],Nothing]`,
and `Interface.wrap[S,Req,Resp,Cons,Mat](downstream, gate, rng)` — encapsulating the `Broadcast` +
tick-aligned `MergeTimedEventGraph` rejoin (with the three generic collect/cast flows generalized from
the store runner) as a `GraphDSL.createGraph` returning a `FanOutShape2` with the downstream's Mat. First
gate `stochastacy.core.component.gate.FlatThrottleGate` (per-tick cap; `onTick` reset; caller-supplied
reject response) — the generic form of the frozen `AdmissionSampler`. Examples (new package
`stochastacy.examples.store.v2`, reusing `store.*` by import only): `StoreV2TrialRunner` wraps the
datastore with `FlatThrottleGate(ErrorResult("throttled"))`, driven by `ApiWorkload` translated to
`StoreRequest`; `StoreV2TrialResult`. Scope: minimal — no stats/reporting/MC (later slices). Core unit
tests: `FlatThrottleGateSpec`, `InterfaceSpec` (toy echo downstream: admit forwards, reject
short-circuits, 1:1, control events preserved, downstream consumption passes through, deterministic).

### Slice 2 — Latency gate + two-gate stack

Core: `LatencyGate` — the trivial decorator (admit everything, add latency). Examples: extend the V2
edge to a two-gate stack, `latency → throttle → datastore` (the throttle gate from Slice 1).

**Validated by:** the admit-all path works; two gates compose; edge latency accumulates across the stack;
1:1 integrity still holds.

**Delivered** (core 499 +8, examples 237 +2; all prior tests pass). `stochastacy.core.component.gate.LatencyGate[Req,Resp](latency: StatelessSampler[Double])` — admits every request, adds a per-request latency **drawn from a distribution** (not constant; `LatencyGate.constant(ticks)` is a named special case, and `LogNormalSampler.constant(mu, sigma)` is the realistic form). It threads the current tick into its state via `onTick` (second real use of the hook) so a sampler whose params vary with tick gives **time-varying** latency; draws clamped to `>= 0`. **D1:** latency is proven at the core level by response timing (a single request through `wrap(echo(0.1), LatencyGate.constant(0.5))` lands at conceptual time 2.6; two stacked gates → 2.8 — accumulation), gates emitting no metric plane. **D3:** gate order `latency → throttle → datastore` (latency outermost). V2 edge is now `Interface.wrap(Interface.wrap(datastore, throttle), latency)` with `edgeLatency: StatelessSampler[Double] = ConstantSampler(0.0)` (default preserves Slice-1 behavior); the seed split keeps `workloadRng` first. Tests: `LatencyGateSpec` (constant, distributional draw, tick-threading, negative-clamp), `InterfaceSpec` stack group (accumulation + 1:1/throttle-with-latency-in-front), V2 spec (log-normal latency composed in front of throttle keeps throttling + exact 1:1; deterministic).

### Slice 3 — Burst-capacity (token-bucket) gate + the headline experiment

Core: `TokenBucketGate` — stateful; refills tokens per tick via `onTick`, admits bursts up to the bucket
size, rejects (429) when empty.

**Validated by:** the headline experiment — the *same bursty workload* through a flat cap vs. a token
bucket of *equal average rate*: the bucket throttles far less during bursts yet matches on the mean.
Policy, not just capacity, governs throttling under bursty load. (The "keyset vs. offset" of this demo.)

**Delivered** (core 505 +6; examples unchanged — core-only per D-scope). `stochastacy.core.component.gate.TokenBucketGate[Req,Resp](capacity, refillPerTick, rejectResponse, latencyTicks)` — **fractional tokens** (DQ1); one token per admit; `onTick` refills capped at `capacity` (third use of the hook); **starts full** (D-init). The experiment is a deterministic pure-sampler simulation (DQ2) comparing it to `FlatThrottleGate` at equal average rate `R=5`: (Goal 2) arrivals `[2,2,2,18,2,2]` (mean < R) → flat `(15,13)` vs bucket `(28,0)` — the bucket absorbs the spike the flat cap rejects; (Goal 3) sustained `fill(20)(10)` → flat rejects 100, bucket rejects 85 with `flatRejected − bucketRejected ≤ capacity` (bounded advantage — the bucket is refill-limited long-term, no cheating). `TokenBucketGateSpec` also covers start-full, fractional-refill accumulation, and refill-cap; `InterfaceSpec` gains a token-bucket-through-`wrap` 1:1 composition test. **D-scope:** token-bucket integration into `StoreV2TrialRunner` and the burst-workload demo are deferred to Slice 5.

### Slice 4 — Chaos-failure gate + orthogonality + full-stack integrity

Core: `ChaosGate` — an independent per-request draw, load-independent, rejecting with a `503`-style
response.

**Validated by:** sweeping offered load, the `429` (throttle) rate scales with load while the `503`
(chaos) rate holds ≈ its configured probability — the two mechanisms are orthogonal; the full stack
composes; every request ends in exactly one terminal outcome (served / 429 / 503).

**Delivered** (core 511 +6; examples unchanged — core-only per D-scope). `stochastacy.core.component.gate.ChaosGate[Req,Resp](fail: StatelessSampler[Boolean], rejectResponse, latencyTicks)` — an independent per-request Bernoulli draw (DQ1: sampler-driven, reusing `BernoulliSampler`; `ChaosGate.constant(p, resp)` convenience; tick threaded via `onTick` for time-varying failure rates), load-independent by construction. The orthogonality experiment (`ChaosGateSpec`) is a deterministic pure-sampler simulation of a **chaos-outermost** `chaos → throttle` stack (DQ-order — so 503 faces a load-independent population): sweeping constant load `{3, 8, 20}`/tick over 200 ticks at `p=0.1`, cap 5, the 503 rate stays ≈ 0.1 (spread < 0.03 across a 6.7× load increase) while the 429 rate climbs strictly (~0 → ~0.65, swing > 0.4), and `served + 503 + 429 == total` at every load. `InterfaceSpec` gains a three-gate full-stack test (`latency → chaos → throttle` over echo): exactly one terminal outcome per request (served / -429 / -503), all classified, deterministic. **D-scope:** wiring the gates into the store edge + the V2/Monte-Carlo demo experiments are Slice 5.

### Slice 5 — Store Demo V2 capstone: reporting + docs

Wire the full edge stack over the capstone workload as a Monte Carlo ensemble; add V2 reporting that
breaks out per-gate reject rates alongside the datastore metrics; land the burst-vs-flat and
orthogonality experiments as capstone assertions; write `specs/README.store-demo-v2.md` and add a Store
Demo V2 paragraph to the root README. Delivers the runnable Store Demo V2 capstone.

**Validated by:** the demo *visibly* exhibits latency accrual, load-driven throttling, burst tolerance,
and load-independent chaos failures; exported output is inspectable.

**Split into 5a (edge assembly + MC + reporting + bridge) and 5b (experiments + docs).**

**5a Delivered** (examples 242 +5; core unchanged; phase-0 demo untouched). New `stochastacy.examples.store.v2`
code: `EdgeConfig` (structured — `latency` sampler, `RateLimiter.FlatThrottle | TokenBucket`, `chaosProbability`)
with `EdgeConfig.gates` building the outermost-first stack `latency → rate-limiter → chaos`;
`StoreV2TrialRunner` gains a structured `run(edge)` and a raw `runGates(Seq[InterfaceSampler[?, …]])`
(folds gates over the datastore via `Interface.wrap`, existential state), folding **two planes** into
windowed `Statistics[StoreStatKey]` — the datastore's `Consumption` (via `StoreStats.observations`) and,
since gates emit no metric plane, each request's terminal outcome classified from the response stream
into 0/1 `outcome.served`/`outcome.throttled`/`outcome.chaos` (mean = rate). `StoreV2MonteCarloRunner`
reuses `MonteCarlo` + `StoreMonteCarloResult`; `StoreV2Report.summary` reports per-gate/per-use-case
outcome rates (JSONL reuses the frozen `StoreReport.jsonl`); `StoreV2Demo` `@main` bridge runs green
(served 75.9% / throttled 22.6% / chaos 1.5% at cap 18, p=0.02). Reuse-by-import throughout; original
demo frozen.

### Slice 6 — Component catalog / engineer's guide

An engineer's guide to the reusable components built in `core` — the interface component and its gates
(and, as it makes sense, the broader component/sampler/stats/run machinery they build on). A catalog
entry per component covering: what it is and the shape it presents; its **logical properties**
(shape-preserving, in-band rejection / 1:1 integrity, stateless vs. stateful, `onTick` semantics,
determinism); intended **use-cases** (when to reach for it, and how gates compose/stack); and **which
demos use it** as a worked example (linking `store.v2` and the phase-0 store demo). Scoped as
documentation only — no new component code. Declares the phase complete.

**Validated by:** a reviewer can, from the guide alone, pick the right gate for a gating problem, know
its properties and how to compose it, and jump to a demo that exercises it.

### Stretch — Circuit-breaker gate

The one gate needing the **response-feedback** path: the interface observes the downstream responses
flowing back through it, tracks a recent error-rate window, and opens (fast-fails with a 503) for a
cooldown when the rate spikes. Out of scope for the clean "decide-from-request-only" line of slices 1–5;
taken on deliberately as its own slice or pushed to a later phase.

## Design principles and reuse

- **In-band rejections** preserve 1:1 integrity and need no new response types (phase-0 Slice 6b).
- **Shape-preserving decorator** is what makes gates stack; the wrapped component exposes the same
  `Req → Resp` interface as the downstream it wraps.
- Reuse existing machinery: `MergeTimedEventGraph` (tick-aligned rejoin), the `onTick` hook (phase-0
  Slice 6a) for stateful gates (throttle counter, token-bucket refill), the schedule-and-release
  transducer for latency stamping.
- **Core stays free of a forced observation type** (phase-0 Slice 4): whatever DD-1 resolves to, the
  engine must not impose an observation vocabulary on gates.

## Deferred / future gates (beyond this phase)

Circuit breaker (stretch, above); and — not yet scoped — per-tenant quota / rate limit, concurrency
bulkhead (in-flight limit), priority-based load shedding. Each is a further `InterfaceSampler`; the phase
delivers the machinery that makes them cheap to add.
