# IPS Phase 5 — Improved Accuracy

## Goal

Phase 5 closes the remaining accuracy gaps in the simulator, measured against a concrete
standard: **the simulator should be able to produce a non-empty value for every CloudWatch
standard metric that would be non-zero for a DynamoDB table configured as the thermostat fleet
demo configures it** (CRUD + GSIs + LSI + Global Tables + both billing modes).

The first three items were explicitly deferred from phase 3 (slice 10). The remaining four
emerge from a CloudWatch metric gap analysis conducted after phase 4.

Phase 4's provisioned-mode and reconfiguration work is complete and untouched by this phase.

Phase 5 adds:

- **rWCU as a distinct capacity bucket** — replicated writes at a destination region bill as
  rWCU (replicated write capacity units), not WCU; they have separate admission and separate
  pricing in both on-demand and provisioned modes
- **Tiered cross-region transfer pricing** — real AWS uses tiered per-GB rates for inter-region
  data transfer; the current flat-rate model undercharges at high transfer volumes
- **GSI/LSI support inside `componentOfReplicated`** — secondary indexes on replicated tables
  are currently rejected at construction time; this slice lifts that restriction
- **`ReturnedItemCount` for Query and Scan** — the use-case sampler already produces item-count
  estimates; they are not yet emitted as a metric
- **`ReplicationLatency` metric** — the `ReplicationCoordinator` already models per-link lag
  distributions; the resulting latency values are used internally but never surfaced as a
  `TableMetricEvent`
- **`SystemErrors`** — real DynamoDB has a small but non-zero transient 500-error rate; the
  simulator currently assumes 100% success for all admitted requests
- **`SuccessfulRequestLatency`** — the largest gap; the simulator processes every admitted
  request instantaneously; real DynamoDB has a well-characterized per-operation latency
  distribution driven by operation type, item size, and consistency mode

Phase 5 also closes **pricing accuracy** gaps against the rule: the simulator should accurately
estimate any DynamoDB cost that can be affected by any DynamoDB configuration expressible in
the Phase 5 simulation API. Three gaps apply:

- **Table class (Standard vs. Standard-IA)** — table class is a configurable DynamoDB property
  that shifts storage rates higher and throughput rates lower; the simulator currently has no
  table-class dimension
- **Per-GSI provisioned capacity pricing** — `BillingMode.Provisioned` already carries
  per-GSI RCU/WCU values, but the current cost calculation approximates total provisioned
  capacity as `baseRate × (1 + numGsis)` instead of summing each entity's actual provisioned
  value; this is wrong whenever GSI capacity differs from the base table
- **Reserved capacity discount** — a 1- or 3-year upfront commitment discounts the provisioned
  hourly rate for base-table RCU/WCU; currently the pricing component has no reserved-capacity
  path

Known limitation not addressed in Phase 5: GSI and LSI projected-attribute storage overhead.
Each GSI/LSI stores copies of projected attributes within the partition or in a separate index
structure; this adds to the storage bill but requires sampler-level estimates of projected
attribute sizes per index — a modeling extension beyond the scope of this phase.

Phase 5 is **complete** when the multi-region thermostat fleet demo can be generated, staged,
and visualized showing accurate rWCU billing, tiered transfer costs, GSIs on each replica,
per-operation latency distributions, replication latency, returned item counts, a configurable
system-error rate, and correct pricing across table class, per-GSI provisioned capacity, and
reserved-capacity discount configurations.

---

## Design Anchors

### rWCU as a distinct admission and billing dimension

In real AWS DynamoDB Global Tables, a write that arrives at a destination region — whether
originated there by a client or replicated from another region — consumes **rWCU** at that
destination, not WCU. The rWCU bucket is independent:

- In **on-demand** mode, rWCU bills at a different (lower) rate than WCU
- In **provisioned** mode, the table has a separate rWCU provisioned ceiling alongside its WCU
  ceiling; replicated writes throttle against the rWCU ceiling, not the WCU ceiling

Currently, `ReplicationCoordinator` emits `ReplicatedWriteForRegion` events that bypass
admission entirely and accumulate as `WriteCapacityConsumed` at the destination. Both the
admission bypass and the WCU attribution are wrong.

The fix has two parts:

1. **New consumption event variant** — introduce `ReplicatedWriteCapacityConsumed` (alongside
   the existing `WriteCapacityConsumed`) so rWCU can be priced separately and reported
   separately in dashboards
2. **rWCU admission path in provisioned mode** — `componentOfReplicated` must accept an rWCU
   ceiling in its `BillingMode.Provisioned` config, and replicated writes must be checked
   against that ceiling (with throttling if exceeded). In on-demand mode no ceiling applies,
   but the consumption event type still changes to rWCU

The rWCU ceiling is a per-region config dimension. The `DynamoDbTable.BillingMode.Provisioned`
type must be extended to carry `replicatedWriteCapacityUnits: Option[Long]` (None means the
table is not a replica and rWCU admission does not apply).

### Tiered cross-region transfer pricing

Real AWS data-transfer pricing is tiered. For DynamoDB Global Tables the relevant charge is
per-GB of replicated data transferred between regions. Tiers reset per billing period (one
calendar month in production; one simulation run in the simulator).

The current `CrossRegionTransferPricing` component applies a flat per-source-region per-GB
rate. It has no concept of cumulative volume and cannot express tier transitions.

The fix: `CrossRegionTransferPricing` must accumulate total bytes transferred per source region
across the simulation and apply the correct per-tier rate to each tranche. The pricing config
(`CrossRegionTransferPricingRates`) is extended from a flat rate per source region to a tiered
rate schedule per source region (a `Vector[(thresholdBytes: Long, ratePerGiB: BigDecimal)]`
sorted ascending).

Since the simulator does not model calendar time, the "billing period" is the entire simulation
run. This is consistent with how all other pricing dimensions work.

### GSI/LSI support inside `componentOfReplicated`

`DynamoDbTable.componentOfReplicated` currently validates that `config.globalSecondaryIndexes`
and `config.localSecondaryIndexes` are empty and throws at construction time if they are not.
This was a deliberate scope deferral from slice 10.

Lifting the restriction requires verifying that the replicated-write path through
`TableStorageStage` and the index-maintenance graph already handles admitted samples correctly
regardless of whether they originated from a client or from the replication coordinator. If the
storage stage and index-maintenance graph are already topology-agnostic (they should be, since
they operate on `AdmittedRequestSample` without caring about source), the change may be as
small as removing the validation guard and adding coverage tests.

Any rWCU admission work done in slice 1 of this phase must be consistent with GSI/LSI
configurations: in provisioned mode, replicated writes that hit a replica's GSIs still consume
rWCU from the same rWCU pool (real DynamoDB behavior).

### Request latency as a stochastic sample

The simulator has no sub-tick time resolution — a tick is one second and all admitted requests
within it are processed instantaneously. Latency therefore cannot be modeled as an actual
processing delay in the stream. Instead it is a **stochastic sample emitted as a metric per
admitted, non-errored request**, consistent with the project's general principle of
stochastic-summary-oriented modeling.

Each admitted request receives a latency sample drawn from a **log-normal distribution**
(a good empirical fit for DynamoDB's right-skewed, always-positive latency shape). The
distribution parameters (μ, σ) vary by:

- **Operation type** — GetItem/DeleteItem/PutItem/UpdateItem have different baselines; Query and
  Scan are higher and more variable; batch operations are modeled as the sum of individual
  per-item samples with some per-call overhead
- **Item size** — both the request item size (writes) and the returned item size (reads) add a
  linear increment to the median; very large items (>100 KB) see super-linear growth
- **Consistency** — strongly consistent reads add a fixed median increment (~1 ms) relative to
  eventually consistent reads
- **Index type** — GSI reads use the same distribution as base-table reads of equivalent size;
  LSI reads use the base-table distribution directly (LSI accesses the same partition)

A new `DynamoDbTable.LatencyModel` config type carries the (μ, σ) parameters and per-factor
adjustments. Sensible AWS-calibrated defaults are provided; callers that do not configure a
`LatencyModel` use the defaults. The model is intentionally coarse — its purpose is to make
`SuccessfulRequestLatency` non-empty and plausible, not to be a cycle-accurate DynamoDB
performance model.

Latency is emitted as `StorageMetricEvent.SuccessfulRequestLatency(ms: Double)` from
`TableStorageStage`. The downstream demo pipeline aggregates latency into P50/P95/P99 per
operation type over each time window.

### System errors as a configurable Bernoulli process

A system error is a transient AWS-side failure (HTTP 500) unrelated to throttling, item
collection limits, or conditional-check failures. Real DynamoDB exhibits these at rates on the
order of 0.01–0.1% of requests, varying by service load and region health.

The model is a simple per-request Bernoulli draw: each admitted request has an independent
probability `p` of becoming a system error. Errored requests:
- Emit `SystemErrorResponse` (a new `DynamoDBResponse` variant) instead of the normal response
- Consume no capacity (no `ReadCapacityConsumed` / `WriteCapacityConsumed` events)
- Emit `StorageMetricEvent.SystemError` for downstream counting
- Do not mutate table state and do not trigger index maintenance

`DynamoDbTable.Config` gains a `systemErrorRate: Double` field (default `0.0` — system errors
disabled unless explicitly configured). The check is applied in `TableStorageStage` after
admission but before state mutation, consistent with the validate-then-mutate pattern already
used for item-collection size limit enforcement.

### DynamoDB pricing as a multi-dimensional model

DynamoDB's pricing model has eight independent dimensions: read capacity, write capacity,
replicated write capacity (rWCU), storage, provisioned capacity reservation, reserved capacity
discount, cross-region data transfer, and table class. Phase 5 completes the simulator's
coverage of all eight for configurations expressible through the simulation API.

**Table class** is a per-table configuration (`Standard` or `StandardInfrequentAccess`). The
two classes use identical pricing dimensions but with shifted rates: Standard-IA charges a
higher storage rate and lower throughput rates (both read and write, both on-demand and
provisioned). The structural implication for the simulator: `DynamoDbTable.Config` gains a
`tableClass` field, and `DynamoDbPricingRates` is extended with a parallel Standard-IA rate
set. Reserved capacity is not available for Standard-IA tables.

**Per-GSI provisioned capacity** is independently configured and independently billed. In real
DynamoDB, a base-table write that triggers three GSI maintenance writes checks four independent
capacity ceilings (base table + three GSIs), and the cost of provisioned capacity is the sum of
all four entities' hourly allocations. The simulator's current demo-runner hack of multiplying
the base-table provisioned rate by `(1 + numGsis)` is accurate only when all GSIs are
provisioned at the same rate as the base table. The fix: `ProvisionedCapacityUtilization` must
carry the true per-entity sum, and `DynamoDbPricing` must compute costs from actual per-entity
provisioned values drawn from `BillingMode.Provisioned`.

**Reserved capacity** is a pricing-layer concern only — it does not change simulation behavior.
A `ReservedCapacity` sub-config on `DynamoDbPricingRates` specifies a committed number of base-
table RCU and WCU, a discounted hourly rate for each, and a term (one-year or three-year).
Provisioned RCU/WCU up to the reserved amounts bill at the discounted rate; amounts above
bill at the standard rate. Reserved capacity covers the base table only, not GSIs, and is
unavailable for Standard-IA tables. Validation at config construction enforces these constraints.

---

## Phase-5 Implementation Slices

### Status

| Slice | Status | Summary |
|-------|--------|---------|
| 1. rWCU Consumption Event and On-Demand Billing | **Done** | `ReplicatedWriteCapacityConsumed` event; rWCU accumulation in `DynamoDbUsageTotals`; on-demand rWCU pricing path; multi-region demo and dashboard updated |
| 2. rWCU Provisioned Admission | **Done** | `BillingMode.Provisioned.replicatedWriteCapacityUnits: Option[Long]`; rWCU token-bucket admission in `componentOfReplicated`; `InsufficientReplicatedWriteCapacity` throttle reason; `UpdateProvisionedCapacity` extended for rWCU |
| 3. Tiered Cross-Region Transfer Pricing | **Done** | `CrossRegionTransferPricingRates` uses tiered schedule; `CrossRegionTransferPricing` accumulates cumulative bytes and applies correct per-tranche rate; AWS default tiers added to demo config |
| 4. GSI/LSI Support in `componentOfReplicated` | **Done** | Construction-time guard was already absent; verified storage and index-maintenance graph are topology-agnostic; test-completion slice: provisioned-mode + GSI rWCU test added to `DynamoDbTableReplicatedSpec`; multi-region GSI metric assertion added to `ThermostatFleetSingleTrialRunnerSpec` |
| 5. ReturnedItemCount for Query and Scan | **Done** | `StorageMetricEvent.ReturnedItemCount` emitted unconditionally per admitted Query/Scan; `DemoMetric.ReturnedItemCount(operation)` with SUM rollup; both single-region runner, multi-region runner, and mixed-mode runner collect and emit the metric; "Returned Item Count by Operation" panel added to thermostat-fleet and mixed-mode dashboards; WCU consumed-vs-provisioned panel enhanced with p50/p75/p95 percentile bands |
| 6. ReplicationLatency Metric | Future | |
| 7. SystemErrors | Future | |
| 8. SuccessfulRequestLatency | Future | |
| 9. Table Class: Standard vs. Standard-IA | Future | |
| 10. Per-GSI Provisioned Capacity Pricing Accuracy | Future | |
| 11. Reserved Capacity Discount | Future | |
| 12. Multi-Region Demo Update | Future | |

---

### 1. rWCU Consumption Event and On-Demand Billing

Introduce `DynamoDbConsumptionEvent.ReplicatedWriteCapacityConsumed` as a new variant alongside
`WriteCapacityConsumed`. The replication coordinator emits this type (instead of
`WriteCapacityConsumed`) for all replicated-write consumption events at the destination region.

Add an rWCU pricing path to `DynamoDbPricing`. In on-demand mode, rWCU bills at the AWS
on-demand rWCU rate (currently $0.000975 per rWCU, vs $0.00130 per WCU). In provisioned mode,
rWCU pricing is handled by the capacity reservation path (slice 2).

Update `DynamoDbUsageTotals` to accumulate `replicatedWriteCapacityUnits` separately from
`writeCapacityUnits`. Update the multi-region demo metrics and Grafana dashboard to display
rWCU alongside WCU.

All existing single-region tests are unaffected. Multi-region tests must be updated to assert
`ReplicatedWriteCapacityConsumed` rather than `WriteCapacityConsumed` for replicated writes.

### 2. rWCU Provisioned Admission

Extend `DynamoDbTable.BillingMode.Provisioned` with `replicatedWriteCapacityUnits: Option[Long]`
(defaulting to `None`; only meaningful for replicated tables). Update `componentOfReplicated`
to require this field when `billingMode` is `Provisioned`.

Add an rWCU token-bucket admission check inside `componentOfReplicated`'s replicated-write
path. Replicated writes that exceed the rWCU ceiling emit `ThrottledResponse` with a new
`DynamoDbThrottleReason.InsufficientReplicatedWriteCapacity`. The 24-hour cooldown and
`UpdateProvisionedCapacity` management event must also accept the rWCU dimension.

### 3. Tiered Cross-Region Transfer Pricing

Replace the flat `rate: BigDecimal` field in `CrossRegionTransferPricingRates` with a tiered
schedule `tiers: Vector[TransferPricingTier]` where each tier carries a cumulative byte
threshold and a per-GiB rate. A helper `TransferPricingTier.flat(rate)` preserves backward
compatibility for callers that do not need tiers.

Update `CrossRegionTransferPricing` to accumulate cumulative bytes per source region as it
processes events and look up the correct tier at each event. The total cost is the sum of
per-tranche charges across all tier boundaries crossed.

Add default AWS DynamoDB Global Tables transfer tier values to the demo's pricing-rates config.

### 4. GSI/LSI Support in `componentOfReplicated`

Remove the construction-time guard that rejects secondary-index configs in
`componentOfReplicated`. Verify (or correct) that `TableStorageStage` and the
index-maintenance graph handle replicated `AdmittedRequestSample` elements correctly for all
index projection types (`All`, `KeysOnly`, `Include`).

Add integration tests for `componentOfReplicated` with GSIs and LSIs configured, covering
write propagation, projection-aware read execution, and (in provisioned mode) rWCU pool
interaction with GSI maintenance writes.

Update `DynamoDbGlobalTable.componentOf` to permit secondary indexes in each region's config
(it currently defers to `componentOfReplicated` which rejected them).

### 5. ReturnedItemCount for Query and Scan

`QuerySample` and `ScanSample` already carry `returnedItemCount` (used to compute RCU
consumption). `TableStorageStage` discards this value after the capacity calculation. This
slice emits it as a metric.

Add `StorageMetricEvent.ReturnedItemCount(eventTime, usecase, operation, count: Long)` emitted
by `TableStorageStage` once per Query or Scan admitted request. The `operation` field
distinguishes `Query` from `Scan` (mirroring the `Operation` dimension in CloudWatch).

Add `DemoMetric.ReturnedItemCount` with SUM rollup. Update the thermostat fleet demo and
Grafana dashboards (both single-region and multi-region) to include a returned-items panel.

No sampler changes required — the data already exists in the samples.

### 6. ReplicationLatency Metric

The `ReplicationCoordinator` already computes the lag between when a write enters the lag queue
(origin tick) and when it is applied at the destination (apply tick). This tick delta is used
internally to schedule delivery but is never exposed as a metric.

Introduce `ReplicationMetricEvent.ReplicationLatency(eventTime, sourcRegion, destinationRegion,
latencyTicks: Long)` emitted by `ReplicationCoordinator` each time a replicated write is
applied at the destination. `latencyTicks` is the difference between the apply tick and the
origin tick; since 1 tick = 1 second, this is directly comparable to CloudWatch's millisecond
`ReplicationLatency` metric (multiplied by 1000 for unit parity).

Add `DemoMetric.ReplicationLatency` with LAST rollup (to show the most recent observed lag
per window, matching CloudWatch's Average statistic for visual comparison). Update the
multi-region Grafana dashboard with a replication latency panel per destination region.

### 7. SystemErrors

Add `systemErrorRate: Double = 0.0` to `DynamoDbTable.Config`. Add validation that the value
is in `[0.0, 1.0)`.

In `TableStorageStage`, before state mutation, draw a Bernoulli sample against
`systemErrorRate`. On a hit, emit `SystemErrorResponse` and `StorageMetricEvent.SystemError`;
skip all state mutation, consumption events, and index maintenance propagation. This follows
the same validate-then-mutate split used for item-collection size limit enforcement.

Add `DemoMetric.SystemErrorCount` with SUM rollup. The thermostat fleet demo configs leave
`systemErrorRate` at its `0.0` default unless a demo preset is explicitly exploring error
behavior; no dashboard changes are required unless a non-zero rate is configured.

Tests: confirm that system-errored requests produce no consumption events and no state change;
confirm the error rate over many trials converges to the configured probability.

### 8. SuccessfulRequestLatency

Add `DynamoDbTable.LatencyModel` as a new config type. It carries log-normal (μ, σ) parameter
pairs keyed by operation type (`GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`,
`Scan`), plus per-factor adjustments:
- `bytesPerMsMedianIncrement: Double` — linear item-size scaling applied to μ
- `stronglyConsistentMedianIncrementMs: Double` — additive to μ for strongly-consistent reads
- `batchCallOverheadMs: Double` — added once per batch call before per-item sampling

`DynamoDbTable.Config` gains `latencyModel: DynamoDbTable.LatencyModel =
DynamoDbTable.LatencyModel.awsDefault` where `awsDefault` encodes conservative AWS-calibrated
parameters (P50 ~1–2ms for single-digit-KB GetItem; P50 ~2–4ms for PutItem; P50 ~5–20ms for
Query/Scan depending on item count).

`TableStorageStage` samples a latency value after a request has been successfully processed
(not for throttled, system-errored, or item-collection-rejected requests) and emits
`StorageMetricEvent.SuccessfulRequestLatency(ms: Double, operation: OperationType)`.

The demo pipeline adds `DemoMetric.LatencyP50`, `DemoMetric.LatencyP95`, `DemoMetric.LatencyP99`
with appropriate percentile rollup aggregation (P50/P95/P99 computed across the window's raw
samples per trial, then averaged across trials). Update both thermostat fleet Grafana dashboards
with a latency distribution panel.

### 9. Table Class: Standard vs. Standard-IA

Add `DynamoDbTable.TableClass` as a sealed type with two cases: `Standard` (default) and
`StandardInfrequentAccess`. Add `tableClass: TableClass = Standard` to `DynamoDbTable.Config`.

Extend `DynamoDbPricingRates` with a parallel Standard-IA rate set. The two rate sets share
the same fields; callers select between them via the table class. `DynamoDbPricing` reads the
table class from config and applies the matching rates.

Add validation: `StandardInfrequentAccess` is incompatible with reserved capacity config
(slice 11); construction fails if both are specified.

Tests: confirm that for identical workloads, Standard-IA produces lower throughput cost and
higher storage cost than Standard; confirm the crossover point where Standard-IA becomes
cheaper lies at the expected storage-to-throughput ratio.

### 10. Per-GSI Provisioned Capacity Pricing Accuracy

`BillingMode.Provisioned` already carries `globalSecondaryIndexReadCapacityUnits: Map[String,
Long]` and `globalSecondaryIndexWriteCapacityUnits: Map[String, Long]`. Two things need fixing:

**`ProvisionedCapacityUtilization` metric:** The event currently carries only the base-table
provisioned RCU/WCU. Change it to carry `totalProvisionedReadCapacityUnits` and
`totalProvisionedWriteCapacityUnits` as the sum of base table + all GSI provisioned values.
Remove the `× (1 + numGsis)` hack from the mixed-mode demo runner, which was compensating for
this gap.

**`DynamoDbPricing` cost calculation:** Replace the approximation with an exact sum: total
provisioned RCU cost = `(baseRcu + Σ gsiRcu) × rcuHourlyRate` and equivalently for WCU.
`DynamoDbPricingInputs` must carry both the base-table and per-GSI provisioned values, or the
total already summed.

Tests: confirm that a provisioned table with non-uniform GSI capacity (e.g., base=10 WCU,
GSI-A=20 WCU, GSI-B=5 WCU) produces a provisioned cost equal to `(10+20+5) × rate`, not
`10 × 3 × rate`.

### 11. Reserved Capacity Discount

Add `ReservedCapacity` as an optional sub-config on `DynamoDbPricingRates`:
```
ReservedCapacity(
  reservedReadCapacityUnits: Long,
  reservedWriteCapacityUnits: Long,
  discountedRcuHourlyRate: BigDecimal,
  discountedWcuHourlyRate: BigDecimal
)
```

`DynamoDbPricing` applies reserved-capacity pricing when present: base-table provisioned RCU up
to `reservedReadCapacityUnits` bills at `discountedRcuHourlyRate`; the remainder bills at the
standard rate. Same split for WCU. Reserved capacity covers only the base table — GSI
provisioned hours always bill at the standard rate.

Add construction-time validation: reserved capacity requires `BillingMode.Provisioned` and
`TableClass.Standard`; providing a `ReservedCapacity` config with on-demand billing mode or a
Standard-IA table class is an error.

Tests: confirm correct rate-split when provisioned capacity straddles the reserved threshold;
confirm reserved-only and standard-only edge cases; confirm validation rejects invalid combos.

### 12. Multi-Region Demo Update

Update the multi-region thermostat fleet demo to use a table configuration that includes the
three GSIs already defined in the single-region scenario (the multi-region variant currently
uses a stripped-down config to avoid hitting the `componentOfReplicated` guard).

Update the multi-region Grafana dashboard to show:
- **rWCU vs. WCU** per region — confirms replicated writes are billed separately
- **Transfer cost by tier** — shows where the simulation crosses tier boundaries (if at all at
  the default trial count and tick count; the demo config may need a high-volume preset to make
  tier crossings visible)
- **Per-region cost breakdown** — WCU cost + rWCU cost + transfer cost per region
- **ReplicationLatency** per destination region (from slice 6)
- **ReturnedItemCount** for Query and Scan (from slice 5)
- **SuccessfulRequestLatency** P50/P95/P99 per operation type (from slice 8)
- **Table class comparison** — optional side-by-side preset showing Standard vs. Standard-IA
  cost for the same workload, illustrating the storage/throughput tradeoff
