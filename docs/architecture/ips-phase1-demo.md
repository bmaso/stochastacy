# IPS Phase 1 Demo Architecture

## Purpose

This note describes the implemented architecture of the phase-1 demo for the initial public showing.

The phase-1 demo:

- uses a table-only DynamoDB simulation
- uses an `order-tracking` scenario
- exercises all currently implemented Stage 4 operations:
  - `GetItem`
  - `PutItem`
  - `UpdateItem`
  - `DeleteItem`
- supports repeated trial execution
- produces raw per-tick and windowed time-series output for dashboarding
- produces mean and standard-deviation statistics across multiple trials

## Demo Goals

The phase-1 demo is intended to show:

- logical CRUD behavior over simulated time
- resource consumption over simulated time
- time-based storage behavior
- estimated cost over a whole simulation run
- statistical variation across repeated simulation trials

The demo is not intended to prove exact DynamoDB billing fidelity.

## Phase-1 Execution Model

The phase-1 demo uses a headless execution model.

That means:

- a JVM process runs the simulation trials
- the process exports structured results
- a host-side CLI stages those results into Postgres
- Grafana reads the staged Postgres records and visualizes them

This is preferred over a live interactive dashboard for phase 1 because it:

- keeps the simulator and demo output architecture simpler
- makes repeated Monte Carlo execution easier to control
- reaches time-series and statistical reporting faster
- avoids coupling the first public showing to UI input plumbing

## Architectural Layers

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

This layer is plain configuration, not dashboard code.

### 2. Single-Trial Runner

Runs one simulation trial end to end.

Responsibilities:

- construct the timed request stream for one trial
- materialize the simulation graph
- collect responses, metric events, and consumption events
- derive trial-local summaries from those outputs

Each trial has isolated execution state, including:

- its own table state
- its own graph materialization
- its own random seed or random state
- its own result buffers

### 3. Multi-Trial Executor

Runs many trials for the same scenario configuration.

Responsibilities:

- run `N` trials
- support bounded parallel execution
- support sequential execution as a fallback
- preserve trial identity in the emitted results

The practical purpose of this layer is to keep 100-trial runs comfortably fast for demo use.

### 4. Monte Carlo Aggregator

Consumes completed `TrialResult`s and computes cross-trial statistics.

Responsibilities:

- align time buckets across trials
- compute per-time-bucket mean
- compute per-time-bucket standard deviation
- compute whole-run mean
- compute whole-run standard deviation

### 5. Export / Reporting Layer

Transforms simulation results into dashboard-friendly records.

Implemented output format:

- JSON Lines (`.jsonl`)

The export preserves both:

- raw per-tick records
- derived `60s` and `300s` windowed records

### 6. Bridge And Dashboard Layer

Stages exported data into Postgres and visualizes it through Grafana.

Current implementation:

- Postgres schema and views are provisioned by Docker init SQL
- Grafana datasource and dashboard are provisioned from checked-in assets
- a host-side CLI exposes:
  - `generate`
  - `stage`
  - `view`

## Output Model

The demo output supports both:

- time-series views
- whole-run summary statistics

### Whole-Run Summary Output

Whole-run summaries support statistical panels and rollups.

Examples:

- total read-capacity usage
- total write-capacity usage
- total storage byte-ticks
- final storage bytes
- total estimated cost

These are available both:

- per trial
- aggregated across trials as mean and standard deviation

## Time-Series And Pricing Principle

Time-series and time windows are important for representing consumption over simulated time.

They are not automatically the same thing as billed price outputs.

The implemented relationship is:

- timed windows represent how usage evolves during a run
- exported windowed records are derived from raw timed records
- final pricing integrates usage over the whole run

The demo therefore supports:

- time-series visualization of usage and cumulative cost behavior
- final total cost for each trial
- mean and standard deviation of total cost across trials

Per-window values are reporting and explanation artifacts, not authoritative billed prices.

## Implemented Record Families

### Raw Trial Time-Series Records

One record per:

- scenario
- trial
- tick
- metric

### Raw Aggregate Time-Series Records

One record per:

- scenario
- tick
- metric
- statistic kind

Current aggregate statistic kinds:

- `mean`
- `stddev`

### Trial Summary Records

One record per:

- scenario
- trial
- summary metric

### Aggregate Summary Records

One record per:

- scenario
- summary metric
- statistic kind

### Trial Window Time-Series Records

One record per:

- scenario
- trial
- window size
- window start tick
- metric

Current phase-1 window sizes:

- `60`
- `300`

### Aggregate Window Time-Series Records

One record per:

- scenario
- window size
- window start tick
- metric
- statistic kind

Current aggregate statistic kinds:

- `mean`
- `stddev`

## Current Serving Path

The implemented serving path is:

1. run the `order-tracking` scenario for many trials
2. export raw and windowed JSONL records
3. stage the JSONL into Postgres
4. read the staged records through the provisioned Grafana dashboard
