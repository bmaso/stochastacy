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
| 1 | Interface machinery + flat-throttle gate + minimal V2 edge | Planned | new V2 pipeline throttles correctly; 1:1 integrity; original demo untouched |
| 2 | Latency gate + two-gate stack | Planned | admit-all decorator works; `latency → throttle` stacks; latency accumulates |
| 3 | Burst (token-bucket) gate + headline experiment | Planned | equal-mean bucket throttles less than a flat cap under bursts |
| 4 | Chaos gate + orthogonality + full-stack integrity | Planned | 429 rate scales with load, 503 rate ≈ constant; one terminal outcome per request |
| 5 | Store Demo V2 capstone: reporting + docs | Planned | full-stack MC run; per-gate reject rates reported; specs + root README |
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

### Slice 2 — Latency gate + two-gate stack

Core: `LatencyGate` — the trivial decorator (admit everything, add latency). Examples: extend the V2
edge to a two-gate stack, `latency → throttle → datastore` (the throttle gate from Slice 1).

**Validated by:** the admit-all path works; two gates compose; edge latency accumulates across the stack;
1:1 integrity still holds.

### Slice 3 — Burst-capacity (token-bucket) gate + the headline experiment

Core: `TokenBucketGate` — stateful; refills tokens per tick via `onTick`, admits bursts up to the bucket
size, rejects (429) when empty.

**Validated by:** the headline experiment — the *same bursty workload* through a flat cap vs. a token
bucket of *equal average rate*: the bucket throttles far less during bursts yet matches on the mean.
Policy, not just capacity, governs throttling under bursty load. (The "keyset vs. offset" of this demo.)

### Slice 4 — Chaos-failure gate + orthogonality + full-stack integrity

Core: `ChaosGate` — an independent per-request draw, load-independent, rejecting with a `503`-style
response.

**Validated by:** sweeping offered load, the `429` (throttle) rate scales with load while the `503`
(chaos) rate holds ≈ its configured probability — the two mechanisms are orthogonal; the full stack
composes; every request ends in exactly one terminal outcome (served / 429 / 503).

### Slice 5 — Store Demo V2 capstone: reporting + docs

Wire the full edge stack over the capstone workload as a Monte Carlo ensemble; add V2 reporting that
breaks out per-gate reject rates alongside the datastore metrics; land the burst-vs-flat and
orthogonality experiments as capstone assertions; write `specs/README.store-demo-v2.md` and add a Store
Demo V2 paragraph to the root README. Declares the phase complete.

**Validated by:** the demo *visibly* exhibits latency accrual, load-driven throttling, burst tolerance,
and load-independent chaos failures; exported output is inspectable.

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
