# IPS Phase 1 Grafana Dashboard Design

## Purpose

This note defines the Grafana dashboard that should be used for the phase-1 initial public showing.

The intended output of this design step is:

- an implementation-ready dashboard design
- a panel layout that can be translated into Grafana dashboard JSON
- a clear contract for the simulation data that the dashboard will consume

This note is not itself the Grafana dashboard definition.

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

The dashboard should tell a simple story:

1. a simulated `order-tracking` service performs `PutItem`, `GetItem`, `UpdateItem`, and `DeleteItem`
2. the simulator runs many trials of the same scenario
3. the dashboard shows the mean and variance of resource usage and cost over time
4. the dashboard also shows whole-run summary values across the full batch of trials

The dashboard should make it easy to answer:

- when during the run does read load peak?
- when during the run does write load peak?
- how does storage occupancy evolve?
- how does cumulative estimated cost evolve?
- how much run-to-run variability is there?

## Data Source Assumption

Phase 1 should target a Grafana dashboard that is ultimately driven by the simulator's exported records.

The current simulator export model already produces four record families:

- `trial-time-series`
- `aggregate-time-series`
- `trial-summary`
- `aggregate-summary`

The dashboard design assumes those records will be made queryable by Grafana through a suitable data source.

This design intentionally does not yet choose the final ingestion bridge. That will be handled in the implementation step after this design is accepted.

## Dashboard Structure

The dashboard should have three rows.

### Row 1: Scenario Summary

Purpose:

- orient the user quickly
- show the whole-run outcome of the current batch of trials

Panels:

1. `Scenario`
- type: text / stat context panel
- content:
  - scenario id
  - trial count
  - simulation ticks
  - read consistency
  - table name
- purpose:
  - remind the user what they are looking at

2. `Mean Total Estimated Cost`
- type: stat
- source:
  - `aggregate-summary`
- filter:
  - `metric = TotalEstimatedCost`
  - `statistic = mean`
- purpose:
  - show the headline cost estimate for the scenario batch

3. `Variance of Total Estimated Cost`
- type: stat
- source:
  - `aggregate-summary`
- filter:
  - `metric = TotalEstimatedCost`
  - `statistic = variance`
- purpose:
  - show how much spread exists across trials

4. `Mean Final Storage Bytes`
- type: stat
- source:
  - `aggregate-summary`
- filter:
  - `metric = FinalStorageBytes`
  - `statistic = mean`
- purpose:
  - show the expected ending table size

5. `Variance of Final Storage Bytes`
- type: stat
- source:
  - `aggregate-summary`
- filter:
  - `metric = FinalStorageBytes`
  - `statistic = variance`
- purpose:
  - show the spread in ending storage occupancy

### Row 2: Time-Series Usage

Purpose:

- show how the table behaves over simulated time
- make peaks, accumulation, and spread visually obvious

Panels:

1. `Mean Read Capacity Units by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = ReadCapacityUnits`
  - `statistic = mean`
- x-axis:
  - `tick`
- y-axis:
  - mean read-capacity units
- purpose:
  - show when read load occurs

2. `Variance of Read Capacity Units by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = ReadCapacityUnits`
  - `statistic = variance`
- x-axis:
  - `tick`
- y-axis:
  - variance of read-capacity units
- purpose:
  - show uncertainty in read load timing and magnitude

3. `Mean Write Capacity Units by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = WriteCapacityUnits`
  - `statistic = mean`
- x-axis:
  - `tick`
- y-axis:
  - mean write-capacity units
- purpose:
  - show when write load occurs

4. `Variance of Write Capacity Units by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = WriteCapacityUnits`
  - `statistic = variance`
- x-axis:
  - `tick`
- y-axis:
  - variance of write-capacity units
- purpose:
  - show uncertainty in write load timing and magnitude

### Row 3: Storage And Cost Over Time

Purpose:

- show the two most intuitive cumulative stories:
  - storage occupancy
  - cumulative estimated cost

Panels:

1. `Mean Storage Bytes by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = StorageBytes`
  - `statistic = mean`
- x-axis:
  - `tick`
- y-axis:
  - mean storage bytes
- purpose:
  - show the expected storage trajectory across the run

2. `Variance of Storage Bytes by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = StorageBytes`
  - `statistic = variance`
- x-axis:
  - `tick`
- y-axis:
  - variance of storage bytes
- purpose:
  - show how uncertain storage occupancy is over time

3. `Mean Cumulative Estimated Cost by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = CumulativeEstimatedCost`
  - `statistic = mean`
- x-axis:
  - `tick`
- y-axis:
  - mean cumulative estimated cost
- purpose:
  - show how cost accumulates across the simulation run

4. `Variance of Cumulative Estimated Cost by Tick`
- type: time series
- source:
  - `aggregate-time-series`
- filter:
  - `metric = CumulativeEstimatedCost`
  - `statistic = variance`
- x-axis:
  - `tick`
- y-axis:
  - variance of cumulative estimated cost
- purpose:
  - show uncertainty in cost accumulation over time

## Optional Drilldown Panels

These are not required for the first dashboard JSON, but they are reasonable additions if implementation is easy.

1. `Raw Trial Cost Series`
- type: time series
- source:
  - `trial-time-series`
- filter:
  - `metric = CumulativeEstimatedCost`
- purpose:
  - show individual trial lines behind the aggregate story

2. `Raw Trial Summary Table`
- type: table
- source:
  - `trial-summary`
- purpose:
  - allow inspection of individual trial totals

These are optional because they are useful, but the primary phase-1 story should remain aggregate-focused.

## Display Principles

The dashboard should follow these rules.

### 1. Favor aggregate views first

The default experience should emphasize:

- mean
- variance

across many trials, not the details of any single trial.

### 2. Show time-series behavior prominently

The primary charts should be time-series panels, because the simulator's value is not only in total values but also in how usage and cost evolve over time.

### 3. Do not imply per-window billed prices

The `CumulativeEstimatedCost` series is acceptable because it represents the growing total estimated cost across the run.

The dashboard should not present each tick as an independently billed price period.

### 4. Keep the first dashboard narrow

The first dashboard should only cover:

- the `order-tracking` phase-1 scenario
- `GetItem`
- `PutItem`
- `UpdateItem`
- `DeleteItem`

It should not try to preview phase-2 index work.

## Expected Query Shape

The Grafana implementation should be able to query records with at least these logical fields:

- `recordType`
- `scenarioId`
- `trialId`
- `trialCount`
- `tick`
- `metric`
- `statistic`
- `value`

The dashboard JSON implementation should treat:

- `trial-time-series` and `aggregate-time-series` as separate logical sources within the same dataset
- `trial-summary` and `aggregate-summary` as separate logical sources within the same dataset

## Minimal Variable Support

The first dashboard implementation should support at least:

1. `scenarioId`
- default:
  - current phase-1 scenario
- purpose:
  - future-proofing for more than one scenario export

2. `trialCount`
- display-only context, not necessarily a selectable variable

The first dashboard does not need complex interactive filters.

## What The Next Step Should Produce

The implementation step after this design should produce:

- a Grafana dashboard JSON definition matching this layout
- a concrete bridge from simulator-exported records into a Grafana-queryable data source

That next step should translate this note as directly as possible rather than inventing a different layout.
