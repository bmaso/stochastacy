# IPS Phase 7 — Composable Workloads

## Goal

Phase 7 delivers a composable, declarative **workload** system for stochastacy: a typed Scala
hierarchy of composable samplers, a YAML DSL for defining workloads from those samplers, and a
lightweight web-based visualization tool for exploring and decomposing workload timelines.

All existing demos are migrated to define their traffic patterns using the new workload system.

Concretely, Phase 7 delivers:

1. A `Sampler[S, T]` trait hierarchy — the foundational abstraction for all stochastic and
   deterministic value generation in stochastacy, covering both stateless and stateful cases.
2. A `WorkloadDefinition` model — a declarative description of a request stream: which request
   types arrive, at what rate, with what per-request parameter distributions.
3. Migration of all existing demos to use `WorkloadDefinition`, replacing the current
   hardcoded imperative request generators.
4. A YAML DSL for defining workloads, making workload definitions portable, human-readable,
   and independent of Scala code.
5. A lightweight web-based workload visualizer — a tool for exploring a workload timeline
   defined in the DSL, with visual decomposition of complex workloads into their constituent
   samplers. Designed to be packageable as a desktop app (Tauri) without changes to the
   web layer.

---

## Phase-7 Implementation Slices

### Status

| Slice | Status | Summary |
|-------|--------|---------|
| 1. Core sampler hierarchy | **Done** | `Sampler[S, T]` trait; `StatelessSampler[T]` alias; 7 distribution samplers + `ConstantSampler`; `MappedSampler` + `CombiningSampler` combinators; `TemporalShapeFunctions` pure factory functions; `RandomBurstSampler` (stateful burst pattern); `ErasedSampler` (stateful → stateless adapter) |
| 2. Workload definition model | **Done** | `RequestShape` sealed ADT (8 variants); `RequestShapeDefinition(rate, shape)`; `WorkloadDefinition(tableName, usecase, requests)`; `WorkloadRequestStream` generator with per-shape split RNGs |
| 3. Demo migration | **Done** | `ThermostatFleetScenarioConfig.toWorkloadDefinition(region)` translates config scalars to composed samplers; all four demo runners call `WorkloadRequestStream`; `generateRequestsForRegion`, `computeSpikeMultiplier`, `poissonSampler` deleted; `UseCaseSampler` boundary documented and left unchanged |
| 4. YAML DSL | Planned | YAML schema + parser for all stateless sampler types; round-trip tests |
| 5+ Workload visualizer | To be sliced | Lightweight web tool; visual workload timeline; sampler decomposition; Tauri desktop packaging |

---

### 1. Core Sampler Hierarchy

**Goal:** Establish the foundational `Sampler[S, T]` abstraction and all primitive
implementations needed to express real workload patterns.

**Core trait:**

```scala
trait Sampler[S, T]:
  def initialState: S
  def sample(tick: Long, rng: UniformRandomProvider, state: S): (T, S)

type StatelessSampler[T] = Sampler[Unit, T]

object Sampler:
  def stateless[T](f: (Long, UniformRandomProvider) => T): StatelessSampler[T]
```

The stateless case is the common case. `StatelessSampler[T]` is a type alias, not a separate
trait; `Sampler.stateless(...)` is a convenience constructor that hides the `Unit` state from
implementors who don't need it.

**Distribution samplers** (all `StatelessSampler`):

Each distribution sampler accepts one `Long => Double` function per distribution parameter.
The constant case is a factory on the companion object, not a separate class.

| Sampler | Parameters | Output type |
|---------|------------|-------------|
| `PoissonSampler(lambda: Long => Double)` | λ | `Int` |
| `BinomialSampler(n: Long => Long, p: Long => Double)` | n trials, p success | `Int` |
| `NormalSampler(mean: Long => Double, stddev: Long => Double)` | μ, σ | `Double` |
| `LogNormalSampler(mu: Long => Double, sigma: Long => Double)` | μ, σ of log | `Double` |
| `UniformSampler(min: Long => Double, max: Long => Double)` | bounds | `Double` |
| `BernoulliSampler(p: Long => Double)` | probability | `Boolean` |
| `ConstantSampler(value: T)` | fixed value | `T` |

Example of constant factory:
```scala
object PoissonSampler:
  def constant(lambda: Double): PoissonSampler = PoissonSampler(_ => lambda)
```

**Temporal shape functions** (`Long => Double`, plain Scala values, not samplers):

These are passed into distribution sampler parameter slots. They are pure functions of tick,
carry no state, and do not touch the RNG.

| Function | Description |
|----------|-------------|
| `dailySinusoid(min, max, periodTicks, peakTick)` | sinusoidal cycle; min at trough, max at peak |
| `linearGrowth(initial, ratePerTick)` | linearly increasing value |
| `triangularPeak(start, end, peakMultiplier)` | ramps up to peak and back down over a tick range |
| `timeWindow(start, end, inner)` | passes through `inner(tick)` during range, zero outside |
| `weekdayMask(ticksPerDay, inner)` | passes through `inner(tick)` Mon–Fri, zero on weekends; assumes tick 0 = midnight Monday |
| `randomBurst(probability, durationTicks, multiplier)` | **stateful** — not a pure `Long => Double`; modeled as a `Sampler[BurstState, Double]` instead (see note below) |

Note: `randomBurst` cannot be a pure `Long => Double` function because it requires stochastic
state (whether a burst is currently active). It is the primary motivating use case for
`Sampler[S, T]` with non-Unit state. It is implemented as a stateful sampler wrapping any
`StatelessSampler[Double]`:

```scala
case class RandomBurstSampler[S](
  inner: Sampler[S, Double],
  probability: Double,
  durationTicks: Int,
  multiplier: Double
) extends Sampler[(Int, S), Double]
```

**Deliverables (complete):** `Sampler[S, T]` trait + `StatelessSampler[T]` alias + `stateless`/`deterministic` constructors; all distribution samplers with companion `constant(...)` factories; `MappedSampler` and `CombiningSampler` combinators with named constructors; `TemporalShapeFunctions` pure factory functions; `RandomBurstSampler` (stateful, produces `Int`, wraps a lambda-producing sampler); `ErasedSampler` (adapts any `Sampler[S, T]` to `StatelessSampler[T]` via mutable state — needed to use stateful rate samplers in `RequestShapeDefinition.rate`); unit tests for each.

---

### 2. Workload Definition Model

**Goal:** Define the data model for a complete workload and the request stream generator that
replaces the current imperative `generateRequestsForRegion`.

```scala
case class RequestShapeDefinition(
  requestType: DynamoDbRequestType,          // PutItem, GetItem, Query, Scan, TransactWrite, ...
  rate: StatelessSampler[Int],               // count per tick
  params: Map[String, StatelessSampler[_]]   // per-request parameter samplers
)

case class WorkloadDefinition(
  tableName: String,
  requests: Vector[RequestShapeDefinition]
)
```

The request stream generator takes a `WorkloadDefinition` and produces the same
`Iterator[TimedElement[DynamoDBRequest]]` that `generateRequestsForRegion` currently produces,
but driven entirely by the sampler definitions rather than hardcoded logic.

**Deliverables (complete):** `RequestShape` sealed ADT with 8 variants; `RequestShapeDefinition(rate, shape)` with convenience constructors; `WorkloadDefinition(tableName, usecase, requests)`; `WorkloadRequestStream` generator (two RNGs per shape, `Tick`-framed output); 19 unit tests covering tick framing, request counts, request types, parameter propagation, and metadata.

---

### 3. Demo Migration

**Goal:** Replace all hardcoded request generation in existing demos with `WorkloadDefinition`
instances. `generateRequestsForRegion` is removed; `ThermostatFleetBehavior`'s request
generation logic is expressed as sampler compositions.

Affected demos: thermostat fleet single-region, multi-region, mixed-mode, capstone.

**Open question:** `UseCaseSampler` implementations currently express per-request outcome
sampling (hit/miss, item bytes, partition access) in a style that predates the `Sampler[S, T]`
hierarchy. It is expected that `UseCaseSampler` implementations can be aligned with or derived
from the new hierarchy, but the exact relationship is to be determined during this slice.
The minimum requirement is that existing `UseCaseSampler` behavior is preserved; alignment
with the new hierarchy is a stretch goal.

**Deliverables (complete):** `ThermostatFleetScenarioConfig.toWorkloadDefinition(region)` translates config scalars into composed samplers (fleet growth, spike multipliers, polar vortex, alert storm via `RandomBurstSampler`, `transactWriteItemsPerItemBytes` branch); all four demo runners (`ThermostatFleetSingleTrialRunner` ×2, `ThermostatFleetMixedModeSingleTrialRunner`, `ThermostatFleetMultiTableSingleTrialRunner`, `ThermostatFleetCapstoneSingleTrialRunner`) call `WorkloadRequestStream`; `generateRequestsForRegion`, `computeSpikeMultiplier`, and `poissonSampler` deleted; `UseCaseSampler` boundary documented and left unchanged; all 490 tests pass.

---

### 4. YAML DSL

**Goal:** A YAML schema and parser that produces a `WorkloadDefinition` from a YAML document,
making workloads portable and independent of Scala code.

**Scope:** stateless samplers only. Stateful samplers have no current use cases that require
YAML representation; they remain available programmatically.

The YAML schema covers:
- All distribution samplers (`poisson`, `log-normal`, `normal`, `binomial`, `uniform`,
  `bernoulli`, `constant`)
- All temporal shape functions as named parameter expressions (`daily-sinusoid`,
  `linear-growth`, `triangular-peak`, `time-window`, `weekday-mask`)
- `WorkloadDefinition` and `RequestShapeDefinition` structure

Example fragment:
```yaml
workload:
  table: device-telemetry
  requests:
    - type: put-item
      rate:
        distribution: poisson
        lambda:
          shape: daily-sinusoid
          min: 10.0
          max: 200.0
          period-ticks: 1440
          peak-tick: 720
      params:
        item-bytes:
          distribution: log-normal
          mu: 5.7
          sigma: 0.25
```

**Deliverables:** YAML schema; parser (YAML → `WorkloadDefinition`); round-trip tests
verifying that parsing a YAML document produces a definition whose sampled output matches
the equivalent programmatically-constructed definition.

---

### 5+ Workload Visualizer (to be sliced)

The workload visualizer is a lightweight web-based tool that accepts a workload YAML document
and renders an interactive timeline. It is out of scope for slices 1–4 and will be broken into
one or more slices once the DSL is complete and the technology choices are confirmed.

The intended capabilities are:

- **Timeline view**: per-tick sampled rate for each request type in the workload, rendered as
  a time series chart.
- **Decomposition view**: for a selected request type, show each constituent sampler's
  contribution to the overall rate at any tick — the "track mixing" breakdown.
- **Parameter inspection**: display the time-varying parameter functions (e.g. the sinusoidal
  lambda curve) alongside the Poisson draws they produce.

**Technology direction:** the tool will be built as a standard web application. It should be
designed so that it can be packaged as a desktop application using Tauri (a Rust-based shell
around a system webview) without changes to the web layer. Tauri is the preferred
web-to-desktop packaging tool: it is actively maintained, produces small binaries, and requires
no changes to the web application itself.

The specific frontend framework, chart library, and slice breakdown will be decided at the
start of this work, once slices 1–4 are complete.
