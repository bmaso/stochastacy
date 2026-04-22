# IPS Phase 1 Grafana Dashboard

## Purpose

This note describes the implemented Grafana dashboard used for the order-tracking demo path.

The checked-in dashboard definition lives at:

- [order-tracking-phase2-dashboard.json](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/grafana/order-tracking-phase2-dashboard.json)

## Primary User

The primary user for the phase-1 dashboard is:

- a platform engineer
- an SRE
- a cloud architecture engineer

This user wants to understand:

- how DynamoDB table usage evolves over simulated time
- how estimated cost evolves over simulated time
- how much variation exists across repeated simulation trials
- whether an `order-tracking` workload looks operationally and financially safe enough to pursue

This dashboard is intended for:

- design-time planning
- architectural discussion
- internal demonstration

It is not intended for live production telemetry.

## Demo Story

The dashboard tells a simple story:

1. a simulated `order-tracking` service performs `PutItem`, `GetItem`, `UpdateItem`, and `DeleteItem`
2. the simulator runs many trials of the same scenario
3. the dashboard shows central tendency and spread of total consumed capacity over time
4. the dashboard also calls out selected-GSI consumed read and write capacity
5. the dashboard shows whole-run central-range summary values across the full batch of trials

## Data Source

The implemented dashboard is driven by simulator-exported records staged into Postgres.

Current staged record families:

- `trial-time-series`
- `aggregate-time-series`
- `trial-summary`
- `aggregate-summary`
- `trial-window-time-series`
- `aggregate-window-time-series`

The concrete bridge is:

- Postgres as the staging and query bridge
- Grafana PostgreSQL datasource provisioned from checked-in assets

## Dashboard Structure

The dashboard has five rows.

### Row 1: Scenario Summary

Purpose:

- orient the user quickly
- show the whole-run outcome of the current batch of trials

Panels:

1. `Scenario`
- type: text context panel
- content:
  - batch id
  - scenario id
  - trial count
  - simulation ticks
  - window size
  - read consistency
  - table name

2. `Total Estimated Cost Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- presentation:
  - `mean +/- stddev`

3. `Final Storage Bytes Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- presentation:
  - `mean +/- stddev`

### Row 2: Consumed Capacity Summary

Purpose:

- show whole-run central ranges for total and selected-GSI read and write capacity

Panels:

1. `Total Read Capacity Units Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- presentation:
  - `mean +/- stddev`

2. `Total Write Capacity Units Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- presentation:
  - `mean +/- stddev`

3. `GSI Read Capacity Units Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- filter:
  - selected `gsiIndexName`
- presentation:
  - `mean +/- stddev`

4. `GSI Write Capacity Units Central Range`
- type: text panel backed by a hidden query variable
- source:
  - `aggregate-summary`
- filter:
  - selected `gsiIndexName`
- presentation:
  - `mean +/- stddev`

### Row 3: Time-Series Usage

Purpose:

- show how total consumed capacity behaves over simulated time
- make peaks and spread visually obvious

Panels:

1. `Total Read Capacity Units by Window`
- type: time series
- source:
  - `trial-window-time-series`
- aggregation:
  - SQL computes mean, `p5`, `p25`, `p75`, and `p95`
- x-axis:
  - `window_start_tick`
- presentation:
  - white mean line
  - percentile boundary lines
  - shaded percentile bands

2. `Total Write Capacity Units by Window`
- type: time series
- source:
  - `trial-window-time-series`
- aggregation:
  - SQL computes mean, `p5`, `p25`, `p75`, and `p95`
- x-axis:
  - `window_start_tick`
- presentation:
  - white mean line
  - percentile boundary lines
  - shaded percentile bands

### Row 4: Per-GSI Usage

Purpose:

- show the selected GSI's consumed capacity over simulated time without exposing LSI detail

Panels:

1. `GSI Read Capacity Units by Window`
- type: time series
- source:
  - `trial-window-time-series`
- filter:
  - selected `gsiIndexName`
- aggregation:
  - SQL computes mean, `p5`, `p25`, `p75`, and `p95`

2. `GSI Write Capacity Units by Window`
- type: time series
- source:
  - `trial-window-time-series`
- filter:
  - selected `gsiIndexName`
- aggregation:
  - SQL computes mean, `p5`, `p25`, `p75`, and `p95`

### Row 5: Storage And Cost Over Time

Purpose:

- show the two most intuitive cumulative stories:
  - storage occupancy
  - cumulative estimated cost

Panels:

1. `Storage Bytes by Window (Mean +/- StdDev)`
- type: time series
- source:
  - `aggregate-window-time-series`
- filter:
  - `metric = StorageBytes`
- presentation:
  - mean
  - mean + stddev
  - mean - stddev

2. `Cumulative Estimated Cost by Window (Mean +/- StdDev)`
- type: time series
- source:
  - `aggregate-window-time-series`
- filter:
  - `metric = CumulativeEstimatedCost`
- presentation:
  - mean
  - mean + stddev
  - mean - stddev

## Implemented Dashboard Controls

Current dashboard variables include:

- `batch_id`
- `windowSizeSeconds`
- `scenarioId`
- `trialCount`
- `gsiIndexName`
- `simulationTicks`
- `readConsistency`
- `tableName`

Current supported window sizes:

- `60`
- `300`

## Serving Notes

- the dashboard defaults to the simulation epoch time range, not a live wall-clock range
- raw per-tick records remain staged for debugging and future use
- windowed records drive the main presentation panels
- visible reporting preserves:
  - overall read and write capacity
  - per-GSI read and write capacity
  - overall-only storage and cost
- LSI activity remains internal and contributes only to the overall totals
- per-window usage views are reporting artifacts, not authoritative billed price outputs
