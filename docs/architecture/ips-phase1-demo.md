# IPS Phase 1 Demo Architecture

## Purpose

This note describes the architecture of the phase-1 demo for the initial public showing.

The phase-1 demo should:

- use a table-only DynamoDB simulation
- use an `order-tracking` scenario
- exercise all currently implemented Stage 4 operations:
  - `GetItem`
  - `PutItem`
  - `UpdateItem`
  - `DeleteItem`
- support repeated trial execution
- produce time-series-friendly output for dashboarding
- produce mean and variance statistics across multiple trials

## Demo Goals

The phase-1 demo is intended to show:

- logical CRUD behavior over simulated time
- resource consumption over simulated time
- time-based storage behavior
- estimated cost over a whole simulation run
- statistical variation across repeated simulation trials

The demo is not intended to prove exact DynamoDB billing fidelity.

## Phase-1 Execution Model

The phase-1 demo should use a headless execution model.

That means:

- a JVM process runs the simulation trials
- the process exports structured results
- a dashboard tool such as Grafana reads those results and visualizes them

This is preferred over a live interactive dashboard for phase 1 because it:

- keeps the simulator and demo output architecture simpler
- makes repeated Monte Carlo execution easier to control
- reaches time-series and mean/variance reporting faster
- avoids coupling the first public showing to UI input plumbing

## Architectural Layers

The demo should be structured as the following layers.

### 1. Scenario Definition

Defines the shape of the `order-tracking` workload and the stochastic parameters for a run.

Responsibilities:

- operation mix over time
- request-frequency assumptions
- item-size assumptions
- hit/miss tendencies
- update and delete tendencies
- simulation horizon
- trial count
- trial parallelism

This layer should be plain configuration, not dashboard code.

### 2. Single-Trial Runner

Runs one simulation trial end to end.

Responsibilities:

- construct the timed request stream for one trial
- materialize the simulation graph
- collect responses, metric events, and consumption events
- derive trial-local summaries from those outputs

Each trial should have isolated execution state, including:

- its own table state
- its own graph materialization
- its own random seed or random state
- its own result buffers

This isolation is required so multi-trial execution can run safely.

### 3. Multi-Trial Executor

Runs many trials for the same scenario configuration.

This layer is required for the phase-1 demo. A single-trial runner by itself is not enough.

Responsibilities:

- run `N` trials
- support bounded parallel execution
- support sequential execution as a fallback
- preserve trial identity in the emitted results

Recommended controls:

- `trialCount`
- `parallelism`

Recommended default behavior:

- use bounded parallelism in normal runs
- allow `parallelism = 1` for simpler debug or deterministic runs

The purpose of this layer is practical as well as architectural: if the demo runs 100 trials, it should not take multiple minutes under normal development conditions.

### 4. Monte Carlo Aggregator

Consumes completed `TrialResult`s and computes cross-trial statistics.

Responsibilities:

- align time buckets across trials
- compute per-time-bucket mean
- compute per-time-bucket variance
- compute whole-run mean
- compute whole-run variance

This layer should treat per-time-series data and whole-run totals as related but distinct outputs.

### 5. Export / Reporting Layer

Transforms simulation results into dashboard-friendly records.

Responsibilities:

- flatten trial results into exportable records
- flatten aggregate statistics into exportable records
- preserve enough metadata for dashboards to group and filter correctly

Recommended initial output format:

- JSON Lines (`.jsonl`)

This is preferred for phase 1 because it:

- handles multiple record shapes cleanly
- is easy to generate from the JVM
- is easy to ingest into downstream tools

CSV may still be useful later, but JSON Lines is the better initial fit.

### 6. Dashboard / Viewer Layer

Reads exported data and visualizes it.

Phase-1 goal:

- display results from exported batch data

Deferred beyond phase 1:

- live dashboard controls that mutate simulation parameters and rerun batches interactively

## Output Model

The demo output should support both:

- time-series views
- whole-run summary statistics

### Time-Series Output

Time-series output should make the simulator's dynamic behavior visible.

Examples:

- read-capacity consumption over time
- write-capacity consumption over time
- storage occupancy over time
- cumulative estimated cost over time

These can be represented either as:

- raw per-trial time-series points
- aggregate per-time-bucket statistics across trials

### Whole-Run Summary Output

Whole-run summaries should support statistical panels and rollups.

Examples:

- total read-capacity usage
- total write-capacity usage
- total storage byte-ticks
- final storage bytes
- total estimated cost

These should be available both:

- per trial
- aggregated across trials as mean and variance

## Time-Series And Pricing Principle

Time-series and time windows are important for representing consumption over simulated time.

They are not automatically the same thing as billed price outputs.

The intended relationship is:

- timed windows represent how usage evolves during a run
- final pricing integrates usage over the whole run

So the demo should support:

- time-series visualization of usage and cumulative cost behavior
- final total cost for each trial
- mean and variance of total cost across trials

The demo should not assume that each minute or time bucket needs its own authoritative billed price.

Per-time-bucket values may still be useful for explanation and visualization, but whole-run totals remain the primary pricing outputs.

## Recommended Record Families

The export layer should likely produce at least these record families.

### Trial Time-Series Records

One record per:

- scenario
- trial
- time bucket
- metric

Examples of fields:

- scenario id
- trial id
- tick or window index
- metric name
- value
- statistic kind = `raw`

### Aggregate Time-Series Records

One record per:

- scenario
- time bucket
- metric
- statistic kind

Examples of fields:

- scenario id
- tick or window index
- metric name
- statistic kind = `mean` or `variance`
- value

### Trial Summary Records

One record per:

- scenario
- trial
- metric total

### Aggregate Summary Records

One record per:

- scenario
- metric total
- statistic kind

## Suggested Code Structure

A reasonable initial package structure could be:

- `stochastacy.demo`
- `stochastacy.demo.scenarios`
- `stochastacy.demo.runner`
- `stochastacy.demo.reporting`

Possible core types:

- `OrderTrackingScenarioConfig`
- `TrialResult`
- `MonteCarloResult`
- `TimeSeriesPoint`
- `SummaryStat`
- `SingleTrialRunner`
- `TrialExecutor`
- `DemoExporter`

## Recommended Phase-1 Build Order

1. define the demo data model
2. define the `order-tracking` scenario config
3. implement the single-trial runner
4. implement the multi-trial executor with bounded parallelism
5. implement the Monte Carlo aggregation layer
6. implement JSON Lines export
7. connect the exported data to a dashboard view

## Deferred Beyond Phase 1

The following are intentionally out of scope for this demo architecture:

- index-aware demo scenarios
- `Query`
- `Scan`
- PartiQL query support
- live dashboard parameter controls
- distributed execution of trials
- exact AWS billing semantics
