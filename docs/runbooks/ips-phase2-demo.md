# IPS Phase 2 Demo Runbook

## Purpose

This runbook describes the normal operator flow for the phase-2 DynamoDB demo:

1. start the Docker services
2. generate a simulation batch
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

## Generate A Batch

Choose a fresh batch id for each run. Example:

```bash
sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge generate --batch-id order-tracking-phase2-demo-001 --output /tmp/order-tracking-phase2-demo-001.jsonl --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

Notes:

- `generate` writes JSONL to the path passed in `--output`
- on macOS, `/tmp` resolves to `/private/tmp`
- `generate` may be run repeatedly

## Stage A Batch Into Postgres

Use the same batch id and the same trial-count, parallelism, and simulation-ticks metadata that were used during generation.

```bash
sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge stage --input /tmp/order-tracking-phase2-demo-001.jsonl --batch-id order-tracking-phase2-demo-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'
```

Notes:

- `stage` rejects duplicate `batch_id` values
- staging loads both raw records and windowed records into `stochastacy_demo.demo_records`
- the staged batch includes `60s` and `300s` windowed rollups

## Open The Dashboard

Print the Grafana URL:

```bash
sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge view --batch-id order-tracking-phase2-demo-001'
```

Then open the printed URL in a browser and log into Grafana with:

- user: `admin`
- password: `admin`

## Use The Dashboard

For the normal happy path:

1. select the staged `batch_id`
2. confirm the `scenarioId`
3. choose a `Window Size` of `60` or `300`
4. inspect the overview, total capacity, selected-GSI capacity, storage, and cost panels

Operational notes:

- the dashboard defaults to the simulation epoch time window, not a live wall-clock range
- `60` means 1-minute windows
- `300` means 5-minute windows
- raw per-tick records are still staged even though the main panels use windowed records

## Stop The Stack

```bash
docker compose down
```

## Troubleshooting

### Schema Or Dashboard Provisioning Changed

If the Postgres schema or provisioned Grafana assets have changed, recreate the persistent Docker state:

```bash
docker compose down -v
docker compose up -d
```

Then regenerate and restage a fresh batch.

### JSONL Shape Changed

If the export schema has changed, do not reuse older staged data for the updated dashboard. Regenerate and restage a fresh batch.

### Duplicate Batch Id

If `stage` fails because the batch id already exists:

- choose a new `--batch-id`, or
- recreate the Docker state if you intentionally want an empty demo database

### `/tmp` On macOS

If the generated file does not appear under `/tmp`, check `/private/tmp`. On macOS, `/tmp` is a symlink to `/private/tmp`.
