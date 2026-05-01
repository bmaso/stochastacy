# Thermostat Fleet Demo Runbook

## Purpose

This runbook describes the operator workflow for the phase-3 thermostat fleet DynamoDB demo:

1. start the Docker services
2. generate a simulation batch (single-region or multi-region)
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

## Mixed-Mode

### Generate A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-fleet-mm-001 --output /tmp/thermostat-fleet-mm-001.jsonl --mode mixed-mode --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

This simulates the thermostat fleet in a single region (`us-east-1`) across three billing-mode phases:

1. **On-demand (ticks 1–400):** No capacity ceiling. The fleet consumes approximately 3,800 WCU/tick at the observed mean, rising with fleet growth.
2. **Provisioned — initial capacity (ticks 400–800):** Billing mode switches to provisioned at 4,000 WCU (≈105% of the observed on-demand mean) and 250 RCU. This level is intentionally set below the 2× morning-spike peak. Throttles begin as soon as the spike ramps up around tick 420 and clear when it subsides around tick 540.
3. **Provisioned — adjusted capacity (ticks 800–1200):** Provisioned capacity is raised to 12,500 WCU and 100 RCU at the two-thirds mark. This provides headroom above the 2× evening-spike peak (≈7,600 WCU) and eliminates all throttling for the remainder of the simulation.

Optional flags let you override the provisioned levels at the command line:

```bash
--initial-provisioned-wcu <long>    # default: 4200
--adjusted-provisioned-wcu <long>   # default: 12500
```

### Spike Narrative

The chart shows two notable operation features in the provisioned phases:

- **Morning traffic spike (~ticks 420–540):** Every device reports telemetry at 2× the normal rate as users start their day. This is a deterministic triangular peak built into the fleet behavior. Under the 4,000 WCU initial ceiling the peak demand of ≈7,600 WCU cannot be fully admitted, so throttling appears sharply and subsides once the spike rolls off around tick 540. On the Grafana time axis (which treats each tick as one Unix second), this feature appears at approximately the 7-minute mark of the 20-minute simulation window — around 16:07 UTC in the dashboard default epoch.

- **Capacity adjustment (~tick 800):** Provisioned WCU jumps from 4,200 to 12,500. The admission stage immediately opens the ceiling to full demand, producing a visible step-up in consumed WCU and admitted request count. On the Grafana time axis this appears at approximately the 13-minute mark — around 16:13 UTC. Because admitted-request and WCU metrics rise sharply from the provisioned floor to the full demand level, this step-up is visually prominent and may appear as a second "spike" adjacent to the post-adjustment plateau.

A symmetric **evening spike** (ticks 1020–1140) falls entirely within the 12,500 WCU envelope and produces no throttling — it is visible as a smooth rise-and-fall in the WCU and admitted-request charts with no corresponding throttle events.

### Stage A Batch

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-fleet-mm-001.jsonl --batch-id thermostat-fleet-mm-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

### Open The Mixed-Mode Dashboard

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-mm-001 --mode mixed-mode'
```

## Open The Dashboard

Print the Grafana URL:

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-sr-001 --mode single-region'
```

Then open the printed URL in a browser and log into Grafana with:

- user: `admin`
- password: `admin`

## Use The Dashboard

For the normal happy path:

1. select the staged `batch_id`
2. select a `Window Size` of `60` or `300`
3. inspect the **Capacity Overview** row for total RCU/WCU across all trials
4. for multi-region batches: select a `Region Name` and inspect the **Per-Region Write Capacity** row
5. select a `GSI Index Name` and inspect the **GSI Pressure** row
6. inspect the **Storage and Cost** row for storage bytes, cumulative cost, cross-region transfer, and per-region WCU totals

### Panel guide

- **Capacity Overview**: percentile-banded time series (P5/P25/Mean/P75/P95) for total read and write capacity units per window
- **Per-Region Write Capacity**: write and read capacity for the selected `regionName` (only populated for multi-region batches)
- **GSI Pressure**: write and read capacity for the selected `gsiIndexName` across all replicas
- **Storage and Cost**: storage growth, cumulative estimated cost, cross-region transfer bytes by direction, and per-region WCU totals

Operational notes:

- the dashboard defaults to simulation epoch time, not live wall-clock range
- `60` means 1-minute windows; `300` means 5-minute windows
- per-region and cross-region panels show no data for single-region batches
- raw per-tick records are staged alongside windowed records

## Understanding WCU Variance Across Trials

The GSI and total WCU charts show wide percentile bands (P95 roughly 2–3× P5 within any given
time window). This section explains the two contributing factors.

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
