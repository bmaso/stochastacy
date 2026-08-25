# Thermostat-Fleet Single-Region (DynamoDB on the v2 core) — Engineer's Guide

An IoT-scale AWS demo on the domain-agnostic v2 engine: a **growing fleet of smart thermostats** streaming
telemetry into **one on-demand DynamoDB table** with **three GSIs and one LSI of mixed projections**,
queried by customer support and scanned for fleet alerts, estimating capacity, storage, and cost across a
Monte Carlo ensemble. It re-implements the legacy `thermostat-fleet-single-region` demo on the new
`stochastacy.core` abstractions, and is proven — by a reconciliation gate — to reproduce it within ~2 %.

It is the first thermostat scenario on the v2 core (the domain the later multi-table, capstone, and
multi-region phases reuse), and it exercises two things order-tracking did not: **mixed index projections**
(KeysOnly / Include / All) and an **inbound gate** (`Interface.wrap` + `ChaosGate`) on an AWS table.

The example lives in the `aws/` module, package `stochastacy.aws.examples.thermostatfleet`; it drives the
reusable `DynamoDbTable` (`stochastacy.aws.dynamodb`) through the shared single-table demo harness
(`stochastacy.aws.examples.demo`). See the [AWS component catalog](aws-component-catalog.md).

---

## 1. What the demo demonstrates

### The fictional domain
A fleet of **smart thermostats** reporting telemetry to one DynamoDB table, `device-telemetry`. Each device
periodically writes a reading (`PutItem`, ~300 B); a **customer-support** tool queries one customer's
devices (`Query` on a GSI); a **fleet dashboard** scans for alerting devices (`Scan` on a GSI). The fleet
**grows over time**, and the table starts **empty** and fills as devices report.

### The shape of the simulation
```
workload (telemetry write + GSI query + GSI scan) → [ ChaosGate → DynamoDbTable(+3 GSI,+1 LSI) ] → consumption → usage → on-demand cost
                                                                       │
                                                                       └→ responses (discarded by this demo)
```
Every telemetry write fans out **index maintenance** to each secondary index (its own WCU + storage, sized
by the index's projection); every read consults **its target GSI's own projected state**. A small
**system-error gate** on the inlet rejects ~0.1 % of requests (consuming nothing), modeling DynamoDB's
intrinsic transient failures. The runner folds the consumption plane into per-tick and per-trial totals,
prices it, and the Monte Carlo runner repeats across seeded trials for the *distribution* of outcomes.

The demo surfaces:

- **A growing, time-shaped write load** — telemetry λ scales with the fleet (`0.033 × fleetSize(tick)`) and
  is shaped by **morning/evening spikes**, an optional **polar-vortex** window, and stochastic
  **alert-storm bursts** (see §4.2).
- **Mixed-projection index maintenance** — `customer-devices` (KeysOnly) and `fleet-alerts` (Include 64 B)
  maintain only key/projected bytes (and no-op on same-size updates); `device-status` (All) and the
  `reading-type-history` LSI (All) carry the full item — so `device-status` dominates write capacity.
- **Projection-correct reads** — a query/scan is charged for its *target's projected* bytes, not the base
  item's.
- **Intrinsic failure** — a load-independent `ChaosGate` at `systemErrorRate = 0.001`.
- **Run-to-run variance** — across-trial mean and standard deviation for every metric.

---

## 2. Results — reconciliation with the legacy demo

The demo's reason for existing is parity: it must behave like the legacy single-region demo. The gate
(`ThermostatFleetReconciliationSpec`) runs the v2 ensemble at the legacy's configuration (100 trials × 1200
ticks) and compares across-trial means to a **captured legacy baseline** (the legacy code is unreferenceable
from this module). It is a **clean equivalence** on every dimension:

| metric | v2 vs. legacy | band |
|---|---|---|
| mean total **write** capacity units | **−0.18%** | ±3% |
| per-GSI **write** capacity units (customer-devices / fleet-alerts / device-status) | **≤0.2%** | ±3% |
| mean total **read** capacity units | **+0.47%** | ±5% |
| per-GSI **read** capacity units (customer-devices / fleet-alerts) | **−0.5% / +1.2%** | ±5% |
| mean final storage bytes | **+0.11%** | ±3% |
| mean total estimated cost | **−0.18%** | ±3% |

The sub-2 % gaps are consistent with the sampling error of two 100-trial ensembles drawn from *independent*
RNG streams. Three things make this a *clean* equivalence rather than the phase-3-style
reconciliation-with-divergence:

- **Writes + maintenance replicate the legacy math** — including the mixed-projection maintenance
  (`device-status` All carries the full item, the others no-op on same-size updates).
- **The system-error gate closes the last gap.** The legacy's `systemErrorRate = 0.001` is reproduced by an
  inbound `ChaosGate` (Slice 6a), so there is no deferred ~0.1 % divergence on the write path.
- **The projection-correct reads did *not* meaningfully diverge.** v2 charges reads for each GSI's
  *projected* bytes (KeysOnly ≈128 B, Include ≈192 B) rather than the base item's 300 B — but the read
  sizes here are small enough that RCU rounding (4 KB blocks, halved for eventual consistency) **absorbs**
  the difference, so total and per-GSI RCU still match within ~2 %. (This is the opposite outcome from the
  indexed order-tracking demo, whose *scans grew unbounded* and so diverged by design.)

Two immaterial modeling differences are documented, not gated: the legacy writes a **constant** 300 B per
telemetry item while v2 draws **±25 % uniform** (same mean, both sub-1 KB ⇒ 1 WCU/item and the same expected
storage); and the polar-vortex `affectedFraction` default differs, but is inert while the vortex multiplier
is 1.0 (off by default).

To run the gate:
```bash
sbt 'aws/testOnly stochastacy.aws.examples.thermostatfleet.ThermostatFleetReconciliationSpec'
```

---

## 3. Running the demo

No external services — the demo writes JSONL plus a console summary (with a per-GSI RCU/WCU line).

```bash
sbt 'aws/runMain stochastacy.aws.examples.thermostatfleet.ThermostatFleetDemo --output /tmp/thermostat-fleet-single-region.jsonl --trials 100 --ticks 1200 --seed 1'
```

Flags (all optional; unset values fall back to `ThermostatConfig.singleRegionDefault`): `--output`,
`--seed`, `--trials`, `--ticks`, `--parallelism` (does not affect results). The scenario — fleet size and
growth, telemetry rate and item bytes, the temporal-shape and alert-storm parameters, the system-error
rate, the index projections, and the query/scan rates — lives in `ThermostatConfig`; edit `singleRegionDefault`
(or `.copy(...)`) to explore other regimes.

The JSONL carries the same four record kinds as the other v2 demos — `trial-time-series`, `trial-summary`,
`aggregate-time-series`, `aggregate-summary` — in the legacy demo's record shape, so the existing Grafana
queries bind unchanged. Per-GSI capacity uses the legacy names `GSI:<name>:ReadCapacityUnits` /
`WriteCapacityUnits` (and `Total…`); a **write-only** GSI (`device-status`, never read) is reported too.

---

## 4. Internals

### 4.1 The table and its domain
The table is the reusable `DynamoDbTable` component (`stochastacy.aws.dynamodb`) — generic mechanics with an
injected `TableBehavior`, plus its secondary indexes declared as **config** (never graph nodes). This demo
supplies `ThermostatFleetBehavior`: a telemetry write is an **insert-or-overwrite by fleet saturation**
(`(fleetSize(tick) − itemCount)/fleetSize(tick)` chance of hitting a new device, so the table fills toward
the fleet size); a query on `customer-devices` evaluates 2–10 items and a scan on `fleet-alerts` evaluates
50–250, each charged for the **target's projected** bytes. The behavior takes the current `tick` (Slice 2),
so the fleet can grow. See the [AWS component catalog](aws-component-catalog.md) for the component contract.

The three GSIs and one LSI have **mixed projections**: `customer-devices` KeysOnly, `fleet-alerts`
Include(64 B), `device-status` All, `reading-type-history` (LSI) All. `SecondaryIndexMechanics` maintains
each on every write (GSI asynchronously / LSI synchronously), sized by its projection — so KeysOnly/Include
no-op on same-size overwrites while All always pays.

### 4.2 The workload
`ThermostatWorkload.arrivals` emits three per-tick flows. **Telemetry** `PutItem` is drawn from a shaped,
stateful rate (`ThermostatConfig.telemetryRateSampler`): a fleet-scaled base λ
(`0.033 × fleetSize(tick)`), multiplied by the larger of a **morning** and **evening** triangular spike
(`TemporalShapeFunctions.triangularFactor`, ×2.0 at ticks 420–540 / 1020–1140) and an (off-by-default)
**polar-vortex** window factor, then wrapped in a `RandomBurstSampler` for **alert-storm** bursts (additive
in λ-space: prob 0.002/tick, 30-tick duration, ×5.0). **Query** (`customer-devices`) and **scan**
(`fleet-alerts`) fire at constant Poisson rates (0.5 / 0.1 per tick), eventually consistent (GSI reads
cannot be strong). Each event gets a uniform-random intra-tick position and the scenario id; `TickFraming`
frames them into the `Tick`-windowed, `EndOfTime`-terminated stream the table consumes.

### 4.3 One trial
`SingleTableTrialRunner.runTrial` (the shared harness) wires `workload → [ChaosGate →] DynamoDbTable`,
discards responses, and drains the consumption plane. The `ChaosGate` is attached **only when
`systemErrorRate > 0`** — a load-independent per-request rejection (a `SystemErrorResponse`, consuming no
capacity and mutating no state), the first `Interface.wrap` on an AWS table. The runner derives three
independent seeds (workload / table / gate) so the flows don't share an RNG stream; `TrialAccounting` folds
the consumption in one pass into a `TrialResult` (totals + per-tick series), and `OnDemandPricing` prices it.

### 4.4 The ensemble
`SingleTableMonteCarloRunner` drives the core `MonteCarlo.stream` — `trialCount` reproducible trials from
one master seed, order-stable and parallelism-independent — folding each completed trial into an
`IncrementalAggregator` (running moments per metric → across-trial mean and population standard deviation)
and releasing it. The `@main`'s `runToFile` streams each trial's records to disk through a `JsonlWriter` as
it completes, then appends the aggregates — so a run's memory stays flat in the trial count and the JSONL
grows during the run rather than being buffered whole (a collecting `run` variant backs the tests/gates).

---

## Source map

| concern | file |
|---|---|
| scenario config (fleet / telemetry / temporal shapes / system error / indexes / read rates) | `ThermostatConfig.scala` |
| domain behavior (saturation write, query/scan read shapes) | `ThermostatFleetBehavior.scala` |
| workload driver (shaped telemetry + GSI query/scan) | `ThermostatWorkload.scala` |
| `@main` | `ThermostatFleetDemo.scala` |
| reconciliation gate | `test/.../ThermostatFleetReconciliationSpec.scala` |
| shared single-table demo harness | `stochastacy.aws.examples.demo.*` (`SingleTableScenario`, `SingleTable{Trial,MonteCarlo}Runner`, `TrialAccounting`, `OnDemandPricing`, `MonteCarloAggregation`, `JsonlExport`) |
| the reusable table component + gate | `stochastacy.aws.dynamodb.*`, `stochastacy.core.component.Interface` / `gate.ChaosGate` |

## See also

- [AWS component catalog](aws-component-catalog.md) — the `DynamoDbTable` component and its index config.
- [Order-Tracking v2](README.ordertracking-v2.md) — the other AWS demo (and the indexed reconciliation it contrasts with).
- [Core component catalog](component-catalog.md) — the interface/gate machinery the system-error gate reuses.
