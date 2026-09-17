# Component Catalog — Engineer's Guide

A catalog of the reusable building blocks in `stochastacy.core`: the **interface component** and its
**gates** (latency, throttling, burst, continuous-time rate limiting, chaos), **circuits** (several components
wired into one — feedback loops included), plus the **foundations** they are built on. Unlike the
demo guides ([Store Demo](README.store-demo.md), [Store Demo V2](README.store-demo-v2.md),
[MM1 demo](README.mm1-demo.md)) — which
explain what a particular simulation *shows* and how to run it — this catalog describes the parts as
**building blocks you can drop into your own simulator**: what each is, the properties it guarantees,
when to reach for it, and how the pieces compose.

Scope is the **domain-agnostic core**. The AWS/DynamoDB simulator components are catalogued separately, in
the [AWS component catalog](aws-component-catalog.md).

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
- **In-band rejection → 1:1 integrity.** A rejection is an ordinary `Resp` value — the `response` of the
  gate's `Reject(request, response)` — merged back into the response stream, so **every request yields
  exactly one terminal response** — served or rejected — and no new response type is needed. (The
  rejection's `request` is not emitted here: under `wrap` a rejection *is* the response. It matters when a
  gate is a [circuit node](#gates-as-circuit-nodes).)
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
- **`InterfaceOutcome[+Req, +Resp]`** — `Admit(request)` or `Reject(request, response)`. Covariant, so both
  unify to `InterfaceOutcome[Req, Resp]`. A rejection carries its **request** as well as its response, so
  anything that receives it out of band — a client node in a circuit's retry loop — knows *which* request
  was rejected and can retry it, even when every rejection shares one constant response value.

---

## The gates

All five are `InterfaceSampler`s in `core/component/gate/`, generic over `Req`/`Resp`, carrying no domain
knowledge beyond the response a rejection returns. They differ in *what drives the admit/reject decision*.
Every rejecting gate emits `Reject(request, rejectResponse)` — the rejection always carries its request. Each
can decorate an edge under [`Interface.wrap`](#interfacewrap) or sit inside a circuit as a node (see
[Gates as circuit nodes](#gates-as-circuit-nodes)).

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
- **Tick-granular refill.** Tokens arrive only at tick boundaries, so a bucket drained early in a tick stays
  empty for the rest of that tick however much simulated time passes. That is harmless when requests are
  spread across ticks, but it is the *tick* acting as the rate limit when requests arrive in sub-tick bursts —
  as they do in a feedback loop closed inside a circuit. There, use
  [`ContinuousTokenBucketGate`](#continuoustokenbucketgate).

**When to use.** The realistic rate limiter — when bursts should be absorbed rather than rejected, while
still enforcing an average throughput ceiling — at tick resolution.

**Composition.** Drop-in alternative to `FlatThrottleGate` at the rate-limiting position.

**Exercised by.** `core/component/gate/TokenBucketGateSpec.scala` (the deterministic burst-vs-flat
experiment: 0 vs 13 rejects, bounded advantage under overload); the bucket side of the burst experiment
in `StoreV2ExperimentsSpec.scala` (0% vs. 52% on the same spiky traffic).

### `ContinuousTokenBucketGate`

**Purpose.** A token-bucket rate limiter refilled in **continuous time** — the form a feedback loop needs.

**Signature.** `ContinuousTokenBucketGate[Req, Resp](capacity: Double, refillPerTick: Double, rejectResponse: Resp, latencyTicks: Double = 0.0)`

**Properties.**
- **Refills from elapsed conceptual time.** Tokens accrue at `refillPerTick` per tick of time elapsed since
  the previous request — read from the input's `at` — capped at `capacity`. A request half a tick after a
  drain sees half a tick's refill. There is no `onTick`: the tick boundary plays no part in the decision.
- **The bound.** Over **any** interval of length `T`, admissions never exceed `capacity + refillPerTick × T`.
- **Starts full; fractional tokens.** Nothing accrues before the first request (there is no earlier time to
  accrue from); a request finding less than one whole token is rejected.
- **Deterministic** — no RNG.

**When to use.** Any rate limit whose requests arrive at sub-tick spacing and whose decision should depend
on *when* they arrive: a gate inside a circuit (a client retrying a rejection within the same tick), or any
workload where ticks are coarse relative to the rate being limited. Prefer `TokenBucketGate` only when
tick-resolution refill is the behavior you mean to model.

**Composition.** Drop-in alternative to `TokenBucketGate` — the same constructor shape — under
`Interface.wrap` or as a circuit node. Because it reads `at`, it relies on its inputs being delivered in
conceptual-time order within a tick: a circuit guarantees that; a plain pipeline does so only when its source
is sorted within each tick (see the [rubric](#when-a-circuit-is-required)).

**Exercised by.** `core/component/gate/ContinuousTokenBucketGateSpec.scala` (mid-tick refill — where
`TokenBucketGate` stays empty on the same inputs; the `capacity + refill × T` bound checked over every
interval of an overloaded run; start-full and capped idle accrual; the rejection carrying its request);
`core/component/circuit/GateCircuitLoopSpec.scala` (a retry loop through the gate).

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

### Gates as circuit nodes

Under `Interface.wrap` a rejection goes straight back to the caller as its response, and nothing inside the
simulation reacts to it. When something *does* react — a client that backs off and retries a throttled
request — the rejection has to travel back to that client, which makes a cycle, and a cycle is a
[circuit](#circuits)'s job. Every gate is an ordinary sampler, so it can be a circuit node as it is; the
builder adds sugar for wiring both outcomes at once:

```scala
val (circuit, (client, gate)) = Circuit.buildWith[Req, Nothing, Fact] { b =>
  val client = b.node("client", new RetryingClient(cfg))       // loopback: fb accepts Resp | Reject[Req, Resp]
  val server = b.node("server", new Server(cfg))
  val gate   = b.gate("throttle", new ContinuousTokenBucketGate[Req, Resp](10, 5, Throttled))(
                 admitTo = server.in, rejectTo = client.fb)    // Admit(r) → server gets r; Reject(r, resp) → client
  b.input(client.in)
  b.connect(client.out, gate.in)
  b.connect(server.out, client.fb)
  b.wiretap(gate.out) { case Reject(_, _) => Fact.Throttled } // throttle metrics without a gate metric plane
  b.consumption(server.consumption)
  (client, gate)
}
```

- **`b.gate(name, gate)(admitTo, rejectTo)`** declares the gate node and wires both outcomes: admitted requests
  arrive at `admitTo` **unwrapped**, and the whole `Reject(request, response)` arrives at `rejectTo` — so the
  receiver knows which request to retry. **`b.gateVia(…)(admitTo, rejectTo)(rejectAs)`** maps the rejection
  first (e.g. into a domain retry event). Both return the gate's handle.
- **Throttle metrics** come from a [wiretap](#circuitbuilder) on the gate's outcome plane — gates still have
  no consumption plane, and a gate node needs no consumption routing.
- **Per-tick gates keep their per-tick meaning.** A circuit calls `onTick` at every boundary, exactly as the
  transducer does, so `FlatThrottleGate` still caps each tick window and `TokenBucketGate` still refills at
  boundaries. Inside a loop that closes within a tick, that is usually not the rate limit you mean — prefer
  [`ContinuousTokenBucketGate`](#continuoustokenbucketgate). A per-tick gate's final state has just been
  reset by the last boundary, so read its admissions from its outputs (or a wiretap), not from `stateOf`.

**Exercised by.** `core/component/circuit/GateCircuitLoopSpec.scala` — each shipped gate in a retry loop: the
client retries exactly the requests a flat throttle, a token bucket, or a continuous token bucket rejected;
retries every chaos rejection once and then gives up; never retries behind an admit-only latency gate.
`CircuitTailgateShapesSpec.scala` (a throttle's outcomes split between server and client) and
`CircuitBuilderSpec.scala` (`gateVia` + `buildWith` handles).

---

## Circuits

A Pekko graph composes components as **stages**, and a stage finishes a tick window only once its inputs
have reached the next `Tick`. That is exactly right for a pipeline, where data flows one way. It is wrong for
a **loop** — a response that produces the next request, a rejection that produces a retry — because around
a cycle every stage waits on itself. A **circuit** hosts several samplers as the **nodes** of a single stage,
wired freely with **cycles allowed**, and runs them from an internal calendar ordered by conceptual time. A
loop closes exactly, even inside one tick with zero delay; from the outside the circuit is an ordinary
component.

### When a circuit is required

Work through these questions in order for the component graph you are designing.

1. **Is there a cycle?** A cycle is any path by which a component's output eventually becomes an input to
   that same component, or to a component upstream of it: a response that triggers a follow-up request, a
   rejection or timeout that triggers a retry, a replicated write that returns to its region.
   **No →** wire components directly — pipelines, `Interface.wrap` stacks, fan-out and merges — unless
   question 4 applies.
2. **Can any trip around the cycle take less than one tick?** A zero-latency rejection, a short or jittered
   backoff, a fast service, an immediate next request, think time drawn from a distribution that reaches
   below one tick. **Yes → you must use a circuit.** Direct wiring cannot express the loop:
   - a cycle of ordinary component stages **deadlocks** — each waits for its upstream's next `Tick`, and
     around a cycle each stage is its own upstream;
   - the loopback stage (`ScheduleReleaseTransducer.loopbackComponentOf`) breaks that deadlock by forwarding
     its tap ticks eagerly, but a tap emitted **from feedback** must land in a *later* tick than the fed-back
     item — the stage fails otherwise — so a trip that comes back around within the tick cannot continue;
   - and even the part it does accept is absorbed in **wire-arrival order**, not conceptual-time order.
3. **Is every trip around the cycle at least one tick *by construction*?** (Guaranteed by the model — a lag
   clamped to at least one tick — not merely typical of a distribution.) Then ask: **does any component on
   the cycle make decisions that depend on the order of its inputs within a tick?** Order-sensitive
   components include a per-tick counter (`FlatThrottleGate`), a token bucket, a FIFO queue, and anything that
   reads `at` (`ContinuousTokenBucketGate`, a time-weighted integral). **Yes → you absolutely should use a
   circuit.** The loopback stage will run, but it absorbs a window's primary and fed-back inputs in arrival
   order, so which request gets throttled or queued first becomes an accident of stream scheduling rather
   than of simulated time. **No →** direct wiring with `loopbackComponentOf` is correct. The worked example is
   the AWS module's [`GlobalTable`](aws-component-catalog.md#globaltable): the replication coordinator
   clamps every link lag to at least one tick, and a replica's replay of replicated writes only accumulates
   billing and storage totals, which do not depend on the order a window's writes were applied.
4. **No cycle, but does an order-sensitive component consume a stream whose events interleave within a
   tick?** Streams merged by `MergeTimedEventGraph` (including the response rejoin inside `Interface.wrap`)
   interleave a window's events in no particular order, and a source that is not sorted within each tick
   delivers them out of order to begin with. A component stage absorbs inputs in arrival order. **If the
   result depends on that order → you absolutely should use a circuit** (it dispatches in conceptual-time
   order). If it does not — the component only accumulates totals, or tick-resolution behavior is what you
   mean to model — wire directly.

| The graph has… | Verdict |
|---|---|
| a cycle, any trip around which can take less than one tick | **must** use a circuit |
| a cycle of at least one tick by construction, with an order-sensitive component on it | **absolutely should** use a circuit |
| no cycle, but an order-sensitive component fed a merged or within-tick-unsorted stream | **absolutely should** use a circuit |
| a cycle of at least one tick by construction, with only order-insensitive components (e.g. `GlobalTable`) | **may wire directly** with `loopbackComponentOf` |
| no cycle: pipelines, `Interface.wrap` stacks, fan-out, merges into order-insensitive components | **may wire directly** |

**Why circuits are not the default.** A circuit is the exact mechanism, but it has costs, so reach for it
when the rubric calls for it rather than everywhere:
- **One stage.** All of a circuit's nodes run in one Pekko stage, with no parallelism between them.
- **One way in.** A circuit has one external inlet, one forward outlet and one consumption outlet.
- **Internal edges are private.** What passes between nodes is observable only through a
  [wiretap](#circuitbuilder).
- **Inputs are dispatched in conceptual-time order.** For a source sorted within each tick this is identical
  to a component stage; for an unsorted source a stateful node can legitimately behave differently than under
  `componentOf` (that is the point of question 4, but it is a difference).
- **Loops between circuits are not supported.** A loop must live inside one circuit; a cycle between two
  circuits, or between a circuit and other stages, is subject to questions 2 and 3 like any other stages.

### `Circuit`

**Purpose.** Describe several components and the wiring among them — cycles allowed — and run them as one
component.

**Signature.**
```scala
Circuit.build[In, Out, Cons](body: CircuitBuilder[In, Out, Cons] => Any): Circuit[In, Out, Cons]
Circuit.buildWith[In, Out, Cons] { b => …; handles }: (Circuit[In, Out, Cons], H)   // keep node handles

Circuit.componentOf(circuit: Circuit[In, Out, Cons], rng: UniformRandomProvider)
  : Graph[FanOutShape2[Timed[In], Timed[Out], Timed[Cons]], Future[CircuitResult]]
```
*(element types abbreviated; the wire carries `TimedElement[Timed[…]]`.)* The [MM1 demo](README.mm1-demo.md)'s
whole circuit:
```scala
Circuit.buildWith[Session, Nothing, MM1Fact] { b =>
  val client = b.node("client", new ClientNode(config))   // loopback: in Session, fb PageResponse, out PageRequest
  val server = b.node("server", new ServerNode(config))   // plain:    in PageRequest, out PageResponse
  b.input(client.in)
  b.connect(client.out, server.in)
  b.connect(server.out, client.fb)                        // the loop
  b.consumption(client.consumption)
  b.consumption(server.consumption)
  Handles(client, server)
}
```

**Properties.**
- **An ordinary component outside.** `componentOf` presents the same `FanOutShape2` as
  `ScheduleReleaseTransducer.componentOf`, so a circuit works under `Interface.wrap`, `TrialRunner`, and any
  custom graph.
- **Exact conceptual-time dispatch.** Every event waits on a calendar ordered by `(tick, intraTick, seq)`,
  where `seq` is a single counter incremented on every enqueue, so ties resolve in arrival and emission
  order — deterministically. Routes add no delay: an emission's own `Scheduled` delay is its latency.
- **Loops close within a tick.** When `Tick(t)` arrives the circuit **dispatches** every event earlier than
  `t`; a routed item that lands before `t` joins the same pass. It then **releases** outlet items earlier than
  `t` in time order, calls every node's **`onTick(t)`** in declaration order (boundary facts are stamped at
  `(t, 0)` plus their delay), and **forwards** `Tick(t)`. That is the transducer's own per-tick order, so a
  **one-node circuit is output-identical to `componentOf`** for input sorted within each tick.
- **A reusable blueprint with per-node RNGs.** A `Circuit` is immutable; each `componentOf` call builds
  fresh nodes. A seed is drawn from `rng` for **every** node in declaration order, and a node uses its pinned
  `rngSeed` if it has one — so pinning one node never shifts another's random stream.
- **Validated when built.** `build` throws `IllegalArgumentException` if the circuit has no nodes or no
  input route, repeats a node name, has a node nothing routes into (it could never run), or has a node with
  real consumption that is neither routed nor explicitly ignored. Whether consumption is "real" is decided at
  compile time — a node whose consumption type is `Nothing`, such as a gate, needs no routing.
- **Fails loudly while running.** The stage — and its materialized future — fails on an emission with a
  negative delay, more than `maxEventsPerWindow` dispatches in one tick window (default 10 M: a runaway
  zero-delay cycle), an exception from a node (reported with the node's name and port), or an input stamped
  before the open tick.

**When to use.** When the [rubric](#when-a-circuit-is-required) says so: a loop that can close within a
tick, or order-sensitive components whose inputs must be taken in simulated-time order.

**Composition.** Nodes are any `LoopbackComponentSampler` (an ordinary `ComponentSampler` included), so
existing components — the core gates, the store samplers, AWS's `DynamoDbTableSampler` — are circuit nodes
without modification. The circuit composes outward like any component; loops stay inside it.

**Exercised by.** `core/component/circuit/CircuitStageSpec.scala` (a self-loop dispatched between primary
inputs by conceptual time; zero-delay feedback; loops across tick boundaries; `onTick` ordering; residue;
the runaway, negative-delay and node-exception failures; conceptual-time vs arrival order; determinism);
`CircuitAnchorSpec.scala` and `aws/…/CircuitAnchorDynamoDbSpec.scala` (a one-node circuit matching
`componentOf` byte for byte — on toy samplers and on the real DynamoDB table sampler); `CircuitInteropSpec.scala`
(under `Interface.wrap` and `TrialRunner`); `CircuitBuilderSpec.scala`; `GateCircuitLoopSpec.scala`; and the
[MM1 demo](README.mm1-demo.md), whose theory baseline checks a circuit against closed-form queueing results.

### `CircuitBuilder`

**Purpose.** Declare a circuit's nodes and wiring, type-checked against the nodes' own sampler types.

**Signature.** The `b` inside `Circuit.build { b => … }`:

| Call | Wires |
|---|---|
| `node(name, sampler, rngSeed = None)` | declares a node; returns its typed [handle](#circuitnode) |
| `connect(plane, port)` / `connectVia(plane, port)(pf)` | a node's `out` or `taps` into a node's `in` or `fb` |
| `input(port)` / `inputVia(port)(pf)` | the circuit's external input into a node port |
| `output(plane)` / `outputVia(plane)(pf)` | a node's `out` or `taps` onto the circuit's forward outlet |
| `consumption(plane)` / `consumptionVia(plane)(pf)` | a node's consumption facts onto the circuit's consumption outlet |
| `ignore(plane)` | drops a node's consumption facts, explicitly |
| `wiretap(plane)(pf)` | **copies** a node's `out` or `taps` events onto the consumption outlet |
| `gate(name, gate)(admitTo, rejectTo)` / `gateVia(…)(…)(rejectAs)` | a [gate node](#gates-as-circuit-nodes) with both outcomes wired |
| `maxEventsPerWindow(n)` | the runaway-cycle cap |

**Properties.**
- **Transform and filter in one.** Each `…Via` form takes a partial function: it converts every item it is
  defined at and filters out the rest (`{ case Admit(r) => r }`).
- **Fan-out by declaration.** An emission is offered to every route on its plane, in declaration order.
- **Wiretaps keep internal interactions observable.** A wiretap copies events, in time order, onto the
  consumption outlet — the way to measure what passes between nodes. A copy does not count as routing: a
  plane that is only wiretapped still reports its emissions as unrouted.
- **Mistakes types can't catch fail at the call.** A port or plane from another builder, or routing a
  consumption plane that was ignored (or ignoring one that was routed), throws `IllegalArgumentException`
  immediately; a builder cannot be reused after `build` returns.

**Exercised by.** `core/component/circuit/CircuitBuilderSpec.scala` (a two-node paging loop with typed final
states; every wiring form; wiretaps in time order and not counted as routing; malformed circuits rejected;
`Nothing` consumption needing no routing; per-node RNG derivation with a pinned seed shifting no other node;
`gateVia` with `buildWith`; blueprint reuse).

### `CircuitNode`

**Purpose.** A typed handle to one declared node — the thing you wire and, after a run, read the state of.

**Signature.**
```scala
final class CircuitNode[S, In, Fb, Out, Cons, Tap]:
  val name:        String
  val in:          InPort[In]              // → sample
  val fb:          FbPort[Fb]              // → onFeedback
  val out:         OutPlane[Out]           // forward output
  val taps:        TapPlane[Tap]           // loop-out taps
  val consumption: ConsumptionPlane[Cons]  // consumption facts
```

**Properties.**
- **Wrong wiring does not compile.** Ports (`NodePort[-A]`) are contravariant and emission planes
  (`EmissionPlane[+A]`) covariant, so `connect(plane, port)` type-checks exactly when everything the plane
  emits is something the port accepts — a plane of a narrower type can feed a broader port.
- **A plain component's feedback port is closed.** A `ComponentSampler` has `Fb = Nothing`, so its
  `fb: FbPort[Nothing]` accepts nothing.
- **Consumption stays metric.** `ConsumptionPlane` is deliberately not an emission plane: consumption facts
  can leave the circuit, but cannot drive another node.

**Exercised by.** `CircuitBuilderSpec.scala` and every circuit spec above.

### `CircuitResult`

**Purpose.** The materialized value of a circuit run: final states, residue, and routing diagnostics.

**Signature.**
```scala
final case class CircuitResult(
  nodeStates:     Vector[Any],             // declaration order
  residue:        CircuitResidue,          // (calendarEvents, forwardOutputs, consumptions)
  unrouted:       Vector[UnroutedCount],   // per node plane, non-zero only
  unroutedInputs: Long):
  def stateOf[S](node: CircuitNode[S, ?, ?, ?, ?, ?]): S
```

**Properties.**
- **Typed final state.** `stateOf(handle)` returns a node's final state at its sampler's state type. Keep the
  handles by returning them from `Circuit.buildWith`.
- **Residue is counted, never emitted.** Calendar events and outlet items scheduled past the horizon are
  summarized, as the transducer summarizes its own residue.
- **Unrouted is a diagnostic, not an error.** Emissions no route accepted are dropped and counted per node
  plane, and external inputs no route accepted are counted too — filtering by partial function is a
  legitimate use of routes.

**Exercised by.** `CircuitStageSpec.scala` (residue; unrouted counts); `CircuitBuilderSpec.scala` (`stateOf`).

### Engine rules worth knowing

- **A boundary fact from the last window is always residue.** A node that summarizes a window in `onTick`
  stamps the fact at the next boundary. The window closed by the final flush tick has no later boundary, so
  its fact is post-horizon residue — by design, in circuits and transducers alike. Anything integrated per
  window should divide by the windows it actually **received**, never by the nominal tick count; the MM1 demo
  measured a (M−1)/M bias from doing otherwise (see its [measurement design](README.mm1-demo.md#4-measurement-design)).
- **Order is only guaranteed inside a circuit.** A circuit dispatches by conceptual time; a Pekko stage
  absorbs its inputs in arrival order and releases its outputs in time order at tick boundaries.
- **Sub-tick timing is real.** Inside a circuit, `at` differs between events in the same tick, and delays
  below one tick matter. Components that should respond to that — a rate limit, a FIFO queue — must read
  `at`, not the tick.

---

## Foundations

The substrate the gates build on. Brief here — enough to make the gates' properties legible; the demo
guides show these in use.

- **`ComponentSampler[S, In, Out, Cons]`** (`core/component/SamplerContract.scala`) — the production
  function every component implements: `initialState`, `sample(in, at, state, rng): Emission[S, Out, Cons]`,
  and a defaulted `onTick(tick, state): TickEmission[S, Cons]` for tick-boundary state plus scheduled
  consumption facts. `at` is the input's conceptual time — a **`SimInstant(tick, intraTick)`**
  (`stochastacy.sim`) — for behavior that depends on *when* an input happened. An **`Emission`** carries the
  new state, one **`Scheduled`** forward output, and zero-or-more scheduled consumption facts; a
  **`Scheduled[E](event, delay)`** pairs a timeless payload with a latency in fractional ticks. Samplers
  schedule outputs only by delay — never by absolute time.
- **`LoopbackComponentSampler[S, In, Fb, Out, Cons, Tap]`** (same file) — the **feedback-capable** base
  `ComponentSampler` extends (with `Fb` / `Tap` pinned to `Nothing`, so an ordinary component is
  byte-identical). It adds a second input — `onFeedback(fb, at, state, rng): FeedbackEmission[S, Out, Cons, Tap]`
  — and a `Tap` output channel on the emission (`LoopbackEmission`, of which `Emission` is the `Tap = Nothing`
  alias). A **`FeedbackEmission`** carries the new state, an **optional** forward output (a fed-back item may
  answer nothing, or trigger a request such as a retry), consumption facts, and taps. This is what lets a
  component sit in a **cycle**: it emits taps out one edge and consumes feedback on another. In the loopback
  stage a tap emitted *from feedback* must be stamped at a later tick than the fed-back item (the stage fails
  otherwise), and feedback within a window is absorbed in arrival order. (AWS's `GlobalTable` uses it — a
  table taps admitted writes and applies replicated writes via `onFeedback`, answering nothing.) The same
  sampler can also be a [circuit](#circuits) node; the [rubric](#when-a-circuit-is-required) says when the
  loopback stage is enough and when a circuit is required.
- **`ScheduleReleaseTransducer`** (`core/component/ScheduleReleaseTransducer.scala`) — the generic
  machinery that turns a `ComponentSampler` into a running Pekko graph stage: it unwraps the envelope,
  runs the sampler, stamps each output's absolute time from its delay, buffers, **releases in time order
  at tick boundaries**, and summarizes post-horizon residue into its materialized `ComponentResult`. Its
  `loopbackComponentOf` variant adds a **feedback inlet** and a **tap outlet** (2-in / 3-out); the tap
  forwards each `Tick` **eagerly**, which is the invariant that makes a region↔coordinator cycle
  deadlock-free.
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
- **`TrialRunner` / `MonteCarlo` / `SeedSequence`** (`core/run/`) — `TrialRunner.run(source, component,
  consumptionSink)` runs one trial of any component, returning its materialized result (a `ComponentResult`,
  a `CircuitResult`, …) beside the sink's value; `MonteCarlo.run(trialCount, masterSeed,
  parallelism)(seed => Future[R])` runs N trials with bounded, order-preserving parallelism;
  `SeedSequence.derive` fans a master seed into reproducible per-trial seeds — so results are identical
  for any parallelism. Give **every ensemble its own master seed**: ensembles that share one replay the same
  noise, so their errors agree and look like a systematic effect.
- **`TickFraming`** (`core/stream/TickFraming.scala`) — frames a time-ordered event sequence into a
  protocol-correct `Tick`-windowed, `EndOfTime`-terminated stream (and the inverse).

Foundations are exercised by the [Store Demo](README.store-demo.md),
[Store Demo V2](README.store-demo-v2.md), and the [MM1 demo](README.mm1-demo.md).

---

## Quick reference

| I want to… | Component |
|---|---|
| cap throughput hard | `FlatThrottleGate` |
| cap throughput but tolerate bursts | `TokenBucketGate` |
| rate-limit requests that arrive at sub-tick spacing (e.g. inside a loop) | `ContinuousTokenBucketGate` |
| add latency (constant or distributional) | `LatencyGate` |
| inject random failures | `ChaosGate` |
| put any gate on a component's edge | `Interface.wrap` |
| write my own gate | implement `InterfaceSampler` |
| decide between a circuit and direct wiring | the [rubric](#when-a-circuit-is-required) |
| close a feedback loop — even within a tick | a [`Circuit`](#circuit) |
| retry the request a gate rejected | a [gate node](#gates-as-circuit-nodes) wired with `b.gate`; the `Reject` carries the request |
| observe what passes between circuit nodes | `CircuitBuilder.wiretap` |
| read a circuit node's final state | `CircuitResult.stateOf(handle)`, handles from `Circuit.buildWith` |

Gates share a rule of thumb: **load-driven** (throttle, both buckets) reject based on *how many* requests
arrive; **independent** (chaos) rejects based on a per-request draw; **admit-all** (latency) never
rejects. Stacking them composes those behaviors, and a rejection anywhere in the stack is one terminal
outcome for that request.

## See also

- [Store Demo V2 — the gated edge](README.store-demo-v2.md) — the gates as a worked example.
- [MM1 demo — a closed loop checked against queueing theory](README.mm1-demo.md) — a circuit as a worked
  example.
- [Store Demo](README.store-demo.md) — the foundations (sampler, transducer, stats, Monte Carlo) in use.
- [AWS component catalog](aws-component-catalog.md) — the DynamoDB components built on this core.
