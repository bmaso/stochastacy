# Demo 3: Global IoT Thermostat Fleet Telemetry

## Purpose

This demo simulates a connected thermostat company operating across three AWS regions. It is the primary demo vehicle for phase 3 and phase 4 features, replacing the order-tracking demo as the project's showcase scenario.

The demo should make visible:

- on-demand throttling under sustained high write volume
- hot partitions from concentrated access patterns
- burst capacity absorbing morning and evening HVAC activity spikes
- adaptive capacity redistributing unused partition headroom
- dynamic partition topology evolving with fleet growth
- GSI write back-pressure during alert storms
- projection-aware index reads with varying projection widths
- plan-driven index maintenance with realistic write amplification
- LSI item-collection limits from long-lived device histories
- cross-region replication costs and transfer pricing (phase 4)

## Application Concept

A thermostat fleet management platform. Thousands of smart thermostats are deployed in customer homes across multiple geographic regions. Each thermostat periodically reports telemetry readings (temperature, humidity, HVAC mode, setpoint, runtime minutes). A central operations team monitors fleet health, responds to alerts, and supports customers.

The platform uses DynamoDB as its primary data store. In global-table mode, each region has a local replica so that device writes land in the nearest region and replicate to the others for central analytics and customer support.

## Regional Distribution

Three regions participate:

- `us-east-1` — headquarters, largest fleet: 60% of devices (e.g. 50,000)
- `eu-west-1` — European market: 30% of devices (e.g. 25,000)
- `ap-southeast-1` — APAC pilot: 10% of devices (e.g. 8,000)

Traffic is asymmetric by design. The cost story becomes non-obvious: us-east-1 pays for its own 60% of writes plus replicated write units from the other 40%, and the per-region pricing differences (us-east-1 is cheapest, ap-southeast-1 is most expensive) make the total cost impossible to guess without simulation.

A single-region variant (us-east-1 only) should also be runnable for demos that do not require global tables.

## DynamoDB Table Design

### Base Table: `device-telemetry`

- Partition key: `device_id`
- Sort key: `timestamp`

Each item is a telemetry reading. Item size ranges from 200 to 400 bytes per reading.

### GSI 1: `customer-devices`

- Partition key: `customer_id`
- Sort key: `device_id`
- Projection: `KeysOnly`

Supports customer support lookups: "show me all devices for this account." Write amplification is modest because the projected entry is small (keys only), but every new device registration and customer change triggers index maintenance.

### GSI 2: `fleet-alerts`

- Partition key: `region_code`
- Sort key: `alert_timestamp`
- Projection: `Include` with a small set of alert fields (severity, device_id, reading value)

Supports fleet monitoring: "show me recent alerts in us-east-1." This GSI is the one most likely to hit write back-pressure during alert storms, because a cold snap or heat wave causes many devices to alert simultaneously in the same region, concentrating writes on a single GSI partition key.

### GSI 3: `device-status`

- Partition key: `device_id`
- Sort key: `last_seen_timestamp`
- Projection: `All`

Supports the device shadow pattern: latest known state for each device. Every telemetry write updates this index, so it carries the heaviest write amplification.

### LSI: `reading-type-history`

- Partition key: `device_id` (shared with base table)
- Sort key: `reading_type#timestamp`

Supports type-specific history queries: "show me all humidity readings for this device." Because it shares the partition key with the base table, a long-lived device that reports every 30 seconds will accumulate substantial data under a single partition key value, making LSI item-collection limits relevant over multi-month simulated horizons.

## Workload Use Cases

### 1. Telemetry Ingest (dominant)

Each device reports every 30-60 seconds. This is the primary write workload. The fleet size grows linearly during the simulation, modeling customer acquisition.

This use case exercises:

- high sustained write volume
- GSI write amplification across all three indexes
- cross-region replication in global-table mode
- dynamic partition topology growth as the fleet expands

### 2. Morning/Evening HVAC Spikes

HVAC systems cycle more actively during comfort transition periods. The simulation models this as a time-varying Poisson rate that peaks at simulated hours 7-9 and 17-19, roughly doubling the baseline telemetry rate.

This use case exercises:

- burst capacity (short-term absorption of excess demand)
- adaptive capacity (redistribution of unused partition headroom during spikes)

### 3. Alert Storms

Occasionally a weather event causes many devices in one region to trigger temperature alerts simultaneously. Modeled as a stochastic event that temporarily spikes the `fleet-alerts` GSI write rate by 5-10x for a narrow window (e.g. 30-60 seconds of simulated time).

This use case exercises:

- GSI write back-pressure (the `fleet-alerts` GSI cannot absorb the induced write pressure)
- hot partitions on the alert GSI (all alerts in one region land on the same partition key)

### 4. Customer Support Queries

Low-rate `Query` operations against the `customer-devices` GSI. Eventually consistent.

This use case exercises:

- projection-aware reads (KeysOnly projection means cheap reads)
- read capacity consumption distinct from the dominant write workload

### 5. Fleet Dashboard Scans

Periodic `Scan` operations against the `fleet-alerts` GSI to build monitoring dashboards. Low rate but high evaluated-bytes per scan.

This use case exercises:

- projection-aware scan behavior
- read capacity consumption from scan operations

## Simulation Configuration

The scenario configuration should include:

- devices per region (with growth rate per tick)
- telemetry rate per device (Poisson mean, in reports per tick)
- morning/evening spike multiplier and simulated hour window
- alert storm probability per tick, duration in ticks, and write multiplier
- customer support query rate (Poisson mean per tick)
- fleet dashboard scan rate (Poisson mean per tick)
- item size distribution (mean bytes, variance)
- GSI projections and projection sizes
- item-collection size limit for the LSI
- replication lag distribution per region-pair (for global-table mode)
- cross-region transfer pricing rates
- trial count and parallelism
- simulation ticks (e.g. 1200 for a 20-minute simulated window at 1-second ticks)

## Grafana Dashboard Design

### Row 1: Scenario Summary

- Batch id, trial count, simulation ticks
- Fleet size per region
- Total estimated cost central range (mean +/- stddev)
- Per-region cost central range (if global tables are configured)

### Row 2: Capacity Overview

- Total read capacity units by window (percentile bands: p5, p25, mean, p75, p95)
- Total write capacity units by window (percentile bands)

Same structure as the existing phase-2 dashboard.

### Row 3: Per-Region Write Capacity (global-table mode)

- One panel per region showing write capacity over time
- Replicated write contribution visible as a distinct layer or separate series
- Cross-region transfer bytes per region pair

### Row 4: GSI Pressure

- Per-GSI write capacity panels, selectable by GSI name
- `device-status` GSI shows steady high write load
- `fleet-alerts` GSI shows dramatic spikes during alert storms
- `customer-devices` GSI shows low steady write load

### Row 5: Throttling and Admission

- Throttle rate over time, broken out by reason:
  - whole-resource throughput exceeded
  - hot-partition throughput exceeded
  - GSI write back-pressure
  - item-collection size limit exceeded
- This row makes phase-3 admission features visible

### Row 6: Storage and Cost

- Storage bytes over time (mean +/- stddev)
- Cumulative estimated cost over time (mean +/- stddev)
- Per-region cost breakdown (global-table mode)
- Cross-region transfer cost (global-table mode)

## Implementation Steps

### Step 1: Scenario Configuration

Define `ThermostatFleetScenarioConfig` with all the parameters listed above. Follow the same pattern as `OrderTrackingScenarioConfig`.

### Step 2: Use-Case Sampler

Implement `ThermostatFleetBehavior` as a `UseCaseSampler[TableState]`. This is the heart of the demo. The sampler must:

- produce telemetry writes with time-varying Poisson rates (morning/evening spikes)
- produce alert storm events as stochastic spikes on the `fleet-alerts` GSI
- produce customer support queries against the `customer-devices` GSI
- produce fleet dashboard scans against the `fleet-alerts` GSI
- model fleet growth over simulated time
- return `currentItemCollectionBytes` for write samples to exercise the LSI item-collection limit

### Step 3: Single-Trial Runner

Implement `ThermostatFleetSingleTrialRunner` following the same structure as `OrderTrackingSingleTrialRunner`. It constructs the `DynamoDbTable` (or `DynamoDbGlobalTable` for multi-region) with the full index and replication configuration, generates the timed request stream, and collects results.

### Step 4: Demo CLI Bridge

Implement `ThermostatFleetBridge` with `generate`, `stage`, and `view` subcommands, following the same pattern as `OrderTrackingPhase2Bridge`.

### Step 5: Postgres Schema Extension

Extend the Postgres schema if needed. The existing schema uses string-keyed metrics and may already accommodate new metric names without structural changes. Per-region breakdowns and cross-region transfer metrics may require new record families or metric name conventions.

### Step 6: Grafana Dashboard

Build the `thermostat-fleet-dashboard.json` with the row structure described above. Provision it from checked-in assets following the existing pattern.

### Step 7: Tests

- `ThermostatFleetScenarioConfigSpec` — configuration validation
- `ThermostatFleetSingleTrialRunnerSpec` — single-trial execution and result shape
- `ThermostatFleetBridgeSpec` — CLI subcommand parsing and bridge execution
- `ThermostatFleetDemoRunnerSpec` — multi-trial Monte Carlo execution
- Integration spec proving the full generate/stage path works end to end

## Single-Region vs Global-Table Mode

The demo should support both modes from the same scenario configuration:

- Single-region mode uses `DynamoDbTable.componentOf` with all devices in one region. This exercises all phase-3 features except cross-region replication.
- Global-table mode uses `DynamoDbGlobalTable.componentOf` with devices distributed across regions. This additionally exercises replicated write billing, cross-region transfer costs, and per-region pricing.

The scenario configuration should have a simple flag or region-list parameter that switches between modes. The Grafana dashboard should gracefully handle both: per-region panels show a single region in single-region mode and multiple regions in global-table mode.
