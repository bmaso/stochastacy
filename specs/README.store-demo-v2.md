# Store Simulator V2 (the gated edge) — Engineer's Guide

A second demo built on the same fictional store, but this one is about the **edge in front of the
datastore**. Where the [original store demo](README.store-demo.md) modeled request cost inside the
datastore, Store Demo V2 puts the datastore behind a **composable stack of admission/rejection gates**
— latency, rate limiting, and random failure — each a reusable `stochastacy.core` *interface component*,
and shows how those gates behave and compose.

The example lives in `examples/src/main/scala/stochastacy/examples/store/v2/`. It is all new code: it
reuses the original store demo's datastore, protocol, and workload **by import only** — the original
files are untouched.

---

## 1. What the demo demonstrates

The datastore is the same product-catalog service. In front of it sits an **interface stack**:

```
request → [ latency ] → [ rate limiter ] → [ chaos ] → datastore → response
```

Each bracket is an `InterfaceSampler` wrapped onto the datastore by `Interface.wrap`. A gate either
**admits** a request (it flows on) or **rejects** it with an in-band response — a rejection is just a
`StoreResponse`, so every request still yields exactly one terminal outcome. The demo surfaces five
behaviors:

### 1a. Latency accrual
`LatencyGate` admits everything and adds a per-request latency **drawn from a distribution** (a
`LogNormalSampler` in the demo; constant is a special case). Stacked gates each contribute their delay,
so a response's timing reflects the sum of the latencies it passed through.

### 1b. Load-driven throttling
`FlatThrottleGate` is a hard per-tick cap: the first *N* requests in a tick are admitted (429 for the
rest). Because it keys off the instantaneous per-tick count, a workload whose *mean* is under capacity
still throttles during bursts.

### 1c. Burst tolerance (the headline gate contrast)
`TokenBucketGate` is the same average ceiling as a flat cap, but it **banks unused capacity during quiet
ticks and spends it on a later spike**. Same throughput limit, very different behavior under bursty
load — see the experiment in §2.

### 1d. Load-independent chaos
`ChaosGate` is an independent per-request failure draw (503) — it does **not** depend on arrival volume.
It models "the service just fails sometimes," and it is the mechanism that plays against the rate limiter
to show orthogonality (§2).

### 1e. Composition and integrity
The gates stack by nesting `Interface.wrap`, and the stack preserves **1:1 integrity**: every request
ends as exactly one of *served* / *429 throttled* / *503 chaos*.

### Aside — flat cap vs. token bucket
Both cap throughput at the same average rate, and both reject with a 429. They differ only in *memory*:
the flat cap has none (a quiet tick buys you nothing), while the token bucket accumulates tokens (up to
its capacity) during quiet ticks and can spend them to absorb a burst. So under identical bursty traffic
they reject very differently — invisible if you only test at a steady rate, which is exactly why a
simulator that can replay byte-identical traffic through both is the right tool.

---

## 2. Results

Running `StoreV2Demo` (8 trials, capstone workload, `latency LogNormal(median 0.05) → FlatThrottle(18) →
chaos 2%`) prints:

```
Store Demo V2 — Monte Carlo summary (8 trials)
  gate outcome rates (of all requests):
    served:          75.6%
    throttled (429): 23.0%
    chaos (503):     1.4%
  throttled (429) by use-case: create=22.7%, get=23.5%, list.keyset=23.5%, list.offset=22.4%, report=22.9%
  chaos (503) by use-case:     create=1.4%, get=1.4%, list.keyset=1.4%, list.offset=1.2%, report=1.2%
```

Offered load (~23/tick) exceeds the cap (18), so ~23% is throttled; the 2% chaos gate (innermost, seeing
only admitted requests) rejects ~1.4% of all requests. Two focused experiments (in
`StoreV2ExperimentsSpec`) demonstrate the distinctive gate behaviors:

### Burst tolerance — token bucket vs. flat cap (goal 1c)
Identical spiky traffic (every 10th tick spikes to 30 requests, else 2 — mean 4.8/tick) through two rate
limiters of the *same* average rate (cap 5 / refill 5):

| rate limiter | throttled (429) rate |
|---|---|
| `FlatThrottle(5)` | **52.1%** — rejects every spike's excess |
| `TokenBucket(capacity 30, refill 5)` | **0%** — banks quiet-tick slack, absorbs every spike |

Same throughput ceiling, opposite outcome under bursts. Policy, not just capacity, governs throttling.

### Orthogonality — chaos vs. throttling (goal 1d)
Sweeping offered load through a **chaos-outermost** `chaos(10%) → throttle(cap 5)` edge:

| offered load (get/tick) | chaos (503) | throttle (429) |
|---|---|---|
| 3  | 10.8% | 3.9%  |
| 8  | 10.5% | 32.3% |
| 20 | 9.8%  | 65.5% |

The 503 rate barely moves across a ~7× load increase while the 429 rate climbs from ~4% to ~66% — the two
mechanisms are independent: throttling responds to load, chaos does not.

---

## 3. Running the demo

No external services required — the demo exports JSONL + a text summary.

```bash
sbt 'examples/runMain stochastacy.examples.store.v2.StoreV2Demo --output /tmp/store-v2-demo.jsonl --trials 8 --ticks 200 --window 50 --seed 1'
```

Parameters (all `--key value`, optional; defaults in parentheses): `--output` (`/tmp/store-v2-demo.jsonl`),
`--seed` (`1`), `--ticks` (`200`), `--trials` (`8`), `--window` (`50`, stats window size), `--parallelism`
(`4`, does not affect results). The **edge itself** (latency distribution, rate limiter, chaos rate) is
configured in `StoreV2Demo.scala` via `EdgeConfig`; edit it to explore other regimes.

The JSONL carries the same two record kinds as the original demo (`pooled` and `acrossTrials`), keyed by
`(usecase, metric, window)`; the per-gate metrics are `outcome.served` / `outcome.throttled` /
`outcome.chaos` (each 0/1, so a mean is a rate).

To run the experiment assertions:

```bash
sbt 'examples/testOnly stochastacy.examples.store.v2.StoreV2ExperimentsSpec'
```

---

## 4. Internals

### 4.1 The gate stack
Each gate is an `InterfaceSampler[S, StoreRequest, StoreResponse]` — a `ComponentSampler` whose forward
output is `Admit(request)` or `Reject(response)` and whose consumption is fixed to `Nothing` (gates emit
no metric plane). `Interface.wrap(downstream, gate, rng)` produces a **shape- and materialized-value-
preserving** decorator: the wrapped component presents the same `StoreRequest → StoreResponse` interface
as the datastore, so wraps nest and gates stack. `EdgeConfig.gates` turns the structured config into the
outermost-first stack `latency → rate-limiter → chaos`, and `StoreV2TrialRunner` folds it over the
datastore with `Interface.wrap`.

### 4.2 Reporting from the response stream (no metric plane)
Because gates emit no observations, the per-gate rates are recovered by **classifying the in-band
terminal outcome of every request** from the response stream: `ErrorResult("throttled")` → 429,
`ErrorResult("unavailable")` → 503, anything else → served. The runner folds those (as 0/1 `outcome.*`
metrics) *and* the datastore's own consumption into one windowed `Statistics[(usecase, metric, window)]`,
so a metric's mean is its rate and its count is the request total. `StoreV2Report` summarizes them;
`StoreV2MonteCarloRunner` aggregates across trials (pooled and run-to-run).

### 4.3 Runner entry points
`StoreV2TrialRunner` offers three ways in, all sharing one graph builder: `run(EdgeConfig, …)` (the demo),
`runGates(Seq[gate], …)` (experiments needing a specific stack, e.g. chaos-outermost), and
`runArrivals(Seq[gate], arrivals, …)` (experiments needing *byte-identical* traffic, fed by
`SpikeWorkload`). Everything is deterministic given the seed and independent of parallelism.

---

## Source map

| concern | file |
|---|---|
| edge config → gate stack | `EdgeConfig.scala` |
| trial runner (3 entry points) | `StoreV2TrialRunner.scala`, `StoreV2TrialResult.scala` |
| Monte Carlo | `StoreV2MonteCarloRunner.scala` |
| reporting | `StoreV2Report.scala` |
| bridge / spike workload | `StoreV2Demo.scala`, `SpikeWorkload.scala` |
| experiments | `test/.../store/v2/StoreV2ExperimentsSpec.scala` |
| the gates (reusable core) | `core/component/Interface.scala`, `core/component/gate/*` |

*The gates are reusable `stochastacy.core` components; for their contracts and properties as building
blocks (independent of this demo), see the component catalog.*
