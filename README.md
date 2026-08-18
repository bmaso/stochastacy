# stochastacy

**Build Monte Carlo simulators of distributed software systems — and answer performance, scaling, and
cost questions before you build, load-test, or pay for the real thing.**

stochastacy is a Scala 3 / [Apache Pekko](https://pekko.apache.org/) Streams library for modeling a
distributed system as a graph of stochastic components, driving it with a declarative workload, and
running it thousands of times to see not just the *average* outcome but the *distribution* of outcomes —
latency tails, throttle rates, resource consumption, and cloud spend — with their run-to-run variance
made explicit.

---

## Why simulate?

A real system gives you one run of the world at a time, and a load test is expensive to build and
rarely repeatable under identical conditions. A simulator changes the economics:

- **Experiment freely.** Ask "what happens at 5× load?", "what if this table is 10× bigger?", or "what
  if we switch pagination strategy?" without provisioning anything or writing a load harness.
- **Estimate cloud cost up front.** Components emit *resource-consumption* facts (capacity units, bytes
  read/written, storage, cross-region transfer). Run those through a pricing model and you get an
  estimated bill for a workload that doesn't exist yet. (The repo includes a detailed AWS DynamoDB cost
  model as a worked application of exactly this.)
- **See the tail, not just the mean.** Because each trial is one i.i.d. draw of a stochastic world,
  running N of them yields the *distribution* of a metric across runs — p99 latency, worst-case throttle
  rate, SLO-attainment probability. This is precisely the run-to-run variance that reality can't hand
  you cheaply: production gives one non-repeatable, drifting sample at a time.
- **Reproducible and fast.** Every run is deterministic given a master seed, and a whole Monte Carlo
  ensemble runs in seconds on a laptop.

The output is plain data (JSONL), ready for downstream analytics and visualization engines
(e.g. Postgres + Grafana).

---

## How it works — a 20,000ft view

A simulation is a Pekko Streams graph: a **workload** source feeds a chain of **components**, which emit
responses and consumption facts that get folded into **statistics**. You describe four things.

### 1. Describe the workload

First you say what traffic the system sees — what kinds of requests arrive, and how often. A workload is
just a mix of request streams: "about five reads a second," "a steady trickle of writes," "the odd
expensive report," blended together to look like your real traffic. Each stream gets a label, so when
the same operation shows up in two different guises — say, the same query paginated two different ways —
you can still tell them apart when you read the results later. The engine takes all of this and produces
a stream of requests arriving over simulated time.

### 2. Describe component behaviors

Next you describe how each piece of the system behaves — what the datastore does with a read, how an
admission gate decides whether to accept or reject a request, how long each takes and what it costs. You
write that once per component, as a plain function: given a request and what the component currently
knows about itself, decide what happens next. The behavior rolls dice, so a read might hit or miss and a
scan might touch more or fewer rows from one run to the next. And a component never memorizes every key
it has ever seen — it keeps only a compact summary — so simulating a billion-row table costs no more than
a thousand-row one.

More precisely, each component is a `ComponentSampler` — a small, testable production function:

```scala
trait ComponentSampler[S, In, Out, Cons]:
  def initialState: S
  def sample(in: In, state: S, rng: UniformRandomProvider): Emission[S, Out, Cons]   // per input: new state, one forward output, N consumption facts
  def onTick(tick: Long, state: S): S = state                 // per tick boundary (e.g. reset a per-tick capacity)
```

Given an input and its current state, a sampler produces an **emission**: the updated state, one forward
output (a response, or a downstream request it issues), and zero-or-more consumption facts — each
*scheduled* with a latency. A generic *schedule-and-release transducer* turns each sampler into a running
graph stage, handling all timing, buffering, and ordering, so components chain together with no glue.

### 3. Run simulations

Then you run it — not once, but many times over. A single run tells you what happened in one possible
version of events; running hundreds, each seeded a little differently, tells you the whole range of what
could happen. And because everything traces back to one master seed, that seed reproduces the exact same
set of runs — so results are repeatable and safe to compare.

In code, that's a single call. Wire the components into a graph — `source → components → sinks` — and run
one trial, or a whole ensemble:

```scala
MonteCarlo.run(trialCount, masterSeed, parallelism) { seed => runOneTrial(seed) }
```

The Monte Carlo executor fans one master seed into N reproducible trial seeds and runs them with bounded
parallelism; results are identical regardless of parallelism.

### 4. Collect and aggregate data

Finally you gather the numbers. Every run throws off a pile of measurements — latencies, bytes consumed,
dollars — and stochastacy rolls them into summaries you can actually read: averages, medians, tail
percentiles. From the same data it can answer two quite different questions: what a *typical request*
looks like across all your runs, and how much the outcome *swings from one run to the next* — the
difference between "usually fine" and "usually fine, but one run in ten is a disaster." The results come
out as plain data, ready to drop into whatever you use to chart and explore.

Under the hood, consumption facts fold into mergeable `Statistic`s — additive moments plus a mergeable
histogram for quantiles — keyed however a simulator chooses (e.g. use-case × metric × time-window).
"Mergeable" is the property that makes those two questions cheap and exact to answer —

- **Pooled** — every observation across all trials in one population ("what does a random request look
  like?").
- **Across-trials** — reduce each trial to a scalar, then summarize those N scalars ("how does this
  metric vary run-to-run?").

Export the result to JSONL and hand it to your analytics/visualization stack.

---

## Techniques and design principles

- **Stochastic-summary state, not key-accurate.** Components model outcomes with samplers over a bounded
  summary; there are no per-key maps, so cost is near-constant in request volume and key-space size.
- **Fine-grained, materialized interactions.** Every request, response, and consumption fact is a
  concrete timed event on the wire — stochasticity lives in the *workload* and the *observations*, not
  in a hand-waved "stochastic wire."
- **A timed-event protocol.** Streams are partitioned into time windows by `Tick` events and terminated
  by an `EndOfTime` sentinel; the protocol's ordering invariants hold across every stage boundary,
  including an intra-tick arrival model for sub-tick timing.
- **Mergeable statistics.** Associative `combine` on histograms makes per-tick, cross-window, and
  cross-trial aggregation a simple fold — the prerequisite for tractable Monte Carlo quantiles.
- **Deterministic and reproducible.** Seeded RNGs throughout; a master seed reproduces an entire
  ensemble byte-for-byte.

The engine (`stochastacy.core`) is **domain-agnostic** — it imposes no vocabulary of requests,
resources, or costs. A simulator supplies its own protocol, component behaviors, workload, and reporting.

The reusable building blocks — the interface component and its gates (latency, throttling, burst, chaos),
and the foundations they rest on — are catalogued in
[`specs/component-catalog.md`](specs/component-catalog.md).

---

## Demos

### The Store simulator

A fictional **product-catalog service** — a REST API in front of a datastore, supporting point
operations, category-filtered list queries (under both keyset and offset pagination), and full-scan
aggregate report queries. The simulator was built to explore how *dataset growth* and *pagination
strategy* drive query cost, and how a system behaves as offered load crosses an admission gate's
capacity. Its Monte Carlo experiments **prove the assertions it was designed to explore**: report-query
cost rises measurably as the catalog grows over a run, deep offset-pagination evaluates an order of
magnitude more work than keyset pagination for the identical page of results, and bursty load throttles
even when the *mean* rate sits under capacity. See the detailed engineer's guide in
[`specs/README.store-demo.md`](specs/README.store-demo.md).

### The Store simulator V2 — the gated edge

The same fictional store, but this demo is about the **edge in front of the datastore**: a composable
stack of admission/rejection *interface components* — latency injection, rate limiting, and random
failure — each a reusable `core` gate wrapped onto the datastore, with rejections surfaced in-band so
every request still yields exactly one terminal outcome (served / 429 / 503). Its experiments show how
gating *policy* shapes behavior: a token bucket absorbs a burst that a same-capacity flat cap rejects
(0% vs. 52% throttled on identical traffic), and a random-failure gate stays flat at its configured rate
as offered load climbs while the throttle rate rises with it — the two mechanisms are orthogonal. See
the engineer's guide in [`specs/README.store-demo-v2.md`](specs/README.store-demo-v2.md).

### Order-Tracking — AWS DynamoDB on the v2 core

The first AWS resource modeled on the v2 core: a **single on-demand DynamoDB table** — an order-tracking
service (`PutItem` / `GetItem` / `UpdateItem` / `DeleteItem`) — whose read/write capacity, storage growth,
and on-demand cost are estimated across a Monte Carlo ensemble. The table is a reusable `ComponentSampler`
(generic mechanics, domain injected as a behavior); the demo re-implements the legacy Phase-1 DynamoDB
demo and is proven, by an equivalence gate, to reproduce its aggregate behavior within a few percent —
while *fixing* a legacy bug that never billed a table's pre-loaded storage. See the engineer's guide in
[`specs/README.ordertracking-v2.md`](specs/README.ordertracking-v2.md), and the reusable table component
in [`specs/aws-component-catalog.md`](specs/aws-component-catalog.md).

_More AWS demos — multi-table, multi-region — will be documented here as the AWS line grows._

---

## Building

Scala 3.3 · sbt · Apache Pekko Streams · Apache Commons Statistics/RNG.

```bash
sbt test          # run all tests
sbt core/test     # engine tests only
sbt compile       # compile
```

The engine is published to the local Maven repository for use by standalone downstream projects:

```bash
sbt core/publishM2
```
