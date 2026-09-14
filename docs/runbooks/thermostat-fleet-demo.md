# DynamoDB Demos — Grafana Runbook

## Purpose

This runbook describes the operator workflow for staging any of the DynamoDB demos into Postgres and
viewing its Grafana dashboard:

1. start the Docker services
2. `generate` a simulation batch for a demo
3. `stage` that batch into Postgres
4. `view` — open the provisioned Grafana dashboard

All three steps run through one CLI, `GrafanaDemoBridge`, selecting the demo with `--demo <name>`.

## The demos

| `--demo` | Dashboard | Story |
|---|---|---|
| `order-tracking-phase1` | Order-Tracking Phase 1 | on-demand single table: capacity / storage / cost |
| `order-tracking-indexed` | Order-Tracking Phase 2 | adds GSIs: per-GSI capacity |
| `thermostat-fleet` | Thermostat Fleet | single-region IoT fleet, mixed-projection GSIs |
| `thermostat-mixed-mode` | Thermostat Fleet — Mixed Billing Mode | the **right-sizing trap** (on-demand → provisioned → right-size) |
| `thermostat-fleet-multi-table` | Thermostat Fleet Multi-Table | per-table cost attribution (registry vs telemetry) |
| `thermostat-fleet-capstone` | Thermostat Fleet — Capstone | the 4-table integration: cost, auto-scaling, throttle, TTL |

## Prerequisites

- Docker is installed and running on the host
- `sbt` is installed and available
- the current working directory is the repo root

## Start the stack

```bash
docker compose up -d
```

This starts:

- Postgres on `localhost:5432`
- Grafana on `localhost:3000`

Default credentials:

- Postgres database: `stochastacy_demo`; user `stochastacy`; password `stochastacy`
- Grafana user: `admin`; password: `admin`

## The workflow

The three steps take `--demo <name>` and a `--batch-id` you choose. `generate` and `stage` accept optional
`--trials`, `--ticks`, `--parallelism`, and `--seed`; omitted, each demo uses its own defaults.

### Generate

```bash
sbt 'examples/runMain stochastacy.examples.grafana.GrafanaDemoBridge generate --demo thermostat-mixed-mode --batch-id mm-001 --output /tmp/mm-001.jsonl'
```

Runs the demo's Monte Carlo ensemble and writes the staging JSONL (per-trial and per-tick records, the
windowed rollups the dashboards' time panels read, and the across-trial aggregate summaries).

> **Note:** the mixed-mode demo reconfigures at ticks 400 and 800, so its `--ticks` (if you override the
> default) must be greater than 800. The multi-table and capstone demos run several tables per trial, so
> keep `--parallelism` modest (≤ 4) to stay within the default heap.

### Stage

```bash
sbt 'examples/runMain stochastacy.examples.grafana.GrafanaDemoBridge stage --demo thermostat-mixed-mode --batch-id mm-001 --input /tmp/mm-001.jsonl --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy'
```

Loads the JSONL into the generic `demo_batches` / `demo_records` schema. Pass the same `--trials`/`--ticks`/
`--parallelism`/`--seed` you gave `generate` so the batch metadata matches.

### View

```bash
sbt 'examples/runMain stochastacy.examples.grafana.GrafanaDemoBridge view --demo thermostat-mixed-mode --batch-id mm-001'
```

Prints the Grafana URL for the demo's dashboard, pre-filtered to this `batch_id` and scenario. Add
`--grafana-base-url <url>` if Grafana is not on `http://localhost:3000`.

## Using a dashboard

1. select the staged `batch_id`
2. select a `Window Size` of `60` (1-minute windows) or `300` (5-minute windows)
3. for the indexed / thermostat demos, select a `GSI Index Name` where the dashboard offers one
4. inspect the capacity, storage, and cost panels

Operational notes:

- the dashboards plot simulation epoch time (each tick mapped to one Unix second), not live wall-clock range
- raw per-tick records are staged alongside the windowed records
- the dashboards show the metrics the demos produce today: per-window read/write capacity, storage bytes,
  cumulative and total cost, per-GSI and (multi-table) per-table breakouts, and — for provisioned tables —
  provisioned capacity, billing mode, throttle count, and the TTL-deletion flow

---

## Understanding the right-sizing trap (mixed-mode dashboard)

The mixed-mode scenario illustrates a common, costly capacity-planning mistake: **choosing a provisioned
WCU level from the mean of observed on-demand consumption, then being surprised by throttling.**

### The three phases

The simulation runs 1,200 ticks (one second each), in three billing-mode phases:

| Phase | Ticks | Billing Mode | What happens |
|---|---|---|---|
| On-demand | 1–400 | On-demand | no ceiling; all demand admitted; mean well below the spike peaks |
| Initial provisioned | 400–800 | Provisioned | set near the on-demand **mean** — below the 2× morning-spike peak |
| Adjusted provisioned | 800–1,200 | Provisioned | headroom above the evening spike; no throttling |

### The trap

The fleet workload is stochastic — Poisson request rates, stochastic item sizes, and rare intense **alert
storms**. Planning to the on-demand *mean* ignores two things: the **deterministic morning/evening spikes**
(2× baseline, guaranteed daily) and **alert-storm variance** (rare, but multiplicative when they land during
a spike). So the initial provisioned level throttles during the morning spike, worst in storm trials.

### Reading the panels

- **Billing Mode Timeline** — a step function: 0 (on-demand) for ticks 1–400, 1 (provisioned) thereafter.
  Time-stamps the mode switch on the same axis as everything else.
- **Consumed vs. Provisioned (WCU)** — consumed WCU (mean across trials) against the provisioned ceiling
  (a step that appears at the mode switch). During the throttling window the ceiling sits below the demand
  peaks, yet consumed appears *below* the ceiling — because **throttled requests never consume capacity**
  (admission rejects them before the storage layer), and the per-tick ceiling is diluted by the quieter
  ticks within each window. The utilization graph looks like headroom while demand is being shed.
- **Throttle Rate** — throttled requests per window. **Zero** during on-demand (1–400); **sharp spikes**
  during the initial-provisioned morning spike (~ticks 420–540); **gone** after the tick-800 adjustment.
  This is the panel that reveals the shed demand the utilization graph hides, and it confirms the adjusted
  level (no throttles through the evening spike) is adequately sized.
- **Cumulative Estimated Cost** — the running cost; provisioned billing is by reserved capacity-hours, so
  cost tracks the reservation, not the consumed capacity.

**The trap in one sentence:** the utilization panel makes it look like you have headroom (consumed <
provisioned), while the throttle-rate panel shows you are routinely shedding demand.

---

## Understanding WCU variance across trials

The capacity charts show wide percentile bands (P95 roughly 2–3× P5 within a window). Two factors:

- **Alert storms (primary).** Each tick has `alertStormProbabilityPerTick = 0.002` of starting a 30-tick
  storm that multiplies the telemetry write rate by `alertStormWriteMultiplier = 5.0` — expected ≈ 2–3 per
  trial, Poisson-distributed. Because every telemetry `PutItem` drives maintenance on all GSIs, WCU tracks
  the write count almost linearly, so storm-heavy trials sit near P95 and storm-free ones near P5. The wide
  fan is intentional: it quantifies the cost uncertainty unpredictable storms impose.
- **Traffic spikes (secondary).** A deterministic triangular peak of up to 2× the write rate during the
  morning and evening windows. Identical across trials, so it shifts the time-series shape without widening
  the bands.

## Stop the stack

```bash
docker compose down          # keep the data
docker compose down -v       # drop the Postgres + Grafana volumes (fresh schema / dashboards next up)
```

## Troubleshooting

- **Schema or dashboard provisioning changed:** `docker compose down -v && docker compose up -d`, then
  regenerate and restage.
- **Duplicate batch id:** `stage` fails if the `batch_id` already exists — choose a new `--batch-id`, or
  recreate the Docker state with `docker compose down -v && docker compose up -d`.
- **`/tmp` on macOS** is a symlink to `/private/tmp`; if a generated file does not appear under `/tmp`,
  check `/private/tmp`.
- **Multi-table / capstone are slower:** they run several table simulations per trial; at full defaults
  generation takes a few minutes. Reduce `--trials`/`--ticks` for a quick look.
