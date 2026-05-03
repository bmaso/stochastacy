# Thermostat Fleet Demo Runbook

## Purpose

This runbook describes the operator workflow for the thermostat fleet DynamoDB demo:

1. start the Docker services
2. generate a simulation batch (single-region, multi-region, or mixed-mode)
3. stage that batch into Postgres
4. open the provisioned Grafana dashboard

## Prerequisites

- Docker is installed and available on the host
- `sbt` is installed and available on the host
- the current working directory is the repo root

## Start The Stack

```bash
docker compose up -d
```

This starts:

- Postgres on `localhost:5432`
- Grafana on `localhost:3000`

Default credentials:

- Postgres database: `stochastacy_demo`
- Postgres user: `stochastacy`
- Postgres password: `stochastacy`
- Grafana user: `admin`
- Grafana password: `admin`

## Single-Region Mode

### Generate A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-fleet-sr-001 --output /tmp/thermostat-fleet-sr-001.jsonl --mode single-region --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

This simulates a thermostat fleet in a single AWS region (`us-east-1`) with:
- 83,000 initial devices, growing at ~0.7% per tick
- DynamoDB table with 3 GSIs (`customer-devices`, `fleet-alerts`, `device-status`) and 1 LSI (`reading-type-history`)
- On-demand throughput, hot-partition detection, burst capacity, adaptive capacity, dynamic partition topology, and LSI item-collection limits

### Stage A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-fleet-sr-001.jsonl --batch-id thermostat-fleet-sr-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

### Open The Dashboard

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-sr-001 --mode single-region'
```

## Multi-Region Mode

### Generate A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-fleet-mr-001 --output /tmp/thermostat-fleet-mr-001.jsonl --mode multi-region --trial-count 100 --parallelism 4 --simulation-ticks 1200'
```

This simulates a fleet in three regions:
- `us-east-1` (50,000 devices), `eu-west-1` (25,000 devices), `ap-southeast-1` (8,000 devices)
- Global table with per-link stochastic replication lag (log-normal, ~1.5 tick mean)
- Cross-region transfer costs at $0.02/GiB (us-east-1, eu-west-1) and $0.08/GiB (ap-southeast-1)
- All phase-3 features active at every replica: GSI write amplification, rWCU at destination regions, LSI item-collection limits, GSI back-pressure

> **Note:** Each multi-region trial runs three concurrent regional simulations, so the effective
> concurrency is `parallelism × 3`. Keep `--parallelism` at 4 or below; higher values require
> proportionally more heap and will OOM on the default 4 GiB configured in `.jvmopts`.

### Stage A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-fleet-mr-001.jsonl --batch-id thermostat-fleet-mr-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 4 --simulation-ticks 1200'
```

### Open The Dashboard

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-mr-001 --mode multi-region'
```

## Mixed-Mode ("The Right-Sizing Trap")

### Generate A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-fleet-mm-001 --output /tmp/thermostat-fleet-mm-001.jsonl --mode mixed-mode --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

### Stage A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-fleet-mm-001.jsonl --batch-id thermostat-fleet-mm-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

### Open The Dashboard

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-mm-001 --mode mixed-mode'
```

Optional flags let you override the provisioned levels at the command line:

```bash
--initial-provisioned-wcu <long>    # default: 4200
--adjusted-provisioned-wcu <long>   # default: 12500
```

## Use The Dashboard

For the normal happy path:

1. select the staged `batch_id`
2. select a `Window Size` of `60` or `300`
3. inspect the **Capacity Overview** row for total RCU/WCU across all trials
4. for multi-region batches: select a `Region Name` and inspect the **Per-Region Write Capacity** row
5. select a `GSI Index Name` and inspect the **GSI Pressure** row
6. inspect the **Storage and Cost** row for storage bytes, cumulative cost, cross-region transfer, and per-region WCU totals

### Panel Guide (Single-Region and Multi-Region)

- **Capacity Overview**: percentile-banded time series (P5/P25/Mean/P75/P95) for total read and write capacity units per window
- **Per-Region Capacity and Cost** *(multi-region only)*: write and read capacity for the selected `regionName`; replicated WCU (rWCU) shown separately from locally-originated WCU
- **GSI Pressure**: write and read capacity for the selected `gsiIndexName` across all replicas
- **Storage and Cost**: storage growth, cumulative estimated cost, cross-region transfer bytes by direction, cumulative transfer cost, and per-region WCU totals
- **Returned Item Count by Operation**: mean items returned per window for Query vs. Scan operations; in a telemetry-heavy fleet, write volume dominates but Query and Scan counts are visible; both trend upward as the fleet grows

Operational notes:

- the dashboard defaults to simulation epoch time, not live wall-clock range
- `60` means 1-minute windows; `300` means 5-minute windows
- per-region and cross-region panels show no data for single-region batches
- raw per-tick records are staged alongside windowed records

---

## Understanding The Right-Sizing Trap (Mixed-Mode Dashboard)

The mixed-mode scenario is designed to illustrate a common and costly AWS capacity planning mistake: **choosing a provisioned WCU level based on the mean of observed on-demand consumption, then being surprised by throttling**.

### The Three Phases

The simulation runs 1,200 ticks (each tick = one second), divided into three billing-mode phases:

| Phase | Ticks | Billing Mode | WCU Setting | What Happens |
|-------|-------|--------------|-------------|--------------|
| On-demand | 1–400 | On-demand | — | No ceiling; all demand admitted; mean ~3,800 WCU/tick |
| Initial provisioned | 400–800 | Provisioned | 4,200 WCU | Set at ~110% of on-demand mean; below the 2× morning-spike peak (~7,600 WCU) |
| Adjusted provisioned | 800–1,200 | Provisioned | 12,500 WCU | Headroom above the evening spike; no throttling |

On the Grafana time axis (each tick mapped to one Unix second, displayed as wall-clock time):
- **Mode switch** (~16:06:40): visible as an abrupt drop in consumed WCU from the on-demand level to the provisioned ceiling
- **Morning spike** (~16:07–16:09): the 2× traffic spike hits the 4,200 WCU ceiling; throttle count spikes sharply
- **Capacity increase** (~16:13:20): provisioned WCU jumps to 12,500; consumed WCU rises immediately to meet full demand
- **Evening spike** (~16:17–16:19): absorbed without throttling under the 12,500 WCU envelope

### The Trap: Why the Mean Is the Wrong Planning Anchor

The fleet workload is stochastic: Poisson-distributed request rates, stochastic item sizes, and rare but intense **alert storms** (2× probability per tick, 30-tick duration, 5× write multiplier). When the capacity manager observed the on-demand phase and computed the mean WCU (~3,800), they missed two things:

1. **Deterministic spikes**: the morning and evening traffic peaks are 2× the baseline and are guaranteed to occur every day.
2. **Alert-storm variance**: alert storms are rare (expected 2–3 per trial), but when they land during a spike they combine multiplicatively. Trials that happen to have an alert storm during the morning spike see WCU well above the spike-only peak.

Setting provisioned capacity at 110% of the mean (~4,200 WCU) accounts for neither. The result: every trial experiences throttling during the morning spike, and trials with concurrent alert storms experience especially severe throttling.

### Reading the "Write Capacity: Consumed vs. Provisioned" Panel

This panel is the centerpiece of the right-sizing trap story. It shows five series:

| Series | Meaning |
|--------|---------|
| **Mean WCU** (white, thick) | Arithmetic mean of consumed WCU across all trials per window. The number a naive capacity planner would target. |
| **P50** (blue, thin) | Median consumed WCU. Equal to or slightly below the mean when the distribution is right-skewed. |
| **P75** (blue, thin) | The WCU level that 75% of trials stay under. Setting provisioned capacity here means ~25% of trials will be throttled. |
| **P95** (blue, thin) | The WCU level that 95% of trials stay under. A safer planning target if you want to limit throttling to ~5% of your demand patterns. |
| **Provisioned WCU** (orange dashed) | The active provisioned ceiling. Before the mode switch this is absent; after the mode switch it appears as a step function. |

The two shaded bands between P50→P75 and P75→P95 make the distribution shape visible at a glance.

**Key observations during the throttling phase (16:06–16:13):**

- The provisioned WCU line (4,200) sits *below* P75 and well below P95.
- Consumed WCU appears well below the provisioned ceiling.
- Yet the **Admitted vs. Throttled Requests** chart (above) shows heavy throttling.

The reason consumed is below provisioned even while throttling: **throttled requests never consume capacity**. The admission stage rejects excess demand before it reaches the storage layer. Consumed WCU is therefore bounded above by the provisioned ceiling, and the window total of consumed is diluted by the quieter ticks within each window where demand was below the ceiling. The provisioned ceiling is enforced per-tick, not per-window; the window average obscures the within-window spikes where the ceiling was hit.

**The right-sizing trap in one sentence:** the capacity utilization graph makes it look like you have headroom (consumed < provisioned), while the throttled-requests graph reveals you are routinely shedding demand.

**Why P75 sometimes appears above Mean:** alert storms create heavy right-skew in the WCU distribution. A minority of high-storm trials pull the arithmetic mean above the 75th percentile. This is exactly why P50 (median) and P75 — not the mean — are the appropriate planning anchors. The percentile bands make this skew visible.

**Choosing a provisioned level:**

- If the **P95 band** reaches up to or above the provisioned line, you are throttling in roughly 5% of demand patterns.
- If **P75** reaches the provisioned line, you are throttling in roughly 25% of patterns.
- A safe provisioned level is one where the dashed orange line sits comfortably above the P95 band at all times.
- In this demo, 12,500 WCU (the adjusted level) keeps the provisioned line above P95 throughout the evening spike, producing a clean run with no throttling events.

### The Billing Mode Timeline Panel

The **Billing Mode Timeline** panel shows a step function: 0 (blue, on-demand) for ticks 1–400, 1 (orange, provisioned) for ticks 400–1,200. This makes the phase transitions visually crisp and time-stamps the mode switch and capacity adjustment on the same time axis as the other panels.

### The Throttle Rate Panel

The **Throttle Rate** bar chart plots the count of throttled requests per window. Notice:

- During on-demand (ticks 1–400): zero throttles — all demand admitted
- During initial provisioned + morning spike (~16:07–16:09): sharp throttle spikes; the height of the bars reflects both the demand surplus above 4,200 WCU and the contribution of alert-storm trials
- After capacity increase: throttles vanish entirely

The absence of throttles in the evening spike (after tick 800) confirms that 12,500 WCU provides adequate headroom and validates the adjusted provisioned level.

### The Returned Item Count Panel

The **Returned Item Count by Operation** panel (at the bottom of the dashboard) shows the mean items returned per window from Query and Scan operations. In this workload:

- **Query** counts are driven by customer-support lookups against the `customer-devices` GSI; they rise slowly with fleet growth.
- **Scan** counts are lower, driven by fleet-dashboard scans against the `fleet-alerts` GSI; they are more irregular due to Poisson-distributed scan rates.

Both series should be non-zero throughout all three phases. During the throttling phase, Query and Scan operations are also subject to throttling if the table's read capacity is exhausted — though the default scenario provisions read capacity generously, so read throttling is rare.

---

## Understanding WCU Variance Across Trials (Single-Region / Multi-Region)

The GSI and total WCU charts show wide percentile bands (P95 roughly 2–3× P5 within any given time window). This section explains the two contributing factors.

### Primary driver: alert storms

Each simulation tick has a `alertStormProbabilityPerTick = 0.002` chance of triggering an alert
storm that lasts 30 ticks and multiplies the telemetry write rate by `alertStormWriteMultiplier =
5.0`. Over 1200 ticks the expected storm count per trial is approximately λ ≈ 2.4, Poisson-
distributed.

Because every telemetry PutItem drives index maintenance for all three GSIs, the GSI WCU total
tracks the total write count almost linearly:

| storm count | storm ticks | approximate write multiplier vs. baseline |
|-------------|-------------|-------------------------------------------|
| 0           | 0           | 1.0×                                      |
| 3           | 90          | ~1.3×                                     |
| 6           | 180         | ~1.6×                                     |

Trials near the P5 have few or no storms; trials near the P95 have five or more. The wide
percentile fan is intentional: it quantifies the cost uncertainty that unpredictable alert storms
impose, which is one of the primary findings this simulation is designed to surface.

### Secondary factor: morning and evening traffic spikes

`computeSpikeMultiplier` applies a deterministic triangular peak of up to 2× normal write rate
during ticks 420–540 (morning) and 1020–1140 (evening). These are identical across all trials,
so they shift the time-series shape without widening the percentile bands.

### GSI WCU modelling notes

The `IndexMaintenanceMath.derivePlan` logic determines per-GSI WCU consumption based on the
projection type and whether the previous item entry in the index is known:

- **`customer-devices` (KeysOnly)**: projected entry bytes are capped at 128 bytes regardless of
  item size. The sampler uses a probabilistic model to distinguish new-device writes (first record
  for that device → `previousItemBytes = None` → `InsertEntry` → 1 WCU charged) from
  existing-device updates (`previousItemBytes = averageItemBytes` → `min(newBytes, 128) ==
  min(prevBytes, 128)` for items ≥ 128 bytes → `NoOp` → 0 WCU). The new-device probability is
  `(totalDeviceCount − itemCount) / totalDeviceCount`, so the GSI WCU rate is highest early in
  the simulation and tapers to near-zero once every device has an established record.

- **`fleet-alerts` (Include 64 non-key bytes)**: capped at 192 bytes; same NoOp logic applies for
  existing-device updates where items are ≥ 192 bytes.

- **`device-status` (All)**: projected bytes equal item bytes. Because item sizes are sampled
  stochastically (±25% of the mean), new and previous sizes typically differ, so the action
  resolves to `ReplaceEntry` and 1 WCU is charged per write. This is conservative: the model
  charges 1× (new-item cost) but real DynamoDB charges 2× (delete old + insert new) when GSI key
  attributes actually change. Since the stochastic model cannot distinguish key-attribute changes
  from non-key changes, the 1× charge represents a deliberate lower-bound approximation.

- **Known limitation — `ReplaceEntry` undercharges**: `ReplaceEntry` always charges only the new
  item's WCU cost, never the delete cost for the old entry. When GSI key attributes do change on
  an update, real DynamoDB charges both. The magnitude of this underestimate scales with the
  fraction of writes where key attributes actually rotate (e.g., a device moving to a new
  customer).

## Stop The Stack

```bash
docker compose down
```

## Troubleshooting

### Schema Or Dashboard Provisioning Changed

```bash
docker compose down -v
docker compose up -d
```

Then regenerate and restage a fresh batch.

### Duplicate Batch Id

If `stage` fails because the batch id already exists:

- choose a new `--batch-id`, or
- recreate the Docker state with `docker compose down -v && docker compose up -d`

### `/tmp` On macOS

On macOS, `/tmp` is a symlink to `/private/tmp`. If the generated file does not appear under `/tmp`, check `/private/tmp`.

### Multi-Region Is Slower

Multi-region batches run N regional table simulations per trial. For 100 trials at 1200 ticks each, generation takes several minutes. Use `--parallelism 8` or higher on multi-core machines.
