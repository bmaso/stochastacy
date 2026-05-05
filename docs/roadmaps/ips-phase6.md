# IPS Phase 6 — Close the Gap

## Goal

Phase 6 completes the DynamoDB simulation component with the features required to support the
final ThermoFleet multi-service demo, and closes the remaining simulation accuracy gaps deferred
from Phase 5. The ThermoFleet demo requires API Gateway, Lambda, SQS, DynamoDB, and S3 — none
of those other services are simulated yet. Phase 6 therefore does not deliver the full
multi-service demo. Instead, it delivers:

1. The remaining DynamoDB simulation features (read consistency, TTL, reactive auto-scaling,
   multi-table composition).
2. Three accuracy features deferred from Phase 5 (ReplicationLatency metric, SystemErrors,
   SuccessfulRequestLatency) — included here because simulation accuracy is a prerequisite for
   any persuasive demo.
3. A **DynamoDB capstone demo** that exercises every Phase 6 feature using workloads modeled
   after the ThermoFleet scenarios — four tables, realistic traffic shapes, polar-vortex burst,
   and the key "on-demand vs. provisioned+auto-scaling breakeven" question — without requiring
   any other AWS service to be simulated.

When the other service simulators are built (in later phases), the ThermoFleet workload
definitions and multi-table scaffold from this phase become the DynamoDB layer of the full
multi-service simulation.

---

## Phase-6 Implementation Slices

### Status

| Slice | Status | Summary |
|-------|--------|---------|
| 1. Read Consistency RCU Accounting | Planned | Verify/complete 0.5 RCU for eventually-consistent reads vs. 1.0 RCU for strongly-consistent reads |
| 2. TTL | Planned | Ring-buffer write history; tick-accurate deletion rate derived from past write volume |
| 3. Reactive Auto-Scaling | Planned | External `DynamoDbAutoScaler` component; policy-driven `UpdateProvisionedCapacity` events with reaction delay |
| 4. Multi-Table Simulation Framework | Planned | Composable runner for N parallel table instances with shared tick clock and unified TrialResult |
| 5. DynamoDB Capstone Demo | Planned | Four-table ThermoFleet-inspired workload; all Phase 6 features exercised; Grafana dashboard |
| 6. ReplicationLatency Metric | Planned | Surface tick-delta from `ReplicationCoordinator` as `ReplicationMetricEvent.ReplicationLatency`; per-destination-region panel |
| 7. SystemErrors | Planned | Bernoulli error model in `TableStorageStage`; `SystemErrorResponse`; no-consumption no-state-mutation guarantee |
| 8. SuccessfulRequestLatency | Planned | Log-normal latency samples per admitted non-errored request; P50/P95/P99 rollup; latency panels in dashboards |

---

### 1. Read Consistency RCU Accounting

**Goal:** Correct per-read RCU consumption for the two consistency modes.

AWS DynamoDB charges 0.5 RCU per 4 KB for eventually-consistent reads and 1.0 RCU per 4 KB
for strongly-consistent reads. The `readConsistency: ReadConsistency` field already exists on
`ThermostatFleetScenarioConfig` and is passed into table config. The question is whether the
`TableSamplingStage` RCU calculation actually applies the 0.5× factor for eventual consistency.

This slice verifies the calculation end-to-end and corrects it if needed. If already correct, the
deliverable is a targeted test that asserts the 2× cost difference between the two modes for an
identical read workload.

The ThermoFleet tables use both modes: the Device Registry and Alerts tables use eventual
consistency (high-volume reads where stale data is acceptable); the Commands table device-poll
path may use strongly-consistent reads (a device must not miss a pending command).

---

### 2. TTL

**Goal:** Accurate storage-cost modeling for tables with time-to-live expiry.

DynamoDB TTL asynchronously deletes items whose TTL attribute has passed. For simulation
purposes, deletions are applied deterministically: the number of items deleted at tick T equals
the number of items written at tick T − `ttlPeriodTicks`. This is accurate for any traffic
shape because it tracks the actual write history rather than assuming a steady-state rate.

**Note:** The algorithm described here is advisory. The exact formulation — bucket granularity,
interaction with partition count evolution, behavior when `ttlPeriodTicks > simulationTicks`,
and handling of TTL jitter (real AWS TTL deletions have up to 48-hour lag) — is likely to be
iterated on during implementation. The core invariant that must hold: deletion volume at tick T
is a function of write volume at tick T − ttlPeriod, not of current table size.

**Implementation sketch:**

New config type:
```
DynamoDbTable.TtlModel(
  ttlPeriodTicks: Long,
  historyBucketTicks: Int = 60   // aggregate writes into N-tick buckets to bound memory
)
```

`SummaryTableState` gains a ring buffer of write-count buckets with capacity
`ceil(ttlPeriodTicks / historyBucketTicks)`. At each tick boundary:
1. Record writes-this-tick into the current bucket.
2. Compute expired items: write count from the bucket that is exactly `ttlPeriodTicks` ago.
3. Subtract from `estimatedItemCount` and `estimatedStorageBytes`.
4. Advance the ring buffer.

Edge case: if `ttlPeriodTicks > simulationTicks`, the look-back bucket never exists → zero
deletions within the run. This is correct: items written during the simulation have not yet
aged out.

New `DemoMetric` cases: `TtlDeletesPerTick` (rate), `EstimatedItemCount` (level) — giving the
demo dashboard visibility into storage stabilization over time.

---

### 3. Reactive Auto-Scaling

**Goal:** A reactive DAS-style controller that adjusts provisioned capacity in response to
observed table load, without a pre-computed schedule.

This is the most architecturally novel slice. Requires a design discussion before
implementation to resolve the feedback-arc question (see below).

**Motivation:** The core simulation question — "at what fleet size does on-demand beat
provisioned with auto-scaling?" — requires showing the *lag window* during which demand has
spiked but auto-scaling has not yet reacted. A pre-computed reconfiguration schedule cannot
capture this because the schedule must be known before the simulation runs; real auto-scaling
reacts to observed consumption, which is only known during the run.

**Architecture:**

`DynamoDbAutoScaler` is a new component external to the table. It connects between the table's
metric outlet and `componentOfManaged`'s management inlet:

```
table.metricOut  →  DynamoDbAutoScaler  →  componentOfManaged.managementIn
```

The auto-scaler consumes `ConsumedCapacitySnapshot` events, maintains a rolling utilization
window, and emits `UpdateProvisionedCapacity` management events after a configurable reaction
delay. It does not emit events during the on-demand phase or during a cooldown window after a
recent scale event.

New config type:
```
DynamoDbAutoScaler.Policy(
  targetUtilization: Double,         // e.g. 0.7
  reactionDelayTicks: Int,           // e.g. 120 (2 minutes)
  evaluationWindowTicks: Int,        // rolling window for utilization averaging
  scaleDownCooldownTicks: Int,       // e.g. 900 (15 minutes)
  minReadCapacityUnits: Long,
  maxReadCapacityUnits: Long,
  minWriteCapacityUnits: Long,
  maxWriteCapacityUnits: Long
)
```

**Design question (resolve before implementation):** In Pekko Streams, a graph with a feedback
arc (output feeds back to input of the same component) requires explicit cycle-breaking
mechanisms. Two candidate approaches:

- **External coordinator**: the auto-scaler runs as a separate Pekko actor (not a stream stage)
  that subscribes to metric events via a materialized sink and publishes management events via
  a `Source.queue`. This keeps the graph acyclic at the cost of actor/stream boundary overhead.
- **Internal async feedback**: the auto-scaler is a `GraphStage` with both a metric inlet and a
  management outlet, connected to the table component via a `BroadcastMerge` pattern with an
  async boundary. Keeps everything in the streaming model.

The external-coordinator approach is simpler and mirrors how real DAS works (CloudWatch metrics
→ Application Auto Scaling service → DynamoDB API calls). It is the preferred candidate unless
the async boundary introduces tick-ordering hazards.

---

### 4. Multi-Table Simulation Framework

**Goal:** A composable runner that wires N independent DynamoDB table instances against a
shared tick clock and aggregates their results into a unified trial output.

The ThermoFleet capstone demo needs to run four tables in parallel (Device Registry, Telemetry,
Commands, Alerts), each with its own config and use-case sampler, and combine their per-tick
metrics and cost summaries into a single `TrialResult`. This slice builds that scaffold.

**Design principles:**

- Each table instance is independent; no shared state between tables.
- The tick clock is shared: all tables advance together, preserving the timed-event protocol
  invariants across the combined simulation.
- Per-table cost and metric results are namespaced (e.g., `DemoMetric` cases carry a
  `tableName: String` dimension, or the `TrialResult` carries a `Map[String, TableTrialResult]`).
- The framework should be general enough to point toward the eventual YAML/JSON runner vision
  without prematurely implementing it. At this stage: typed Scala config composition is
  sufficient; YAML/JSON deserialization is deferred.

This slice does not attempt to model inter-table dependencies (e.g., a Lambda that reads from
Registry and writes to Telemetry). That coordination belongs to the Lambda and orchestration
layers, which are future-phase work.

---

### 5. DynamoDB Capstone Demo

**Goal:** A runnable demo that exercises all Phase 6 features using ThermoFleet-inspired
workloads, producing a Grafana dashboard that answers the key simulation questions.

This demo does not simulate API Gateway, Lambda, SQS, or S3. It simulates only the DynamoDB
layer of ThermoFleet: four tables with workloads shaped to mimic what those services would
generate.

**Tables and configurations:**

| Table | Billing mode | TTL | Auto-scaling | Read consistency | Notes |
|-------|-------------|-----|-------------|-----------------|-------|
| Device Registry | On-demand | No | N/A | Eventual | Low write, moderate read, uniform key distribution |
| Telemetry | Provisioned + auto-scaling | Yes | Yes | Eventual | High write, per-device partitions, hot-partition risk during polar vortex |
| Commands | On-demand | No | N/A | Strong | Low volume; device polls must not miss commands |
| Alerts | On-demand | No | N/A | Eventual | Medium write during events; GSI for time-range queries |

**Workload scenarios:**

- *Steady state* — 50,000 devices at ~167 writes/second, light read traffic. Establishes
  baseline costs and shows TTL-stabilized telemetry table size.
- *Morning rush* — Consumer API read traffic spikes 8–10×. Commands table and Device Registry
  bear the load. Shows per-table cost contribution shift.
- *Polar vortex* — Telemetry write volume spikes 5×; 15% of devices report faults simultaneously,
  driving a surge in Alerts writes. The hot-partition and auto-scaling interaction on the
  Telemetry table is the key visual: the lag window where throttles fire before auto-scaling
  catches up.
- *Combined peak* — Polar vortex + maximum fleet size (100,000 devices). Worst-case budget.

**Key simulation questions answered by the dashboard:**

- At what fleet size does on-demand Telemetry table become cheaper than provisioned+auto-scaling?
- What does the auto-scaling lag cost in throttled requests during the polar vortex?
- How much does TTL period affect steady-state storage cost? (1-hour vs. 24-hour retention)
- What is the per-table cost breakdown at steady state vs. polar vortex?

**Grafana dashboard:** Per-table cost panel, Telemetry table write capacity and auto-scaling
timeline, throttle rate during polar vortex, estimated item count with TTL attenuation, and the
breakeven fleet-size comparison panel.

---

### 6. ReplicationLatency Metric

**Goal:** Surface the replication lag already computed inside `ReplicationCoordinator` as an
observable metric, completing the Global Tables simulation output.

`ReplicationCoordinator` already computes the lag between when a write enters the lag queue at
the origin region (origin tick) and when it is applied at the destination (apply tick). This
tick-delta drives the internal delivery schedule but is never emitted as a metric event.

Introduce `ReplicationMetricEvent.ReplicationLatency(eventTime, sourceRegion,
destinationRegion, latencyTicks: Long)` emitted by `ReplicationCoordinator` each time a
replicated write is applied. Since 1 tick = 1 second, `latencyTicks × 1000` is directly
comparable to CloudWatch's millisecond `ReplicationLatency` metric.

Add `DemoMetric.ReplicationLatency(sourceRegion, destinationRegion)` with LAST rollup (the most
recently observed lag per window, matching the CloudWatch Average statistic visually). Update
the multi-region thermostat fleet Grafana dashboard with a replication latency panel per
destination region.

---

### 7. SystemErrors

**Goal:** Model the small but non-zero transient failure rate present in real DynamoDB,
making admission-success no longer synonymous with request success.

Add `systemErrorRate: Double = 0.0` to `DynamoDbTable.Config`, with validation that the value
is in `[0.0, 1.0)`. The default of `0.0` means all existing tests and demos are unaffected
unless they explicitly configure a non-zero rate.

In `TableStorageStage`, after admission but before state mutation, draw a Bernoulli sample
against `systemErrorRate`. On a hit: emit `SystemErrorResponse` and
`StorageMetricEvent.SystemError`; skip all state mutation, consumption events, and index
maintenance propagation. This follows the same validate-then-mutate split already used for
item-collection size limit enforcement.

Add `DemoMetric.SystemErrorCount` with SUM rollup. Demo configs leave `systemErrorRate` at
`0.0` unless a scenario is explicitly exploring error behavior; no dashboard changes are
required for the default case.

Tests: confirm that system-errored requests produce no consumption events and no state change;
confirm the error rate across many trials converges to the configured probability.

---

### 8. SuccessfulRequestLatency

**Goal:** Emit a plausible per-request latency sample for every successfully admitted and
processed request, making `SuccessfulRequestLatency` non-empty in the simulation output.

The simulator has no sub-tick time resolution — all admitted requests within a tick are
processed instantaneously. Latency is therefore a **stochastic sample emitted as a metric**,
consistent with the project's general stochastic-summary-oriented modeling principle.

Add `DynamoDbTable.LatencyModel` as a new config type carrying log-normal (μ, σ) parameter
pairs keyed by operation type (`GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`,
`Scan`), plus per-factor adjustments:
- `bytesPerMsMedianIncrement: Double` — linear item-size scaling applied to μ
- `stronglyConsistentMedianIncrementMs: Double` — additive to μ for strongly-consistent reads
- `batchCallOverheadMs: Double` — added once per batch call before per-item sampling

`DynamoDbTable.Config` gains `latencyModel: Option[DynamoDbTable.LatencyModel]` defaulting to
`None` (no latency samples emitted). A companion `DynamoDbTable.LatencyModel.awsDefault`
provides conservative AWS-calibrated parameters: P50 ~1–2 ms for single-digit-KB `GetItem`,
~2–4 ms for `PutItem`, ~5–20 ms for `Query`/`Scan` depending on item count.

`TableStorageStage` samples a latency value after a request has been successfully processed
(not for throttled, system-errored, or item-collection-rejected requests) and emits
`StorageMetricEvent.SuccessfulRequestLatency(ms: Double, operation: DynamoDbOperationKind)`.

Add `DemoMetric.LatencyP50(operation)`, `DemoMetric.LatencyP95(operation)`,
`DemoMetric.LatencyP99(operation)` with percentile rollup aggregation (computed across the
window's raw samples per trial, then averaged across trials). Update both thermostat fleet
Grafana dashboards with a latency distribution panel per operation type.
