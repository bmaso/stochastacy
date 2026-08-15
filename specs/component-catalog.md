# Component Catalog — Engineer's Guide

A catalog of the reusable building blocks in `stochastacy.core`: the **interface component** and its
**gates** (latency, throttling, burst, chaos), plus the **foundations** they are built on. Unlike the
demo guides ([Store Demo](README.store-demo.md), [Store Demo V2](README.store-demo-v2.md)) — which
explain what a particular simulation *shows* and how to run it — this catalog describes the parts as
**building blocks you can drop into your own simulator**: what each is, the properties it guarantees,
when to reach for it, and how the pieces compose.

Scope is the **domain-agnostic core**. The AWS/DynamoDB simulator components are a separate line and are
not catalogued here.

## How to read an entry

Each primary entry follows one template:

- **Purpose** — one line.
- **Signature** — the type it presents.
- **Properties** — the logical guarantees it upholds.
- **When to use** — the problem it solves.
- **Composition** — how it stacks with others.
- **Exercised by** — the demo and the test that proves each property.

A note on one shared vocabulary word: a component is **shape-preserving** if the thing it produces
presents the *same* request→response interface (and the same materialized value) as the thing it wraps —
which is exactly what lets components nest and stack.

---

## The interface component

### `Interface.wrap`

**Purpose.** Put an admit/reject gate on a component's request/response edge, transparently.

**Signature.**
```scala
Interface.wrap[S, Req, Resp, Cons, Mat](
  downstream: Graph[FanOutShape2[Timed[Req], Timed[Resp], Timed[Cons]], Mat],
  gate:       InterfaceSampler[S, Req, Resp],
  rng:        UniformRandomProvider
): Graph[FanOutShape2[Timed[Req], Timed[Resp], Timed[Cons]], Mat]
```
*(element types abbreviated; the wire carries `TimedElement[Timed[…]]`.)*

**Properties.**
- **Shape- and Mat-preserving decorator.** The wrapped component exposes the *same* `Req → Resp`
  interface and the *same* materialized value as `downstream`. So `wrap(wrap(ds, a), b)` type-checks —
  gates **stack by nesting**.
- **In-band rejection → 1:1 integrity.** A rejection is an ordinary `Resp` value (a `Reject(response)`),
  merged back into the response stream, so **every request yields exactly one terminal response** —
  served or rejected — and no new response type is needed.
- **No metric plane.** A gate's consumption type is fixed to `Nothing`, so the wrap adds no observations
  of its own and passes the downstream's consumption through untouched. Gate effects are read from the
  **response stream** (a rejection *is* a response) — the engine acquires no forced observation type.
- **Latency-aware.** A gate's `Scheduled` delay stamps the timing: on an admit it shifts when the
  downstream receives the request; on a reject it shifts when the rejection emerges.

**When to use.** Any time you want to add gating behavior (rate limiting, failure injection, latency,
back-pressure) to a component without touching that component, and be able to layer several such
behaviors.

**Composition.** Nest to stack; the outermost `wrap` sees requests first. Order is semantically
meaningful — e.g. a latency gate *outside* a throttle means throttled requests still paid the latency,
and a chaos gate *outside* a throttle sees a load-independent population (used to demonstrate
orthogonality).

**Exercised by.** [Store Demo V2](README.store-demo-v2.md) (the whole edge is nested `wrap`s);
`core/component/InterfaceSpec.scala` proves 1:1, short-circuiting, control-event preservation, latency
accumulation, and full-stack one-terminal-outcome.

### Supporting types

- **`InterfaceSampler[S, Req, Resp]`** — what a gate *is*: a `ComponentSampler[S, Req,
  InterfaceOutcome[Req, Resp], Nothing]`. Implement `initialState`, `sample`, and (for stateful gates)
  `onTick`; return an `Emission` whose forward output is an `InterfaceOutcome`.
- **`InterfaceOutcome[+Req, +Resp]`** — `Admit(request)` or `Reject(response)`. Covariant, so both unify
  to `InterfaceOutcome[Req, Resp]`.

---

## The gates

All four are `InterfaceSampler`s in `core/component/gate/`, generic over `Req`/`Resp`, carrying no domain
knowledge beyond the response a rejection returns. They differ in *what drives the admit/reject decision*.

### `FlatThrottleGate`

**Purpose.** A hard per-tick rate cap.

**Signature.** `FlatThrottleGate[Req, Resp](capacityPerTick: Int, rejectResponse: Resp, latencyTicks: Double = 0.0)`

**Properties.**
- **Stateful, load-driven.** State is the count admitted this tick; `onTick` resets it to 0. Admits the
  first `capacityPerTick` per tick, rejects the rest.
- **Deterministic** — no RNG; the decision is a pure function of arrival order and the per-tick count.
- **Burst-sensitive.** Because it keys off the *instantaneous* per-tick count, a workload whose *mean*
  rate is under capacity **still throttles during bursts**.

**When to use.** The simplest rate limit; when you want a hard ceiling with no burst tolerance, or to
contrast against the token bucket.

**Composition.** Stateless w.r.t. other gates; place it wherever the rate ceiling should apply.

**Exercised by.** `core/component/gate/FlatThrottleGateSpec.scala`; the default rate limiter in
[Store Demo V2](README.store-demo-v2.md); the flat-cap side of the burst experiment and the throttle in
the orthogonality sweep (`StoreV2ExperimentsSpec.scala`).

### `LatencyGate`

**Purpose.** Add latency to every request — the pure admit-all decorator.

**Signature.** `LatencyGate[Req, Resp](latency: StatelessSampler[Double])`, with `LatencyGate.constant(latencyTicks)`.

**Properties.**
- **Never rejects** — exercises the interface's admit-only path.
- **Distribution-driven.** Latency (in fractional ticks) is drawn per request from a
  `StatelessSampler[Double]` — realistically `LogNormalSampler.constant(mu, sigma)`; constant is the
  named special case. Draws are clamped to `≥ 0`.
- **Time-varying capable.** State tracks the current tick (via `onTick`), so a sampler whose parameters
  vary with tick produces time-varying latency (load- or time-of-day-dependent).
- **Additive under stacking** — each latency gate contributes its delay, so response timing reflects the
  sum of the latencies a request passed through.

**When to use.** Model service/processing latency or network jitter; inject a latency distribution to see
its effect on tail behavior.

**Composition.** Stacks with any gate; being outermost means rejected requests still pay it.

**Exercised by.** `core/component/gate/LatencyGateSpec.scala` (distributional, tick-threaded,
negative-clamp); `InterfaceSpec.scala` proves accumulation across a stack; the latency stage in
[Store Demo V2](README.store-demo-v2.md).

### `TokenBucketGate`

**Purpose.** A rate limiter with burst tolerance.

**Signature.** `TokenBucketGate[Req, Resp](capacity: Double, refillPerTick: Double, rejectResponse: Resp, latencyTicks: Double = 0.0)`

**Properties.**
- **Stateful, load-driven, with memory.** One token per admitted request; `onTick` adds `refillPerTick`
  tokens, capped at `capacity`. The bucket **banks unused capacity during quiet ticks and spends it on a
  later burst**. Starts full.
- **Fractional tokens** — a `refillPerTick < 1` accumulates across ticks until a whole token is
  available (real limiters run at fractional rates).
- **Same ceiling, different behavior.** Long-run admission is refill-limited (the same average ceiling
  as a flat cap of `refillPerTick`), but under bursty load it throttles far less; under *sustained*
  overload it throttles like the flat cap. Its rejection *advantage* over a flat cap is bounded by
  `capacity`.
- **Deterministic** — no RNG.

**When to use.** The realistic rate limiter — when bursts should be absorbed rather than rejected, while
still enforcing an average throughput ceiling.

**Composition.** Drop-in alternative to `FlatThrottleGate` at the rate-limiting position.

**Exercised by.** `core/component/gate/TokenBucketGateSpec.scala` (the deterministic burst-vs-flat
experiment: 0 vs 13 rejects, bounded advantage under overload); the bucket side of the burst experiment
in `StoreV2ExperimentsSpec.scala` (0% vs. 52% on the same spiky traffic).

### `ChaosGate`

**Purpose.** Inject random failures — a load-*independent* rejection.

**Signature.** `ChaosGate[Req, Resp](fail: StatelessSampler[Boolean], rejectResponse: Resp, latencyTicks: Double = 0.0)`, with `ChaosGate.constant(p, rejectResponse)`.

**Properties.**
- **Independent per-request draw.** A `StatelessSampler[Boolean]` (reuse `BernoulliSampler`) decides
  fail-or-admit per request. The decision **does not depend on arrival volume** — unlike the throttle
  and bucket.
- **Time-varying capable.** State tracks the tick, so a probability that varies with tick models an
  incident window.
- **Orthogonal to rate limiting.** Because failure is independent of load, its rejection rate (as a
  fraction of the requests it sees) stays ≈ its probability while a throttle's rate climbs with load.
- **Stochastic but reproducible** — deterministic given the seed.

**When to use.** Model backend flakiness / random 503s; study resilience, or (paired with a rate limiter)
demonstrate that two rejection mechanisms are independent.

**Composition.** Place **outermost** to face a load-independent population (the clean orthogonality
setup); place inner to fail only admitted requests.

**Exercised by.** `core/component/gate/ChaosGateSpec.scala` (the orthogonality sweep: 503 flat ~10% while
429 climbs 0→65%); the chaos side of `StoreV2ExperimentsSpec.scala`.

---

## Foundations

The substrate the gates build on. Brief here — enough to make the gates' properties legible; the demo
guides show these in use.

- **`ComponentSampler[S, In, Out, Cons]`** (`core/component/SamplerContract.scala`) — the production
  function every component implements: `initialState`, `sample(in, state, rng): Emission[S, Out, Cons]`,
  and a defaulted `onTick(tick, state): S` for tick-boundary state (reset, decay, refill). An
  **`Emission`** carries the new state, one **`Scheduled`** forward output, and zero-or-more scheduled
  consumption facts; a **`Scheduled[E](event, delay)`** pairs a timeless payload with a latency in
  fractional ticks. Samplers speak only in delays — never absolute time.
- **`ScheduleReleaseTransducer`** (`core/component/ScheduleReleaseTransducer.scala`) — the generic
  machinery that turns a `ComponentSampler` into a running Pekko graph stage: it unwraps the envelope,
  runs the sampler, stamps each output's absolute time from its delay, buffers, **releases in time order
  at tick boundaries**, and summarizes post-horizon residue into its materialized `ComponentResult`.
- **The timed-event protocol** (`core/component/Timed.scala`, `stochastacy.sim`) — every wire element is
  a `Timed[E](event, eventTime, intraTick, usecase)` or a `TimedControlEvent` (`Tick` / `EndOfTime`);
  `TimedElement[X] = X | TimedControlEvent`. Streams are partitioned into tick windows and terminated by
  `EndOfTime`; the intra-tick model gives sub-tick ordering. This uniform envelope is what lets
  components chain adapter-free.
- **`Sampler[S, T]` + distribution samplers** (`core/sampler/`) — `sample(tick, rng, state): (T, S)`;
  `StatelessSampler[T] = Sampler[Unit, T]`, with `Sampler.stateless` / `Sampler.deterministic`
  constructors. Distribution samplers (`Poisson`, `Normal`, `LogNormal` + `.constant`, `Binomial`,
  `Uniform`, `Bernoulli`, `Constant`) are the values gates like `LatencyGate` and `ChaosGate` draw from.
- **`Statistic` / `Statistics[K]` / `Histogram`** (`core/stats/`) — a mergeable summary: additive
  moments plus a mergeable log-bucket histogram for quantiles. `combine` is **associative**, which is
  what makes per-tick, cross-window, and cross-trial aggregation a fold (pooled vs. across-trial).
- **`MonteCarlo` / `SeedSequence`** (`core/run/`) — `MonteCarlo.run(trialCount, masterSeed,
  parallelism)(seed => Future[R])` runs N trials with bounded, order-preserving parallelism;
  `SeedSequence.derive` fans a master seed into reproducible per-trial seeds — so results are identical
  for any parallelism.
- **`TickFraming`** (`core/stream/TickFraming.scala`) — frames a time-ordered event sequence into a
  protocol-correct `Tick`-windowed, `EndOfTime`-terminated stream (and the inverse).

Foundations are exercised by both the [Store Demo](README.store-demo.md) and
[Store Demo V2](README.store-demo-v2.md).

---

## Quick reference

| I want to… | Component |
|---|---|
| cap throughput hard | `FlatThrottleGate` |
| cap throughput but tolerate bursts | `TokenBucketGate` |
| add latency (constant or distributional) | `LatencyGate` |
| inject random failures | `ChaosGate` |
| put any gate on a component's edge | `Interface.wrap` |
| write my own gate | implement `InterfaceSampler` |

Gates share a rule of thumb: **load-driven** (throttle, bucket) reject based on *how many* requests
arrive; **independent** (chaos) rejects based on a per-request draw; **admit-all** (latency) never
rejects. Stacking them composes those behaviors, and a rejection anywhere in the stack is one terminal
outcome for that request.

## See also

- [Store Demo V2 — the gated edge](README.store-demo-v2.md) — the gates as a worked example.
- [Store Demo](README.store-demo.md) — the foundations (sampler, transducer, stats, Monte Carlo) in use.
