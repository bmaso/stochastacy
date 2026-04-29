# IPS Phase 4

## Goal

Phase 4 extends the DynamoDB table simulator to support **provisioned capacity mode** and
**dynamic reconfiguration** during a running simulation.

The phase-3 on-demand foundation remains fully intact. Phase 4 adds:

- A provisioned-mode admission path (fixed RCU/WCU ceilings, token-bucket enforcement)
- A provisioned-mode pricing model (pay for capacity per hour, regardless of utilization)
- Management-API events that reconfigure a table mid-simulation: billing mode switches
  (on-demand ↔ provisioned) and provisioned capacity adjustments (RCU/WCU changes)
- A reconfiguration schedule DSL so scenario configs can express a timeline of changes
- A capstone demo that shows a billing-mode switch mid-simulation and the resulting cost
  trajectory shift

Phase 4 is **complete** when a demo can be generated, staged, and visualized that:
1. Starts a simulated fleet in on-demand mode
2. Switches to provisioned capacity at a configured tick
3. Adjusts provisioned capacity at one or more later ticks
4. Shows utilization (provisioned vs. consumed capacity) and cost trajectory in Grafana

---

## Design Anchors

### Billing mode as a first-class config dimension

The current `DynamoDbTable.Config` has `onDemandMaxThroughput`. Phase 4 replaces this with a
sealed `BillingMode` union:

```
BillingMode.OnDemand(maxThroughput: Option[OnDemandMaxThroughput])
BillingMode.Provisioned(readCapacityUnits: Long, writeCapacityUnits: Long)
```

The initial mode is set at construction time. Reconfiguration events mutate the live mode at
tick boundaries inside `TableAdmissionStage`.

### Provisioned admission vs. on-demand admission

| Dimension | On-demand | Provisioned |
|-----------|-----------|-------------|
| Capacity ceiling | AWS-managed, modeled via `onDemandMaxThroughput` | Fixed RCU/WCU token buckets |
| Burst capacity | Up to 300 s of unused capacity | Same logic, bucket refills at provisioned rate |
| Adaptive capacity | Yes — relief for hot partitions under imbalanced load | No — provisioned tables throttle without adaptive relief |
| Hot partition detection | Yes | Yes — still relevant; a hot partition can exhaust the table ceiling |
| Throttling outcome | Same `ThrottledResponse` | Same `ThrottledResponse` |

### Provisioned pricing model

In on-demand mode, cost accrues per consumed RCU/WCU. In provisioned mode, cost accrues for
**capacity reserved per tick-second**, independent of actual consumption. Consumed capacity above
the provisioned limit is impossible (the table throttles first), so the pricing model for
provisioned is entirely capacity-driven, not consumption-driven.

The key observable: under-utilization is visible and costly. A panel showing provisioned vs.
consumed capacity over time makes this concrete.

### Reconfiguration events

Management-API operations are distinct from data-plane requests (`DynamoDBRequest`). Phase 4
introduces a parallel `DynamoDbManagementEvent` type consumed by the table's control plane:

```
DynamoDbManagementEvent.SwitchBillingMode(eventTime, newMode: BillingMode)
DynamoDbManagementEvent.UpdateProvisionedCapacity(eventTime, readCapacityUnits: Long, writeCapacityUnits: Long)
```

These are mixed into the simulation's request stream alongside `TimedControlEvent.Tick` and
`DynamoDBRequest` elements. `TableAdmissionStage` processes them at tick boundaries.

**24-hour mode-switch cooldown**: DynamoDB enforces that billing mode can only be switched once
per 24 hours. The simulator should enforce this constraint and emit a rejected
`ReconfigurationRejectedResponse` if a switch is attempted too soon. Scenario configs that
violate the constraint should fail validation at construction time.

**Scale-down constraint**: Provisioned WCU/RCU cannot be decreased below a floor that
corresponds to the table's minimum partition count (effectively 1 RCU and 1 WCU per partition,
with a hard floor of 1 partition). Model this as a configurable minimum; default to 1.

---

## Phase-4 Implementation Slices

Future phase-4 work should be planned and implemented one slice at a time.

### 1. BillingMode Config and Provisioned Admission

Introduce the `BillingMode` sealed type and update `DynamoDbTable.Config` to use it. Update
`TableAdmissionStage` to use token-bucket RCU/WCU enforcement when in provisioned mode, with
burst capacity refilling at the provisioned rate. Adaptive capacity is suppressed in provisioned
mode; hot partition detection continues to apply.

All existing on-demand tests must pass unchanged. New tests cover provisioned admission:
throttling at the RCU/WCU ceiling, burst absorption up to the 300-second limit, and suppressed
adaptive relief.

### 2. Provisioned Capacity Pricing

Add a provisioned pricing path to `DynamoDbPricing`. In provisioned mode, cost accrues as
`provisioned_rcu × rcu_price_per_hour + provisioned_wcu × wcu_price_per_hour` per simulated
second of wall-clock capacity allocation.

Add a new cost dimension to `DynamoDbCostBreakdown`: `provisionedCapacityCost` (separate from
`readCost` and `writeCost`, which remain consumption-driven and are zero in provisioned mode).
Update `DynamoDbUsageTotals` or introduce a parallel provisioned-usage accumulator to carry
provisioned capacity second-integrals.

### 3. Management Events and Billing Mode Switch

Introduce `DynamoDbManagementEvent` as a new protocol element accepted by `DynamoDbTable`
alongside `DynamoDBRequest`. Add `SwitchBillingMode` as the first variant.

`TableAdmissionStage` applies the mode change at the next tick boundary, resets the appropriate
burst reservoir, and emits a `BillingModeSwitchMetricEvent` (visible in the metric outlet).

Enforce the 24-hour cooldown: a second switch attempted within 86,400 simulated seconds emits
`ReconfigurationRejectedResponse` and does not change state.

### 4. Provisioned Capacity Change Events

Add `UpdateProvisionedCapacity` to `DynamoDbManagementEvent`. Scale-up takes effect immediately
at the next tick boundary (like real DynamoDB). Scale-down is also applied at the next tick
boundary; reject decreases below the configured minimum floor (default 1 RCU / 1 WCU).

Emit `ProvisionedCapacityChangedMetricEvent` so the downstream pipeline can record the
reconfiguration timeline.

### 5. Reconfiguration Schedule DSL

Add a `ReconfigurationSchedule` type to the simulation scenario config layer. A schedule is an
ordered sequence of `(tick, DynamoDbManagementEvent)` pairs. The single-trial runner consumes
the schedule and injects the events at the correct simulation ticks, interleaved with normal
request traffic.

Validation at schedule construction time: mode-switch pairs must be separated by at least
86,400 ticks; `UpdateProvisionedCapacity` events must reference a table currently in provisioned
mode.

### 6. Utilization Metrics

Add per-tick `ProvisionedReadCapacityUnits` and `ProvisionedWriteCapacityUnits` time-series
metrics so Grafana can show provisioned capacity alongside consumed capacity. Utilization
(consumed / provisioned) is a derived panel expression, not a stored metric.

Also emit a `BillingMode` time-series point per tick (encoded as 0 = on-demand, 1 = provisioned)
so a state-timeline panel can show when the switch happened.

### 7. Demo Scenario and Grafana Panels

Extend the thermostat fleet demo with a `provisioned-mode` variant (or a new mixed-mode preset)
that:

- Starts in on-demand for the first third of the simulation
- Switches to provisioned at approximately the 1/3 tick mark, set at 110% of the observed
  mean capacity from the on-demand phase (simulating a team right-sizing after observation)
- Optionally adjusts provisioned capacity at the 2/3 tick mark

Grafana additions:
- **Capacity Utilization** row: provisioned RCU/WCU vs. consumed RCU/WCU as overlaid time series
- **Billing Mode Timeline**: single-value or state panel showing when mode switches occurred
- **Cost Composition**: stacked bar distinguishing on-demand cost (consumed) vs. provisioned
  capacity cost (reserved) over the simulation window
