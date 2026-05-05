# Post-Phase-4 Dashboard Fixes

## Goal

Fix a set of correctness and presentation issues discovered in the mixed-mode (Phase 4 capstone)
Grafana dashboard after end-to-end testing. Each issue is independent and can be tackled
one at a time.

---

## Issue List

### 1. Billing Mode Timeline shows no mode switch  (FIXED)

**Symptom:** The "Billing Mode Timeline" panel showed a flat green line at `1.0` (provisioned)
for the entire simulation window. A mixed-mode scenario should show a step transition between
on-demand (`0`) and provisioned (`1`) at the reconfiguration tick.

**Root cause (diagnosed):** The management stream races ahead in Pekko's fused graph (returning
`Nil` for ticks causes it to drain before the request stream processes tick 2), making
stream-observed `BillingModeSnapshot` events unreliable for the on-demand phase.

**Fix applied:** `billingModeTimeSeries` is now derived from the config schedule, not stream
events. `ThermostatFleetMixedModeSingleTrialRunner` constructs it deterministically:
```scala
val billingModeTimeSeries = (1L to scenarioConfig.simulationTicks).map { tick =>
  val modeCode = if tick <= config.modeSwitchTick then 0 else 1
  SimulationTimeSeriesPoint(tick, DemoMetric.BillingModeIndicator, BigDecimal(modeCode))
}.toVector
```
This produces a clean step function: `0` for all on-demand ticks, `1` for all provisioned ticks,
matching the reconfiguration schedule exactly.

---

### 2. Consumed WCU is 2–3× the provisioned ceiling despite active throttling  (FIXED)

**Symptom:** Consumed WCU hovers at 12–15K per window while Provisioned WCU is ~5K per window.
Throttles are occurring (2K–7K per window), but the math doesn't close: if total demand ≈
consumed + throttled ≈ 15–20K and capacity is 5K, we'd expect 10–15K throttles per window,
not 2–7K. Too many writes are being admitted.

**Root cause (diagnosed):** Scale mismatch, not an admission bug.
- `WriteCapacityUnits` (consumed) = base table + ALL GSI maintenance writes summed together.
  The thermostat fleet has 3 GSIs; on average ~2 GSI writes trigger per base table write, so
  total consumed ≈ 3× base table WCU.
- `ProvisionedWriteCapacityUnits` was emitting only the BASE TABLE provisioned rate (70 WCU/s),
  not the total across all capacity pools (base + 3 GSIs = 280 WCU/s).
- In real DynamoDB provisioned mode, each GSI has its own independent capacity pool.
  Consumed WCU naturally includes the GSI pool draws, so provisioned must too.
- The admission logic itself is correct: base table is properly throttled at its ceiling.

**Fix applied:** `ProvisionedWriteCapacityUnits` and `ProvisionedReadCapacityUnits` are now
multiplied by `(1 + numGsis)` in `ThermostatFleetMixedModeSingleTrialRunner`, making them
represent total provisioned capacity across all entities. With 3 GSIs: 70 × 4 = 280 WCU/tick
= 16,800 per 60-tick window. Consumed (~12,570) is now correctly below provisioned (16,800).

---

### 3. Provisioned vs. consumed metrics are on incompatible scales  (RESOLVED)

**Symptom:** Two opposite mismatches visible side-by-side:
- *Writes:* Consumed WCU (~12–15K) far exceeds Provisioned WCU (~5K) — consumed looks
  inflated relative to provisioned.
- *Reads:* Provisioned RCU (~600) towers over Consumed RCU (near-zero hairline) — provisioned
  looks inflated relative to consumed.

**Root cause (diagnosed):** The two sides have entirely different explanations.

*Write side* — genuine scale bug, now fixed in Issue #2. `ProvisionedWriteCapacityUnits` was
emitting only the base-table provisioned rate, while `WriteCapacityUnits` includes base-table
+ all 3 GSI maintenance writes. Including GSI provisioned capacity (× 4) fixed the comparison.

*Read side* — accurate simulation, not a bug. The thermostat fleet scenario is a 165:1
write-to-read workload: 99 writes/tick vs 0.6 reads/tick. Consumed RCU (~38/60-tick window)
matches exactly what the scenario generates (36 reads/window × ~1 RCU each). Provisioned RCU
(2,400/window = 10 RCU × 4 entities × 60 ticks) correctly reflects the configured capacity.
The 63× gap is accurate: the table is 1.5% read-utilized — intentionally over-provisioned for
a telemetry-dominant workload. No scale error exists; both metrics are in the same units (RCU
summed per window). The "original theory" (provisioned emitted as per-second rate vs per-tick
total) was incorrect.

**Resolution:** Write side fixed in Issue #2. Read side requires no code change — the gap is
correct simulation output showing a read-light scenario with high provisioned RCU headroom.

---

### 4. "Throttle Rate" panel shows a raw count, not a rate  (FIXED)

**Symptom:** The panel titled "Throttle Rate" displays raw throttled-operation counts per
window (2K–6K), not a rate. The Y-axis label even says "Throttled requests" rather than
"requests/s" or "% throttled".

**Fix applied:** In `examples/grafana/thermostat-fleet-mixed-mode-dashboard.json`:
- SQL query now divides `avg("value")` by `cast('${windowSizeSeconds}' as float)`, producing
  throttles/s (e.g., 43–117 throttles/s vs the raw 2,600–7,000 count).
- `axisLabel` updated to `"Throttles/s"`.
- Panel description updated to state "throttled requests per second".

---

### 5. Data cliff to zero at end of simulation window  (FIXED)

**Symptom:** Both consumed and provisioned WCU drop sharply to zero at the last time window
(~16:15 in the screenshot). The last window is incomplete — it covers fewer ticks than a full
window — so its rollup sum is artificially small. Visually this looks like a data artifact or
simulation crash rather than a graceful end.

**Root cause (diagnosed):** `TableAdmissionStage.advanceToShaped` emits `ProvisionedCapacityUtilization`
(and `BillingModeSnapshot`) events timestamped at the INCOMING tick (T+1) rather than the
COMPLETED tick (T). For the last real tick T=simulationTicks, this event is timestamped T+1 —
the sentinel tick — which maps to a new phantom window starting at simulationTicks+1. The
rollup for this window shows 1 tick of provisioned data (280 WCU) and 0 consumed, creating
the visual cliff.

**Fix applied:** All five Grafana SQL queries now include:
```sql
and window_start_tick <= cast('${simulationTicks}' as bigint)
```
This drops the sentinel-tick phantom window from all panels. The filter uses the existing
`${simulationTicks}` dashboard variable (sourced from `stochastacy_demo.demo_batches`), so it
adapts automatically to any simulation length. The last real window now shows clean data
(12,574 consumed vs 16,800 provisioned WCU for the thermostat-mm-003 batch) with no cliff.
