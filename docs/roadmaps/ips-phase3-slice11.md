# Phase 3 Slice 11: Thermostat Fleet Demo

## Goal

Deliver a runnable thermostat fleet demo that exercises the full phase-3 simulator feature set in both single-region and multi-region (global table) modes. At the end of this slice, a user can run `generate / stage / view` and see a Grafana dashboard showing Monte Carlo simulation results for an IoT thermostat fleet deployed across up to three AWS regions.

This is the capstone demo for phase 3. The detailed application concept and table design are specified in `docs/specs/thermostat-fleet-demo.md`.

## Prerequisite State

Slices 1-10 are complete. The simulator core supports on-demand throttling, hot partitions, burst capacity, adaptive capacity, dynamic partition topology, GSI write back-pressure, projection-aware reads, plan-driven index maintenance, LSI item-collection constraints, and global tables with cross-region replication and transfer pricing.

The existing order-tracking phase-2 demo is stable and will remain alongside the new demo.

## Implementation Plan

The slice is organized into seven sequential steps. Each step produces testable, compilable output. Steps 1-4 are the critical path; steps 5-7 complete the demo surface.

### Step 1: Scenario Configuration

**What:** Define `ThermostatFleetScenarioConfig` in a new `examples/src/main/scala/stochastacy/examples/thermostatfleet/` package.

**Fields:**

Scenario identity and simulation parameters:
- `scenarioId: String`
- `simulationTicks: Long`
- `trialCount: Int`
- `parallelism: Int`
- `tableName: String` (default `"device-telemetry"`)

Fleet parameters:
- `regions: Vector[RegionFleetConfig]` — one entry per region, where `RegionFleetConfig` contains `regionName: String`, `initialDeviceCount: Long`, `deviceGrowthPerTick: Double` (Poisson mean of new devices per tick)
- `telemetryReportsPerDevicePerTick: Double` — baseline Poisson mean (e.g. 0.03 for roughly one report per 30 ticks/seconds)

Item size:
- `telemetryItemMeanBytes: Long` (default 300)
- `telemetryItemBytesVariance: Double` (default 0.25, as fraction of mean)

Time-varying workload:
- `morningSpikePeakMultiplier: Double` (default 2.0)
- `morningSpikePeakTickRange: (Long, Long)` — tick range for the morning peak (e.g. ticks 420-540 for simulated hours 7-9 at 1 tick/second)
- `eveningSpikePeakMultiplier: Double` (default 2.0)
- `eveningSpikePeakTickRange: (Long, Long)` — tick range for the evening peak

Alert storms:
- `alertStormProbabilityPerTick: Double` (default 0.002)
- `alertStormDurationTicks: Int` (default 30)
- `alertStormWriteMultiplier: Double` (default 5.0)

Read workload:
- `customerSupportQueryRatePerTick: Double` (Poisson mean)
- `fleetDashboardScanRatePerTick: Double` (Poisson mean)

Index configuration:
- `readConsistency: ReadConsistency` (default `EventuallyConsistent`)
- `customerDevicesGsiProjection: IndexProjection` (default `KeysOnly`)
- `fleetAlertsGsiProjectedNonKeyBytes: Long` (default 64, for `Include` projection)
- `deviceStatusGsiProjection: IndexProjection` (default `All`)
- `readingTypeHistoryLsiProjection: IndexProjection` (default `All`)

Item-collection constraint:
- `itemCollectionSizeLimitBytes: Option[Long]` (default `Some(10L * 1024 * 1024 * 1024)` when LSIs are configured)

Admission parameters (on-demand limits, hot-partition thresholds, burst, adaptive — per-region where applicable):
- `onDemandMaxThroughput: DynamoDbTable.OnDemandMaxThroughput`
- `hotPartitionModel: Option[DynamoDbTable.HotPartitionModel]`
- `burstCapacityModel: Option[DynamoDbTable.BurstCapacityModel]`
- `adaptiveCapacityModel: Option[DynamoDbTable.AdaptiveCapacityModel]`
- `dynamicPartitionTopologyModel: Option[DynamoDbTable.DynamicPartitionTopologyModel]`

Global table parameters (used only when `regions.size > 1`):
- `replicationModel: Option[ReplicationModel]` — per-link lag distributions; when `None` and multi-region, uses a sensible default (e.g. log-normal with mean 500ms / stddev 200ms for all links)

A companion object provides a `singleRegionDefault` and a `multiRegionDefault` preset.

**Validation:** Standard `require` checks following the `OrderTrackingScenarioConfig` pattern.

**Test:** `ThermostatFleetScenarioConfigSpec` — validates defaults, rejects invalid configs.

### Step 2: Use-Case Sampler

**What:** Implement `ThermostatFleetBehavior` as a `UseCaseSampler[TableState]` in the same package.

**Design:** A single sampler class handles all five use-case types. The request's `usecase` field determines the use case (e.g. `"telemetry-ingest"`, `"customer-support-query"`, `"fleet-dashboard-scan"`). The sampler class is constructed per-trial with a `ThermostatFleetScenarioConfig` and an `UniformRandomProvider`, following the `OrderTrackingBehavior` pattern.

**Sampler methods:**

`putItem` (telemetry ingest): Returns a `PutItemSample` with `writtenItemBytes` sampled from a scaled normal around `telemetryItemMeanBytes`, `previousItemBytes = None` (always a new item), `logicalPartitionAccess = SingleLogicalPartitionKey(nextDeviceKey())`, and `currentItemCollectionBytes` estimated from the table's current `averageItemBytes * estimatedItemsPerDevice` to exercise the LSI item-collection limit.

`query` (customer support): Returns a `QuerySample` with small evaluated/returned counts, `logicalPartitionAccess = SingleLogicalPartitionKey(nextCustomerKey())`. Targets the `customer-devices` GSI.

`scan` (fleet dashboard): Returns a `ScanSample` with moderate evaluated bytes, `logicalPartitionAccess = AllPartitions`. Targets the `fleet-alerts` GSI.

`getItem`, `updateItem`, `deleteItem`: Not used in the primary workload. Implement as reasonable defaults or throw `UnsupportedOperationException` if not needed.

**Key internal state:** The sampler maintains a mutable `currentAlertStormTicksRemaining: Int` counter. Each tick, if an alert storm is not active, a Bernoulli draw against `alertStormProbabilityPerTick` may start one.

**Partition key generation:** `nextDeviceKey()` generates tokens like `"device-${rng.nextLong(deviceCount)}"` where `deviceCount` grows with fleet growth. `nextCustomerKey()` generates `"customer-${rng.nextLong(customerCount)}"` with a configurable devices-per-customer ratio. This follows the existing `OrderTrackingBehavior.nextLogicalPartitionKey` pattern.

**Test:** Unit tests for the sampler in isolation: correct sample types for each use case, alert storm activation and deactivation, time-varying rate modulation.

### Step 3: Request Stream Generation and Single-Trial Runner

**What:** Implement request stream generation and `ThermostatFleetSingleTrialRunner` in the same package.

**Request stream generation:** A function `generateRequests(config, rng): Vector[TimedElement[DynamoDBRequest]]` produces the full timed request stream for one trial, following the `OrderTrackingSingleTrialRunner.generateRequests` pattern.

For each tick:
1. Emit a `Tick` event.
2. Compute the effective telemetry rate: `baseRate * spikeMultiplier(tick) * fleetSize(tick)`. The spike multiplier is 1.0 outside peak windows, rising to `morningSpikePeakMultiplier` or `eveningSpikePeakMultiplier` within peak windows (sinusoidal or triangular ramp).
3. Sample the Poisson count of telemetry `PutItemRequest`s at the effective rate.
4. If an alert storm is active, sample additional `PutItemRequest`s at `alertStormWriteMultiplier * baseRate`.
5. Sample Poisson count of `QueryRequest`s for customer support (targeting `customer-devices` GSI).
6. Sample Poisson count of `ScanRequest`s for fleet dashboards (targeting `fleet-alerts` GSI).

In single-region mode, all requests target the single region's table. In multi-region mode, the runner generates separate request streams per region (proportional to each region's fleet size) and feeds them to separate inlets on the `DynamoDbGlobalTable` component.

**Single-trial runner — single-region path:** `runTrialSingleRegion(config, run)` constructs a `DynamoDbTable.componentOf` with the full index configuration (3 GSIs + 1 LSI), materializes the graph, collects responses/consumption/metrics, and builds a `TrialResult` following the `OrderTrackingSingleTrialRunner.runTable` + `buildTrialResult` pattern.

The `DynamoDbTable.Config` is constructed with:
- `tableName` from scenario config
- `stateModel = SummaryTableState(initialItemCount, initialItemCount * telemetryItemMeanBytes)`
- `useCaseBehaviors` mapping each use-case key to a `ThermostatFleetBehavior` instance
- `globalSecondaryIndexes` = three `GlobalSecondaryIndexDefinition`s (`customer-devices` KeysOnly, `fleet-alerts` Include, `device-status` All)
- `localSecondaryIndexes` = one `LocalSecondaryIndexDefinition` (`reading-type-history`)
- `itemCollectionSizeLimitBytes` from scenario config
- `onDemandMaxThroughput`, `hotPartitionModel`, `burstCapacityModel`, `adaptiveCapacityModel`, `dynamicPartitionTopologyModel` from scenario config

**Single-trial runner — multi-region path:** `runTrialMultiRegion(config, run)` constructs a `DynamoDbGlobalTable.componentOf` with per-region `DynamoDbTable.Config`s (base-table-only for slice 10 — GSI/LSI support inside replicated tables is a deferred follow-on). Materializes the graph with per-region request sources, collects per-region consumption/metrics plus cross-region transfer events, and builds a `TrialResult` with region-tagged metrics.

**Important constraint:** Slice 10's `DynamoDbGlobalTable` requires base-table-only configs (no GSIs/LSIs). The multi-region path therefore runs with base-table-only tables. This is a known limitation of slice 10; the dashboard should note this. The single-region path runs with full indexes.

**TrialResult construction:** Extends the existing `buildTrialResult` pattern with new metric names for per-region capacity (e.g. `RegionWriteCapacityUnits(regionName)`, `RegionReadCapacityUnits(regionName)`) and cross-region transfer (e.g. `CrossRegionTransferBytes(source, dest)`). These require extending `DemoMetric` in `core/src/main/scala/stochastacy/demo/model.scala`.

**New DemoMetric variants needed:**
- `RegionReadCapacityUnits(regionName: String)`
- `RegionWriteCapacityUnits(regionName: String)`
- `RegionStorageBytes(regionName: String)`
- `RegionEstimatedCost(regionName: String)`
- `CrossRegionTransferBytes(sourceRegion: String, destinationRegion: String)`
- `TotalCrossRegionTransferBytes`
- `TotalRegionReadCapacityUnits(regionName: String)`
- `TotalRegionWriteCapacityUnits(regionName: String)`
- `TotalRegionEstimatedCost(regionName: String)`
- `TotalCrossRegionTransferCost`

**Test:** `ThermostatFleetSingleTrialRunnerSpec` — runs a small single-region trial (few ticks, small fleet), verifies result shape: expected metric names present, non-negative values, consumption events emitted for base table and indexes. A multi-region variant (base-table-only) verifies per-region metrics and transfer events.

### Step 4: Multi-Trial Executor and Demo CLI Bridge

**What:** Implement `ThermostatFleetBridge` with `generate`, `stage`, and `view` subcommands.

**Multi-trial execution:** Reuse the existing `stochastacy.demo.runner` infrastructure (`FutureMultiTrialExecutor` or equivalent). The bridge's `generate` command runs N trials in parallel, aggregates results via `MonteCarloAggregator`, and exports to JSONL following the existing `OrderTrackingPhase2Bridge` pattern.

**CLI arguments for `generate`:**
- `--batch-id` (required)
- `--output` (required, JSONL path)
- `--trial-count` (default 100)
- `--parallelism` (default 8)
- `--simulation-ticks` (default 1200)
- `--mode` (required: `single-region` or `multi-region`)
- Region-specific overrides as needed (or use defaults)

**CLI arguments for `stage`:**
- Same as order-tracking: `--input`, `--batch-id`, `--db-url`, `--db-user`, `--db-password`, plus metadata fields

**CLI arguments for `view`:**
- `--batch-id`

**Postgres schema:** The existing `demo_records` table uses string-keyed metrics and should accommodate the new metric names without schema changes. If the per-region metric names include the region name in the metric string (e.g. `"Region:us-east-1:WriteCapacityUnits"`), the existing schema works. Verify this by inspecting the existing schema and staging code.

**Test:** `ThermostatFleetBridgeSpec` — CLI argument parsing. Integration spec: generate a small batch, stage to H2, verify staged records have expected metric names.

### Step 5: Grafana Dashboard

**What:** Build `thermostat-fleet-dashboard.json` and provision it alongside the existing order-tracking dashboard.

**Dashboard structure:** Six rows as specified in `docs/specs/thermostat-fleet-demo.md`.

**Dashboard variables:**
- `batch_id` — selectable from staged batches
- `windowSizeSeconds` — 60 or 300
- `scenarioId`
- `gsiIndexName` — selectable from `customer-devices`, `fleet-alerts`, `device-status`
- `regionName` — selectable from staged region names (for per-region panels)
- `trialCount`, `simulationTicks`

**Row 1 (Scenario Summary):** Text panels for batch metadata. Central-range panels for total estimated cost and per-region cost (backed by hidden query variables from `aggregate-summary` records).

**Row 2 (Capacity Overview):** Percentile-band time-series panels for total read and write capacity, same structure as the phase-2 dashboard.

**Row 3 (Per-Region Write Capacity):** Time-series panels filtered by selected `regionName`, showing `Region:$regionName:WriteCapacityUnits` metric. Visible only when multi-region data is present.

**Row 4 (GSI Pressure):** Per-GSI write capacity panels filtered by selected `gsiIndexName`, same structure as the phase-2 per-GSI row.

**Row 5 (Throttling and Admission):** New panels. Requires throttle-rate metrics in the time series. If `Stage1MetricEvent` throttle counts are not currently exported to the demo time series, add `ThrottleCount` and `ThrottleCountByReason(reason)` as new `DemoMetric` variants, emitted from the trial runner's metric-event processing.

**Row 6 (Storage and Cost):** Storage bytes and cumulative cost over time, same structure as the phase-2 row. Additional per-region cost and cross-region transfer cost panels for multi-region mode.

**Provisioning:** Add the dashboard JSON to `examples/grafana/` and update the Docker Compose Grafana provisioning if needed.

**Test:** `ThermostatFleetGrafanaAssetsSpec` — verifies the dashboard JSON is valid, contains expected panel titles, references expected query variables.

### Step 6: Demo Runbook

**What:** Write `docs/runbooks/thermostat-fleet-demo.md` documenting the operator workflow.

**Content:** Prerequisites, stack startup, generate/stage/view commands for both single-region and multi-region modes, dashboard usage instructions, troubleshooting notes. Follow the structure of `docs/runbooks/ips-phase2-demo.md`.

### Step 7: Integration Testing and Polish

**What:** End-to-end integration testing and documentation updates.

**Integration test:** `ThermostatFleetDemoIntegrationSpec` — runs a small batch (5 trials, 30 ticks, small fleet) through the full generate/stage path, verifies staged records contain expected metric families, verifies the trial result shape is consistent across trials.

**Documentation updates:**
- Update `docs/handoffs/ips-handoff.md` to reference the thermostat fleet demo as the current runnable demo surface.
- Update `docs/roadmaps/ips-phase3.md` to mark slice 11 as complete.
- Update `CLAUDE.md` to reference the new demo and its key source files.

## Key Design Decisions

### Decision 1: Single-region path has full indexes; multi-region path is base-table-only

Slice 10's `DynamoDbGlobalTable` does not support GSI/LSI inside replicated tables. Rather than block the multi-region demo until that follow-on is complete, the demo runs multi-region with base-table-only configs and single-region with full indexes. Both paths are valuable: single-region shows the admission/throttling/index story, multi-region shows the replication/cost story. The dashboard should clearly indicate which mode was used for a given batch.

### Decision 2: Request stream is pre-generated, not live-sampled

Following the order-tracking demo pattern, the full request stream is generated in memory before graph materialization. This keeps the demo deterministic per RNG seed and avoids coupling request generation to backpressure behavior. For large fleet sizes and long simulation horizons, memory usage scales with `totalRequests = sum(devicesPerRegion) * telemetryRatePerDevice * simulationTicks`. For the default configuration (83,000 devices, 0.03 reports/tick, 1200 ticks), this is roughly 3 million request events — feasible in memory.

### Decision 3: DemoMetric extension is additive

New `DemoMetric` variants for per-region and cross-region metrics are added alongside existing variants. The existing `exportName` and `sortKey` patterns are extended. No existing metric names change.

### Decision 4: Thermostat demo lives alongside order-tracking demo

The new demo is a separate example in the `examples/` module, not a replacement for order-tracking. Both demos remain runnable. The thermostat fleet demo is the primary showcase for phase 3; the order-tracking demo remains available for simpler single-region demonstrations.

## Files Created

- `examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetScenarioConfig.scala`
- `examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBehavior.scala`
- `examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunner.scala`
- `examples/src/main/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBridge.scala`
- `examples/grafana/thermostat-fleet-dashboard.json`
- `docs/runbooks/thermostat-fleet-demo.md`
- `examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetScenarioConfigSpec.scala`
- `examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetSingleTrialRunnerSpec.scala`
- `examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetBridgeSpec.scala`
- `examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetDemoIntegrationSpec.scala`
- `examples/src/test/scala/stochastacy/examples/thermostatfleet/ThermostatFleetGrafanaAssetsSpec.scala`

## Files Modified

- `core/src/main/scala/stochastacy/demo/model.scala` — add per-region and cross-region `DemoMetric` variants
- `docs/handoffs/ips-handoff.md` — update to reference thermostat fleet demo
- `docs/roadmaps/ips-phase3.md` — add slice 11 entry
- `CLAUDE.md` — add thermostat fleet demo key files

## Test Strategy

- Unit: scenario config validation, sampler behavior in isolation, CLI argument parsing
- Component: single-trial runner produces correct result shape for single-region and multi-region
- Integration: full generate/stage roundtrip with H2, staged record verification
- Asset: Grafana dashboard JSON validity
- Regression: all existing order-tracking tests remain green
