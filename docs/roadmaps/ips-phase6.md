# IPS Phase 6 — Close the Gap

## Goal

Phase 6 delivers every DynamoDB feature needed to implement the full Tier 1 Thermostat Fleet
demo described in `docs/stochastacy-mvp-and-launch-plan.md`. The Tier 1 demo is the DynamoDB
layer of the complete ThermoFleet multi-service architecture (Lambda, API Gateway, SQS, S3,
DynamoDB). Those other services are not yet simulated; Phase 6 delivers only the DynamoDB side,
but does so completely.

Concretely, Phase 6 delivers:

1. The remaining DynamoDB simulation features needed for realism: read consistency, TTL,
   reactive auto-scaling, multi-table composition.
2. Three accuracy features deferred from Phase 5: ReplicationLatency metric, SystemErrors,
   SuccessfulRequestLatency.
3. Two billing dimensions that materially affect real-world DynamoDB costs and are visible in
   the Tier 1 demo: **DynamoDB Transactions** (2× RCU/WCU) and **PITR pricing** (~$0.20/GB-month).
4. A **DynamoDB capstone demo** that exercises every Phase 6 feature using workloads modeled
   after the ThermoFleet scenarios — four tables, realistic traffic shapes, polar-vortex burst,
   and the key "on-demand vs. provisioned+auto-scaling breakeven" question.

**Out of scope:** DynamoDB Streams. Streams pricing will be added when the Lambda simulator
is built (Phase 1 of the product roadmap), since the primary use case is Lambda triggers.

When the other service simulators are built, the ThermoFleet workload definitions and
multi-table scaffold from this phase become the DynamoDB layer of the full multi-service
simulation.

---

## Phase-6 Implementation Slices

### Status

| Slice | Status | Summary |
|-------|--------|---------|
| 1. Read Consistency RCU Accounting | Done | Already implemented: `TableThroughputMath.readCapacityUnitsFor` applies 0.5× for eventual consistency; tests verify 2× difference |
| 2. TTL | Done | `TtlSampler` / `SimpleTtlSampler` ring-buffer; `TtlExpiry` StorageOutcome; `TtlItemsExpired` + `EstimatedItemCount` metrics; `StorageBytesDelta` cascade; `DynamoDbTable.Config.ttlSampler`; 12 new tests |
| 3. Reactive Auto-Scaling | Done | `DynamoDbAutoScaler` actor-based coordinator; `Policy` with rolling window, reaction delays, cooldowns; `autoScalerPolicy` on `ThermostatFleetScenarioConfig`; 7 tests |
| 4. Multi-Table Simulation Framework | Done | `MultiTableScenarioConfig` / `MultiTableEntry`; `ThermostatFleetMultiTableSingleTrialRunner`; namespaced `Table:<name>:*` metrics; unified `TrialResult` |
| 5. DynamoDB Capstone Demo | Done | Four-table ThermoFleet workload; all Phase 6 features exercised; Grafana capstone dashboard |
| 6. ReplicationLatency Metric | Done | `ReplicationMetricEvent.ReplicationLatency` emitted by `ReplicationCoordinator`; `DemoMetric.ReplicationLatency`; per-destination-region panel in multi-region dashboard |
| 7. SystemErrors | Done | `systemErrorRate: Double` on `DynamoDbTable.Config`; Bernoulli draw in `TableStorageStage`; `StorageMetricEvent.SystemError`; `DemoMetric.SystemErrorCount`; panels in all three thermostat-fleet dashboards |
| 8. SuccessfulRequestLatency | Done | Log-normal latency samples in `TableStorageStage`; `StorageMetricEvent.SuccessfulRequestLatency`; P50/P95/P99 rollup; latency panels in all three thermostat-fleet dashboards |
| 9. DynamoDB Transactions | Done | `TransactWriteItems` (2× WCU/item) and `TransactGetItems` (2× RCU/item, always strongly consistent); new request/response/sample/shaped/admitted types; `transactionalWriteCapacityUnitsFor` / `transactionalReadCapacityUnitsFor` in `TableThroughputMath`; all-or-nothing LSI limit and system-error checks; `WriteAsPutSample` adapter; per-item state mutation; Commands table in capstone uses transactions; 10 new tests |
| 10. PITR Pricing | Planned | `pointInTimeRecoveryEnabled: Boolean` on `DynamoDbTable.Config`; continuous storage charge at ~$0.20/GB-month; `DemoMetric.TablePITRCumulativeCost`; panel in capstone dashboard |

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

**Goal:** Model DynamoDB TTL expiry so that table storage self-attenuates over time in
proportion to past write volume, rather than growing monotonically. Storage costs should
reflect the live item population, not cumulative writes. This enables the demo question:
"how much does TTL period affect steady-state storage cost?"

---

**Current behaviors inconsistent with the goal:**

1. **No TTL field in config.** `DynamoDbTable.Config` has no TTL-related field. There is no
   TTL code anywhere in the codebase.

2. **`SummaryTableState` only mutates via explicit admitted operations.** `currentItemCount`
   and `currentTotalItemBytes` are updated only by `recordSuccessfulWrite` and
   `recordSuccessfulDelete`, called from `TableStorageStage` in response to admitted `PutItem`,
   `UpdateItem`, and `DeleteItem` requests. There is no automatic expiration path — items
   persist indefinitely unless explicitly deleted by a use-case sampler.

3. **`StorageBytesDelta` consumption events are only emitted by explicit writes and deletes.**
   `DynamoDbTimeBasedUsageTotals` accumulates byte-ticks by integrating `StorageBytesDelta`
   events tick by tick. TTL-driven deletions emit no such events today, so the time-based
   storage accumulator — and therefore the storage cost calculation — is completely blind to
   TTL expiry.

4. **Without TTL, long simulations produce unboundedly growing storage.** Any simulation with
   high write volume and no explicit deletes in the use-case sampler will have monotonically
   growing `currentItemCount` and `currentTotalItemBytes`, making storage cost projections
   unrealistic for any table with a real retention policy.

5. **Item count is internal state only — not observable in dashboards.** No `DemoMetric` case
   exposes the current live item count. Without a new metric, TTL-driven storage attenuation
   would be invisible in the Grafana dashboard even after implementation.

---

**How stochastacy will behave once the slice is implemented:**

**Sampler-driven TTL expiry.** The sampler is already the source of truth for write volume and
item sizes — it generated the writes, so it inherently knows the write history. Rather than a
ring buffer in `SummaryTableState`, the history structure belongs in the sampler, which
produces a `TtlExpirySample` at each tick. `TableStorageStage` (or a parallel TTL path)
consumes these samples and emits `StorageBytesDelta` consumption events for each affected
target. This keeps all stochastic decisions in the sampler layer, consistent with the project's
design principle.

**Intermediate modifications are accounted for.** Between tick T−ttlPeriod (when items were
written) and tick T (when TTL fires), two things can happen:

- *Explicit deletes*: Some fraction of those items have already been deleted before TTL fires.
  The sampler models the delete rate and applies a stochastic survival probability to the
  cohort written at tick T−ttlPeriod. The expiry count at tick T is reduced accordingly.
- *Updates*: Items that were written and then updated before TTL fires still expire, but the
  bytes freed reflect the updated size, not the original write size. The sampler estimates the
  byte size at expiry time using the update rate and size distribution rather than assuming it
  equals the original write size.

**TTL expiry cascades to GSIs and LSIs.** When DynamoDB TTL deletes an item it is a cascading
deletion: the base table item is freed, and every GSI and LSI projection of that item is also
deleted. The `TtlExpirySample` carries per-target storage deltas — one for the base table, one
per GSI, one per LSI — computed from the projection type and the sampler's estimate of
projected attribute sizes. These drive separate `StorageBytesDelta` events for each affected
`DynamoDbTarget`, mirroring the fan-out pattern the index maintenance graph already uses for
explicit deletes.

**Edge case:** If `ttlPeriodTicks > simulationTicks`, the look-back history never reaches the
write cohort and no deletions fire within the run. This is correct: items written during the
simulation have not yet aged out.

**New config and metrics.** `DynamoDbTable.Config` gains `ttlModel: Option[DynamoDbTable.TtlModel]`
(defaulting to `None` — all existing behavior unchanged). New `DemoMetric` cases — at minimum
`EstimatedItemCount` (a level metric showing current live item count per tick) — make the
storage stabilization curve visible in the dashboard.

**Note:** The exact formulation of the survival probability model, the byte-size-at-expiry
estimate, the history bucket granularity, and the handling of TTL jitter (real AWS TTL
deletions can lag up to 48 hours) are all likely to be iterated on during implementation. The
core invariant that must hold: expiry volume at tick T is a function of write volume at tick
T−ttlPeriod and the intermediate modification rates — not a function of current table size.

---

### 3. Reactive Auto-Scaling

**Goal:** A reactive DAS-style controller that adjusts provisioned capacity in response to
observed table load, without a pre-computed schedule.

**Motivation:** The core simulation question — "at what fleet size does on-demand beat
provisioned with auto-scaling?" — requires showing the *lag window* during which demand has
spiked but auto-scaling has not yet reacted. A pre-computed `ReconfigurationSchedule` cannot
capture this because the schedule must be known before the simulation runs; real auto-scaling
reacts to observed consumption, which is only known during the run.

---

**Architecture: actor-based external coordinator**

`DynamoDbAutoScaler` is a Pekko actor (not a stream stage) that sits between the table's metric
outlet and `componentOfManaged`'s management inlet:

```
table.metricOut  →  Sink.foreach(actor !)  →  [actor]  →  Source.queue  →  componentOfManaged.managementIn
```

This mirrors how real DAS works: CloudWatch metrics flow to Application Auto Scaling (the
external controller), which calls the DynamoDB API. Keeping the auto-scaler outside the stream
graph keeps the graph acyclic — no cycle-breaking primitives are needed.

**Wiring in the runner:** `DynamoDbAutoScaler` exposes a simple API — `actor` and
`managementSource` — that the runner uses to wire the graph. `runTrialSingleRegion` is
modestly restructured: when an auto-scaler policy is present, the actor is created before the
graph is built, the metric broadcast gains an extra outlet feeding `Sink.foreach(actor !)`,
and `managementSource` (the actor's `Source.queue`) replaces the static management events
iterator feeding `componentOfManaged.managementIn`. When no policy is configured, the runner
path is unchanged.

The `Source.queue` uses `OverflowStrategy.dropHead` with a small buffer (e.g. 64 elements) so
actor offers never block — management events are rare (one every `scaleUpReactionDelayTicks` at
most) and the buffer will not overflow in practice.

---

**Policy config:**

```scala
DynamoDbAutoScaler.Policy(
  targetUtilization: Double,           // e.g. 0.70 — scale up above, scale down below
  evaluationWindowTicks: Int,          // rolling window for utilization averaging, e.g. 60
  scaleUpReactionDelayTicks: Int,      // ticks from decision to scale-up event, e.g. 120 (2 min)
  scaleDownReactionDelayTicks: Int,    // ticks from decision to scale-down event, e.g. 900 (15 min)
  scaleUpCooldownTicks: Int,           // min ticks between scale-up events, e.g. 120
  scaleDownCooldownTicks: Int,         // min ticks between scale-down events, e.g. 900
  minReadCapacityUnits: Long,
  maxReadCapacityUnits: Long,
  minWriteCapacityUnits: Long,
  maxWriteCapacityUnits: Long
)
```

AWS does not publish the exact DAS algorithm or reaction timings. The defaults above are
conservative estimates derived from observed real-world DAS behavior. Scale-down is
significantly slower than scale-up by default to reflect the real asymmetry.

---

**Utilization metric and window management:**

The auto-scaler consumes `AdmissionMetricEvent.ProvisionedCapacityUtilization` events (not
`ConsumedCapacitySnapshot`) because each event already carries both consumed and provisioned
capacity for the completed tick. The utilization ratio `consumed / provisioned` is computed
directly from the event, so the rolling window always reflects the correct denominator even as
provisioned capacity changes.

On each scale event (up or down), the rolling utilization window is flushed. The subsequent
cooldown period absorbs the post-scale measurement noise while the window refills.

---

**Scale-up and scale-down decisions:**

On every `ProvisionedCapacityUtilization` event (for reads and writes independently):

- If rolling-window average > `targetUtilization` and no scale-up is pending and cooldown has
  elapsed: enqueue a scale-up decision with fire tick = `currentTick + scaleUpReactionDelayTicks`.
  Target new capacity = `ceil(consumed / targetUtilization)`, clamped to `maxCapacityUnits`.
- If rolling-window average < `targetUtilization × scaleDownThreshold` (e.g. 0.5×, configurable)
  and no scale-down is pending and cooldown has elapsed: enqueue a scale-down decision with
  fire tick = `currentTick + scaleDownReactionDelayTicks`. Target new capacity =
  `ceil(consumed / targetUtilization)`, clamped to `minCapacityUnits`.

Pending decisions are keyed by (dimension: read/write, fireTick). On each `Tick(T)` event,
the actor drains any decisions with `fireTick <= T` and offers `UpdateProvisionedCapacity`
events to `Source.queue`.

---

**On-demand phase silence:**

The auto-scaler is silent when `BillingModeSnapshot` indicates on-demand mode. It activates
only in provisioned mode. In a mixed-mode scenario, it ignores all events until the mode switch
tick and starts its rolling window fresh at that point.

---

**GSI scaling:** Deferred. This slice scales only the base table. Per-GSI independent scaling
is a candidate follow-on slice within Phase 6.

---

**New `DemoMetric` cases:**

- `ProvisionedReadCapacityUnits` and `ProvisionedWriteCapacityUnits` already exist (Phase 4).
  No new metrics are required — the existing provisioned capacity time series will show the
  step changes as auto-scaling fires, which is the key visual for the lag-window analysis.

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

---

### 9. DynamoDB Transactions

**Goal:** Model `TransactWriteItems` and `TransactGetItems` — DynamoDB's atomic multi-item
APIs — so their 2× cost premium is accurately reflected in simulated workloads.

AWS charges:
- `TransactWriteItems`: 2 WCU per 1 KB written, per item (vs. 1 WCU for `PutItem`/`UpdateItem`).
  GSI/LSI maintenance writes triggered by those items are billed at the normal 1× rate.
- `TransactGetItems`: 2 RCU per 4 KB read, per item. The consistency is always strongly
  consistent — the API does not support eventual consistency.

Up to 25 items and 4 MB total per transaction call. The whole transaction either succeeds or
fails atomically.

The demo motivation is the Commands table in the capstone: real device-fleet apps use
`TransactWriteItems` to atomically update a command's status and write an audit record. Without
this slice, the Commands table's cost is modeled at half its real value.

---

**Current behaviors inconsistent with the goal:**

1. **No transaction request or response types exist.** `DynamoDBRequest` in `op_events.scala`
   has `GetItemRequest`, `PutItemRequest`, `UpdateItemRequest`, `DeleteItemRequest`,
   `QueryRequest`, `ScanRequest`, and `PartiQLQueryRequest`. There is no
   `TransactWriteItemsRequest` or `TransactGetItemsRequest`, and no corresponding response
   types. A use-case sampler has no way to express a transactional operation at all.

2. **`DynamoDbOperationKind` has no transaction cases, and `fromRequest` would crash.** The
   `fromRequest` function pattern-matches on all known `DynamoDBRequest` subtypes. If a
   transaction request were submitted, it would throw a `MatchError`. The latency model, metric
   events, and consumption dispatch all key off `DynamoDbOperationKind`; every one of them
   would break.

3. **No transaction sample types.** `sample.scala` has no `TransactWriteItemsSample` or
   `TransactGetItemsSample` traits. There is no contract by which a `UseCaseSampler` can return
   a list of per-item sub-samples for a transactional call. The pipeline has no representation
   for "a batch of N write samples sharing one all-or-nothing admission decision."

4. **No shaped or admitted transaction types.** `shaped_request.scala` has no
   `ShapedTransactWriteItemsRequest` or `ShapedTransactGetItemsRequest`. The sampling stage
   would have no way to produce a shaped envelope that carries both the per-item sub-samples and
   the correct total 2× throughput demand. Correspondingly, `admitted_requests.scala` has no
   admitted variants for transactions.

5. **`TableThroughputMath` has no 2× path.** `writeCapacityUnitsFor` computes plain 1× WCU.
   `readCapacityUnitsFor` computes 0.5× or 1× depending on consistency. If transactional items
   were manually routed through existing request types as a workaround, every item's cost would
   be silently undercounted by half.

6. **`TableStorageStage` would silently drop unknown sample types.** The `decisionFlow`,
   `consumptionForSample`, `metricsForSample`, and `responseForSample` functions all dispatch on
   known `AdmittedRequestSample` subtypes. An unknown type falls to `case _ => ()` in the
   mutation path and `case _ => Nil` in the consumption and metric paths. A transaction would be
   admitted and then silently swallowed — no cost, no metrics, no state change.

7. **`TransactGetItems` strong-consistency-only semantics are unenforceable.** `GetItemRequest`
   carries a `readConsistency` field that can be `EventuallyConsistent` or `StronglyConsistent`.
   The current type system has no way to enforce that transactional reads are always strongly
   consistent, so a transactional read routed through `GetItemRequest` could silently use the
   cheaper consistency mode, understating cost.

8. **`ThermostatFleetBehavior` never generates transaction requests.** The Commands table use
   case emits individual `PutItemRequest` calls. The Commands table cost is currently modeled at
   1× WCU — half the realistic cost for an app using `TransactWriteItems` for atomic device
   command acknowledgment.

---

**How stochastacy will behave once the slice is implemented:**

Transaction requests enter the pipeline like any other request. The sampler produces a
`TransactWriteItemsRequest` or `TransactGetItemsRequest`; it flows through sampling, admission,
and storage stages as a single pipeline element representing the entire transaction.

**Sampling stage** invokes the use-case sampler once per sub-item to produce N
`WriteItemSample` / `GetItemSample` results. The shaped request carries all N samples and a
`throughputDemand` equal to the sum of `transactionalWriteCapacityUnitsFor(itemBytes)` across
all items (i.e., 2× per item). Index maintenance plans are derived per item at 1× — GSI/LSI
maintenance is not doubled. For `TransactGetItems`, demand is 2× strongly-consistent RCU per
item; the read consistency is hardcoded to strong regardless of the table's default.

**Admission is all-or-nothing.** The shaped transaction's total `throughputDemand` is checked
against the remaining tick budget exactly as any other shaped request. If the budget is
insufficient, the entire transaction throttles as a unit — no partial admission. This requires
no change to the admission decision logic itself; only the demand computation changes.

**`TableThroughputMath`** gains `transactionalWriteCapacityUnitsFor` (returns
`2 × writeCapacityUnitsFor`) and `transactionalReadCapacityUnitsFor` (returns
`2 × readCapacityUnitsFor(_, StronglyConsistent)`).

**Storage stage** processes each sub-item sequentially. Before any mutation, a single system
error draw covers the whole transaction: if it fires, the transaction emits one
`SystemErrorResponse` — no mutation, no consumption, no maintenance for any sub-item. If it
passes, each sub-item is applied in sequence (`recordSuccessfulPut` / `recordSuccessfulUpdate`
/ `recordSuccessfulDelete`), the TTL sampler is notified per item, and index maintenance plans
are forwarded per item. Consumption events are emitted at 2× WCU/RCU per item.

**`ThermostatFleetBehavior`** is updated so the Commands table use case generates
`TransactWriteItemsRequest` calls — a two-item transaction atomically updating command status
and writing an audit record. The Commands table's simulated cost doubles relative to its current
value for ticks where transactional writes fire.

**Key simulation question:** "How much do transactions inflate the Commands table bill compared
to non-transactional puts, and how does that overhead scale with fleet size?"

---

### 10. PITR Pricing

**Goal:** Model the continuous storage charge for Point-In-Time Recovery (PITR), making
PITR-enabled tables show their true cost in the simulation output.

AWS charges ~$0.20/GB-month for PITR-enabled tables, billed continuously on the current table
size (same footprint as storage: base table + all GSI/LSI projections). This is roughly 80% of
the base storage rate and is frequently overlooked in cost estimates.

**Scope:**

- New field: `pointInTimeRecoveryEnabled: Boolean = false` on `DynamoDbTable.Config` (and
  forwarded from `ThermostatFleetScenarioConfig`). Defaults to `false`; all existing tests and
  demos are unaffected unless they opt in.
- New rate field: `pitrStoragePricePerGiBSecond: BigDecimal` in `DynamoDbPricingRates.RateSet`,
  defaulting to the AWS standard rate ($0.20/GB-month expressed per-second).
- Pricing: when PITR is enabled, `DynamoDbCostBreakdown.price` adds
  `currentStorageBytes × pitrRate × tickDuration` alongside the existing storage charge.
- New `DemoMetric.TablePITRCumulativeCost(tableName)` with SUM rollup. Add a PITR cost line to
  the capstone dashboard's cost panel (the Device Telemetry table is the natural candidate —
  high write volume + TTL makes it both the largest table and the most likely PITR target).

**Key simulation question:** "How much does enabling PITR on the Telemetry table add to the
monthly bill as fleet size grows?"
