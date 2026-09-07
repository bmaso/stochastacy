# v2/phase12 — Grafana delivery + legacy retirement

**Status: PLANNED** — the **final phase** of the v2 line. Two efforts, done in order:

1. **Grafana delivery** — port the legacy `generate → stage → view` Postgres/Grafana pipeline to the **v2 AWS
   demos**, so each legacy-dashboarded demo has a v2 path that lands the **same dashboard**.
2. **Legacy retirement** — once the v2 pipeline is in place, **delete the legacy `stochastacy.aws` simulator,
   the legacy `stochastacy.workload` DSL, and the legacy `examples` demos**.

Follows `v2/phase11` (single-region + multi-region parity reached). Parity is **settled** (the reconciliation
specs across order-tracking, thermostat, hot-key, and hot-replica establish it) — a v2 demo without a legacy
counterpart is fine, and retirement does **not** wait on any further parity audit.

## Decisions (settled with Brian)

- **Host the v2 pipeline in `examples`** — no new module. `examples` already carries the generic staging layer
  (`stochastacy.demo.*` — JSONL export, incremental Monte Carlo aggregation, time-window rollups) plus the
  PostgreSQL / H2 deps, and the Postgres schema (`demo_batches` + `demo_records`), `docker-compose` (Postgres +
  Grafana), and dashboard JSON. `examples` gains **`dependsOn(aws)`** so a v2 bridge can drive the v2 runners.
- **Match the legacy dashboards only** — six of them, mapping to six v2 demos:

  | legacy dashboard | v2 demo (`@main`, `aws` module) |
  |---|---|
  | `order-tracking-phase1-dashboard.json` | `OrderTrackingDemo` |
  | `order-tracking-phase2-dashboard.json` | `IndexedOrderTrackingDemo` |
  | `thermostat-fleet-dashboard.json` | `ThermostatFleetDemo` (single-region) |
  | `thermostat-fleet-mixed-mode-dashboard.json` | `ThermostatMixedModeDemo` |
  | `thermostat-fleet-multi-table-dashboard.json` | `ThermostatMultiTableDemo` |
  | `thermostat-fleet-capstone-dashboard.json` | `ThermostatCapstoneDemo` |

  The v2-only demos (hot-key, hot-replica, session-store, payments, auto-scaling) get **no** dashboard this phase.
- **Grafana delivery first, retirement last** — retirement is the finish line.
- **Reuse the staging model + docker-compose + schema as-is.** The `demo_batches` / `demo_records`
  (batch → per-tick metric/value/statistic rows) model is domain-agnostic; the v2 bridge feeds it via the
  existing `DemoJsonlExporter`. Dashboards are **reused** where the v2 metric names already match, and adapted
  only where v2 diverges (e.g. v2's more-correct scan/index reads, or the provisioned/throttle dimensions the
  legacy demo lacked) — so the same dashboard JSON files carry forward and are **not** deleted at retirement.

## Goal

Every legacy-dashboarded demo has a **v2 `generate → stage → view`** path (a bridge that runs the v2 Monte
Carlo runner, stages its per-tick metrics into Postgres via the shared staging layer, and provisions the Grafana
dashboard), proven end-to-end against a live Postgres + Grafana. Then the legacy simulator, DSL, and demos are
deleted and the build/tests are green without them.

## Open design questions (to settle per slice)

- **The v2 bridge shape.** A shared `V2DemoBridge` (generate/stage/view) that each demo plugs a runner +
  metric-mapping into, vs. a per-demo bridge like the legacy `ThermostatFleetBridge`. Lean: one shared bridge +
  a small per-demo adapter (the demos already share the `SingleTable`/`MultiTable` harness).
- **Metric mapping.** How each v2 runner's aggregated output (summary + per-tick time series) becomes
  `DemoExportRecord` rows whose `metric` names the dashboards query — reuse the demos' JSONL exporters, or map
  from the runner results directly. Settle in Slice 1 and reuse the shape thereafter.

## Slices

| # | Title | Status | Delivered / notes |
|---|---|---|---|
| 1 | Pipeline foundation + order-tracking end-to-end | Planned | `examples.dependsOn(aws)`; a shared v2 bridge (`generate → stage → view`) over the existing `stochastacy.demo.*` staging + Postgres schema + docker-compose; both order-tracking dashboards (p1 `OrderTrackingDemo`, p2 `IndexedOrderTrackingDemo`) ported and proven end-to-end; a Grafana-assets spec |
| 2 | Thermostat single-region + mixed-mode | Planned | the `ThermostatFleetDemo` + `ThermostatMixedModeDemo` bridge modes + dashboards, reusing the Slice-1 shape |
| 3 | Thermostat multi-table + capstone | Planned | the `ThermostatMultiTableDemo` + `ThermostatCapstoneDemo` bridge modes + dashboards (per-table metrics) |
| 4 | Legacy retirement + close-out | Planned | delete legacy `core/…/stochastacy/aws` + `stochastacy/workload`, the legacy `examples` `ordertracking`/`thermostatfleet` demos + bridges, the `samplerExports` shim; fix build deps; update runbooks + CLAUDE.md to the v2 bridge; full `sbt test` green; roadmap COMPLETE, program roadmap, memory |

### Slice 1 — Pipeline foundation + order-tracking end-to-end
The heavy slice: stand up the whole v2 pipeline and prove it on the simplest demo. Add `examples.dependsOn(aws)`.
Build a shared v2 bridge with `generate` (run the v2 `OrderTracking{,Indexed}MonteCarloRunner`, emit
`DemoExportRecord` JSONL via `DemoJsonlExporter`), `stage` (the existing Postgres loader — `demo_batches` /
`demo_records`), and `view` (Grafana dashboard provisioning). Port the two order-tracking dashboards to the v2
metric names, and validate the round trip against the `docker-compose` Postgres + Grafana. A Grafana-assets spec
(mirroring `OrderTrackingGrafanaAssetsSpec`) locks the dashboard JSON.

### Slice 2 — Thermostat single-region + mixed-mode
Reuse the Slice-1 bridge shape for `ThermostatFleetDemo` (single-region) and `ThermostatMixedModeDemo` (the
mixed-mode provisioned/reconfiguration story — its dashboard carries the extra provisioned/throttle panels).

### Slice 3 — Thermostat multi-table + capstone
`ThermostatMultiTableDemo` (per-table `Table:<name>:…` metrics) and `ThermostatCapstoneDemo` (the 4-table fleet),
the richest dashboards. Confirms the per-table metric shape stages and charts correctly.

### Slice 4 — Legacy retirement + close-out
Delete the legacy simulator and demos now that every legacy-dashboarded demo has a v2 pipeline:
`core/src/main/scala/stochastacy/aws/**` (dynamodb / transfer / events), `stochastacy/workload/**` (the DSL +
`samplerExports` export shim), and the legacy `examples` demos `ordertracking` / `thermostatfleet` (+ their
bridges and any legacy-only dashboards/runbooks). Fix the build (remove the shim's consumers, prune deps), update
`docs/runbooks/` and CLAUDE.md's demo workflows to the v2 bridge, and gate on a green full `sbt test`. Close-out
coda: this roadmap COMPLETE, `v2-program.md` (the v2 line reaches its finish line), memory.

## Scope boundary

The six legacy-matched dashboards, staged from the v2 demos through the existing Postgres/Grafana pipeline, then
the legacy code deleted. **Not in scope:** dashboards for the v2-only demos (hot-key, hot-replica, session-store,
payments, auto-scaling); new metrics or visualizations beyond what the legacy dashboards show; any change to the
staging schema or docker-compose beyond what the v2 metrics require. This is the **last v2 phase** — after it, the
repository is v2-only.
