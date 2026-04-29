# Plan: Phase 4 Slice 4 — Provisioned Capacity Change Events

**Status: COMPLETE** — all 7 steps implemented and verified (322 tests passing).

## Context

Slices 1–3 established `BillingMode.Provisioned` config, provisioned admission ceilings,
provisioned capacity pricing, and mid-simulation billing mode switching via
`DynamoDbManagementEvent.SwitchBillingMode`. All provisioned capacity values (RCU/WCU) are
currently fixed at mode-switch time — there is no way to adjust capacity within provisioned mode.

Slice 4 adds **`UpdateProvisionedCapacity`** — a management event that changes RCU/WCU (and
per-GSI capacity) within provisioned mode without switching the billing mode itself. This is
the DynamoDB equivalent of `UpdateTable` with `ProvisionedThroughput` parameters.

**Key architectural insight:** The existing admission stage billing mode detection in
`advanceToShaped` (line 1384 of `TableAdmissionStage.scala`) uses case-class inequality:
`ref.currentMode != currentBillingMode`. Since `Provisioned` is a case class, changing from
`Provisioned(100, 100)` to `Provisioned(200, 200)` is detected automatically. The existing
capacity extraction, adaptive suppression, and burst reset logic all work correctly for
Provisioned→Provisioned transitions. The only change needed in the admission stage is to emit
`ProvisionedCapacityChanged` (not `BillingModeSwitched`) when both old and new modes are
`Provisioned`.

## Critical files

| Role | File |
|------|------|
| Management event type | `core/.../table/management_events.scala` |
| New metric event | `core/.../table/table_metric_events.scala` |
| Management processor | `core/.../table/DynamoDbTable.scala` (lines 1384–1400) |
| Metric discrimination + fan-out | `core/.../table/TableAdmissionStage.scala` (lines 1384–1403, 1475–1503) |
| Admission tests | `core/.../table/TableAdmissionStageSpec.scala` |
| Component tests | `core/.../table/DynamoDbTableComponentSpec.scala` |

## Step-by-step implementation

### Step 1 — Add `UpdateProvisionedCapacity` to `DynamoDbManagementEvent` ✓

**`core/src/main/scala/stochastacy/aws/dynamodb/table/management_events.scala`**

Added after `SwitchBillingMode`:

```scala
final case class UpdateProvisionedCapacity(
  override val eventTime: SimTime,
  override val usecase: Any,
  newCapacity: DynamoDbTable.BillingMode.Provisioned
) extends DynamoDbManagementEvent
```

The field is typed as `BillingMode.Provisioned` (not `BillingMode`), which statically guarantees
valid provisioned capacity. The `Provisioned` case class's existing `require(readCapacityUnits > 0L)`
/ `require(writeCapacityUnits > 0L)` / GSI checks enforce the minimum floor of 1 RCU / 1 WCU at
construction time. No additional floor validation is needed.

### Step 2 — Add `ProvisionedCapacityChanged` to `AdmissionMetricEvent` ✓

**`core/src/main/scala/stochastacy/aws/dynamodb/table/table_metric_events.scala`**

Added after `BillingModeSwitched`:

```scala
final case class ProvisionedCapacityChanged(
  eventTime: SimTime,
  usecase: Any,
  previousCapacity: DynamoDbTable.BillingMode.Provisioned,
  newCapacity: DynamoDbTable.BillingMode.Provisioned
) extends AdmissionMetricEvent
```

Both fields are typed as `BillingMode.Provisioned` — this event is only emitted for
Provisioned→Provisioned transitions.

### Step 3 — Add management processor case in `componentOfManaged` ✓

**`core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala`** (within the
`managementProcessor` `statefulMapConcat`, currently lines 1384–1400)

Added a new case after the `SwitchBillingMode` case:

```scala
case event: DynamoDbManagementEvent.UpdateProvisionedCapacity =>
  billingModeRef.currentMode match
    case _: DynamoDbTable.BillingMode.Provisioned =>
      billingModeRef.currentMode = event.newCapacity
      Nil
    case _ =>
      List(ReconfigurationRejectedResponse(
        event.eventTime,
        event.usecase,
        "UpdateProvisionedCapacity is only valid when the table is in provisioned billing mode"
      ))
```

Key design decisions:
- **No cooldown:** `lastSwitchTick` is NOT touched. The 24-hour cooldown applies only to
  billing mode switches, not capacity adjustments.
- **On-demand rejection:** If the table is in on-demand mode, emit
  `ReconfigurationRejectedResponse`.
- **Ref update:** Setting `billingModeRef.currentMode = event.newCapacity` causes admission
  stages to detect the change at the next tick boundary via the existing case-class inequality
  check.

### Step 4 — Distinguish capacity change from mode switch in `advanceToShaped` ✓

**`core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala`** (within the
`billingModeEvents` block, currently line 1402)

Replaced the unconditional `BillingModeSwitched` emission:

```scala
// Was:
Vector(AdmissionMetricEvent.BillingModeSwitched(eventTime, "billing-mode-switch", previousMode, newMode))

// Now:
val metricEvent: TimedEvent = (previousMode, newMode) match
  case (p: DynamoDbTable.BillingMode.Provisioned, n: DynamoDbTable.BillingMode.Provisioned) =>
    AdmissionMetricEvent.ProvisionedCapacityChanged(eventTime, "provisioned-capacity-change", p, n)
  case _ =>
    AdmissionMetricEvent.BillingModeSwitched(eventTime, "billing-mode-switch", previousMode, newMode)
Vector(metricEvent)
```

All surrounding logic (capacity extraction, adaptive suppression, burst reset) is unchanged.

### Step 5 — Wire `ProvisionedCapacityChanged` through the fan-out flows ✓

**`core/src/main/scala/stochastacy/aws/dynamodb/table/TableAdmissionStage.scala`** (lines
1475–1503)

Added one line to each of the three fan-out flows, following the `BillingModeSwitched` pattern:

**`admittedFlow`**:
```scala
case _: AdmissionMetricEvent.ProvisionedCapacityChanged => Nil
```

**`responseFlow`**:
```scala
case _: AdmissionMetricEvent.ProvisionedCapacityChanged => Nil
```

**`metricFlow`**:
```scala
case metric: AdmissionMetricEvent.ProvisionedCapacityChanged => List(metric)
```

### Step 6 — Fix existing GSI test (breaking change) ✓

**`core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala`**

The test "componentOfManaged propagates billing mode switch to GSI admission branch" uses
`SwitchBillingMode` to change from `Provisioned(1000, 100, gsi→1000)` to `Provisioned(1000,
100, gsi→1)`. After step 4, the admission stage emits `ProvisionedCapacityChanged` instead
of `BillingModeSwitched` for this Provisioned→Provisioned transition.

Changed assertion to collect `ProvisionedCapacityChanged`:
```scala
val capacityChanged = metrics.collect { case m: AdmissionMetricEvent.ProvisionedCapacityChanged => m }
capacityChanged.size should be >= 1
```

### Step 7 — New tests ✓

**7a. `TableAdmissionStageSpec.scala`** — 3 new tests:

1. **"emit ProvisionedCapacityChanged metric when provisioned capacity ref changes"** — Start
   with `BillingModeRef(Provisioned(100, 100))`, set ref to `Provisioned(200, 200)`. Send a
   request at tick 1. Assert metrics contain `ProvisionedCapacityChanged` with correct
   previous/new values. Assert NO `BillingModeSwitched` is emitted.

2. **"apply new capacity ceiling after provisioned capacity scale-down"** — Start with
   `BillingModeRef(Provisioned(100, 100))`, set ref to `Provisioned(3, 3)`. Send two requests
   each demanding 2 RCUs at tick 1. Assert first admitted, second throttled with
   `TableReadProvisionedThroughputExceeded`.

3. **"still emit BillingModeSwitched for on-demand to provisioned switch (not
   ProvisionedCapacityChanged)"** — Start with `BillingModeRef(OnDemand())`, set ref to
   `Provisioned(100, 100)`. Assert metrics contain `BillingModeSwitched`, not
   `ProvisionedCapacityChanged`.

**7b. `DynamoDbTableComponentSpec.scala`** — 4 new tests:

1. **"componentOfManaged applies UpdateProvisionedCapacity and emits
   ProvisionedCapacityChanged"** — Create managed component with `Provisioned(1, 1)`. Send
   `UpdateProvisionedCapacity(newCapacity = Provisioned(100, 100))` at tick 10. Send a request
   demanding 2 RCUs at tick 50. Assert admitted (2 ≤ 100). Assert metrics contain
   `ProvisionedCapacityChanged`.

2. **"componentOfManaged rejects UpdateProvisionedCapacity when table is on-demand"** — Create
   with default on-demand. Send `UpdateProvisionedCapacity`. Assert
   `ReconfigurationRejectedResponse` with reason mentioning "provisioned billing mode".

3. **"componentOfManaged does not apply 24-hour cooldown to UpdateProvisionedCapacity"** —
   Create with `Provisioned(10, 10)`. Send `SwitchBillingMode` to `Provisioned(20, 20)` at
   tick 1. Send `UpdateProvisionedCapacity(Provisioned(30, 30))` at tick 2 (within cooldown).
   Assert no rejection — capacity change accepted.

4. **"componentOfManaged propagates provisioned capacity change to GSI branch"** — Create
   with `Provisioned(1000, 100, gsi→1000)`. Send
   `UpdateProvisionedCapacity(Provisioned(1000, 100, gsi→1))` at tick 10. Send GSI query
   demanding > 1 RCU at tick 50. Assert throttled with
   `GlobalSecondaryIndexReadProvisionedThroughputExceeded`.

## Verification

All 322 tests passing (233 core + 89 examples).

## Test fixes during implementation

Three Slice 3 tests had design bugs where throughput demand didn't exceed capacity limits due to
the strict `>` comparison in admission (`usage + demand > limit` → throttle):

1. `Provisioned(10L, 10L)` → `Provisioned(3L, 3L)` — cumulative 4 RCUs > 3 limit triggers throttle
2. `FixedHitGetItemBehavior(512L)` → `FixedHitGetItemBehavior(5120L)` — 2 RCUs > 1 RCU limit
3. `FixedQueryBehavior(4096L)` → `FixedQueryBehavior(12288L)` — 1.5 RCUs (3 chunks × 0.5 EC) > 1 RCU GSI limit
