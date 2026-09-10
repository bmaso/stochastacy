# v2/phase12 — Grafana delivery + legacy retirement

**Status: COMPLETE** — the **final phase** of the v2 line. Two efforts, done in order:

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
| 1 | Delete legacy examples demos + pipeline foundation + order-tracking | **Done** | forced reorder (Brian-approved): `examples.dependsOn(aws)` puts the v2 `stochastacy.aws.dynamodb` beside `core`'s legacy same-named package, so the **legacy `examples` demos had to go first** (their imports go ambiguous); then a shared v2 bridge (`GrafanaBridge` + `DemoPostgresStaging` in `stochastacy.demo`, `@main GrafanaDemoBridge`) over the existing schema/docker-compose, both order-tracking dashboards reused and proven end-to-end (H2 round-trip) + a Grafana-assets spec |
| 2 | Thermostat single-region + mixed-mode | **Done** | registered both demos; a small Tier-1 accounting extension (per-tick provisioned capacity + throttle count, from facts already folded to summary) so the mixed-mode **right-sizing-trap** story is visible; both dashboards adapted to v2's metric set (dropped latency/returned/system-error/multi-region panels); H2 round-trips incl. the provisioned throttle path |
| 3 | Thermostat multi-table + capstone | **Done** | a multi-table bridge path (`adaptMultiTable`/`generateMultiTable` over `MultiTableMonteCarloRunner`) + a sealed `DemoKind` (Single \| Multi) registry, wiring both demos; one new **native** metric `TimeToLiveDeletedItemCount` (the TTL-deletion flow, an already-computed value); capstone dashboard swaps the item-count *stock* panel for the TTL-deletion flow and drops latency/system-error; multi-table dashboard unchanged; H2 round-trip witnessing real TTL deletions |
| 4 | Legacy **core** retirement + close-out | **Done** | deleted the legacy **core** `stochastacy/aws` + `stochastacy/workload` (incl. the `samplerExports` shim), the orphaned `visualizer` module (a DSL front-end) and dead `stochastacy.app` scaffolding; pruned the build (snakeyaml / pekko-http); **erased every "legacy" reference from code + living docs** (per Brian: describe the simulator on its own terms) — the 8 reconcile specs reframed as `*BaselineSpec`, comments/CLAUDE.md/specs/README scrubbed, purely-legacy docs (ips roadmaps, `docs/architecture`, `docs/specs`, ips handoff) deleted; runbook + CLAUDE.md workflows moved to the v2 `GrafanaDemoBridge`; full `sbt test` green (290). Roadmaps retain "legacy" as archival record |

### Slice 1 — Delete legacy examples demos + pipeline foundation + order-tracking
The heavy slice. **Forced reorder (Brian-approved):** `examples.dependsOn(aws)` puts the v2 `stochastacy.aws.dynamodb`
package on the same classpath as `core`'s legacy same-named package; the legacy `examples` demos import the legacy
one, so every reference goes ambiguous (and the `.class` files collide on a case-insensitive filesystem). So the
**legacy `examples` `ordertracking` + `thermostatfleet` demos (main + test) were deleted first** — they are exactly
what the v2 pipeline replaces, nothing surviving references them, and the aws reconcile specs cite the legacy only in
capture-command comments. The legacy **core** simulator stays for Slice 4.

**Delivered.** `examples.dependsOn(aws)` (brings `core` transitively, v2 classes ahead of the legacy). A surviving
generic staging home in `stochastacy.demo`: `DemoPostgresStaging` (JDBC loader — schema + `demo_batches`/`demo_records`,
metric-agnostic, a generic `001-schema.sql` resource) + `GrafanaBridge` (adapts a v2 `SingleTableScenario` run into the
generic `TrialResult` model — the v2 metric strings already equal `DemoMetric.exportName` — and drives it through
`DemoReportBuilder`, which adds the **windowed** records the dashboards' time panels need and the v2 AWS exporter never
emitted). A shared `@main GrafanaDemoBridge` CLI (`generate|stage|view`, `--demo <name>`) wires both order-tracking
demos, reusing the legacy dashboards unchanged. Proven end-to-end (minus live Grafana) by `GrafanaBridgeSpec` — an H2
`generate → stage` round trip asserting every dashboard record family populates, including the window views and the
per-GSI metrics — plus `GrafanaAssetsSpec` (dashboards + pipeline assets). Live Grafana `view` is runbook-verified
(Slice 4). Full `sbt test` green.

### Slice 2 — Thermostat single-region + mixed-mode
Reuse the Slice-1 bridge for `ThermostatFleetDemo` + `ThermostatMixedModeDemo` (both `SingleTableScenario`s).

**The metric gap + how we handled it.** The legacy thermostat dashboards query a far richer per-tick set than the
v2 demos emit (per-op latency percentiles, returned-item counts, system-error counts, multi-region panels, and —
mixed-mode — per-tick provisioned capacity / throttle / billing-mode / admitted). This is **not** a fundamental v2
incapacity: the simulation produces all the underlying events; the demos' cost-only accounting just doesn't fold them
(some are on the response plane the demos ignore; per-op latency is drawn but not surfaced as a fact). Per Brian's
call, we **adapt the dashboards to what v2 produces now** and build the rest only when a later demo needs it (no
Known-discrepancies note — these are "produce later", not "won't do").

**Delivered.** One **Tier-1 accounting extension** (the throttle story genuinely needs it — a summary total hides
*when* throttling starts): `TrialTimeSeriesPoint` + `TrialAccountingState` now carry per-tick provisioned RCU/WCU +
throttle count (from `ProvisionedCapacitySnapshot` / `RequestThrottled`, already folded to summary; additive, defaulted
— on-demand demos unchanged). `GrafanaBridge.adaptSingleTable` gained a `provisioned` flag and maps these to
`ProvisionedReadCapacityUnits`/`WriteCapacityUnits`/`ThrottleCount`/`BillingModeIndicator` (the generic
`TimeWindowRollups` already knew these metrics). Both demos registered in `GrafanaDemoBridge`. Both dashboards
**adapted in place** (fleet 27→13 panels, mixed-mode 17→11) — dropping latency-percentile / returned-item /
system-error / multi-region / admitted panels, keeping capacity/storage/cost/GSI and (mixed-mode) the billing-mode /
consumed-vs-provisioned / throttle panels that tell the right-sizing trap. `GrafanaBridgeSpec` gained a provisioned
thermostat H2 round-trip (per-tick provisioned/throttle records populate); `GrafanaAssetsSpec` covers both dashboards.
Full `sbt test` green.

### Slice 3 — Thermostat multi-table + capstone
`thermostat-fleet-multi-table` (`twoTableDefault` — device-registry read-heavy + device-telemetry write-heavy) and
`thermostat-fleet-capstone` (`capstoneDefault` — the 4-table integration proof), the richest dashboards, both with
per-table `Table:<name>:…` metrics.

**The one new metric (grounded in AWS).** The capstone's Telemetry table has TTL, and the legacy dashboard charted a
"live item count" *stock*. We checked AWS: there is **no native CloudWatch item-count metric** — `DescribeTable.ItemCount`
is an approximate value refreshed ~every 6 h that operators push as a *custom* metric, so a live-item-count panel is off
the table under the phase's "native CloudWatch metrics only" rule (Brian's call). The **native** TTL signal is
`TimeToLiveDeletedItemCount` — the per-period count of items deleted by TTL (a *flow*). We surface that instead: it is the
item count the table **already computes** when its `TtlRingBuffer` cohort drains at `onTick` and previously discarded.

**Delivered.** A new **native** consumption fact `TimeToLiveDeletedItemCount(count)` (`consumption.scala`), emitted from
`DynamoDbTable.onTick` only when a TTL cohort actually expires (non-TTL tables byte-identical); folded per-tick in
`TrialAccounting` (`TrialTimeSeriesPoint.ttlDeletedItemCount`, additive/defaulted) and mapped by the bridge to the new
generic `DemoMetric.TableTimeToLiveDeletedItemCount` (a summed-flow window rollup). The bridge grew a **multi-table path**
— `GrafanaBridge.adaptMultiTable` (each table's per-tick + summary as `Table:<name>:…`, provisioned/TTL extras gated by
`TableSpec.usesProvisioning`/`usesTtl`; no per-GSI breakout — no multi-table dashboard charts it) + `generateMultiTable`
over `MultiTableMonteCarloRunner`, driven through `DemoReportBuilder` for the windowed records — and the CLI registry was
generalized to a sealed **`DemoKind` (Single | Multi)**, wiring `thermostat-fleet-multi-table` (uid `ips-phase6-multi-table`)
and `thermostat-fleet-capstone` (uid `ips-phase6-capstone`). The **capstone dashboard** was adapted in place (20→15 panels):
the "Estimated Live Item Count" panel became **"TTL Deleted Item Count per Window"** (`Table:device-telemetry:TimeToLiveDeletedItemCount`),
and the system-error + latency-percentile panels were dropped (Tier-2/3, not v2-produced). The **multi-table dashboard is
unchanged** — every metric it queries (`Table:<n>:{RCU,WCU,StorageBytes,CumulativeEstimatedCost,TotalEstimatedCost}`) is
already produced. `GrafanaBridgeSpec` gained a capstone H2 round-trip (per-table families populate; the telemetry TTL is
shortened to 4 ticks so `Table:device-telemetry:TimeToLiveDeletedItemCount` is actually **> 0**; non-TTL tables emit no TTL
metric; `EstimatedItemCount` is gone), and `GrafanaAssetsSpec` covers both dashboards. Four aws TTL/transaction specs were
updated — the new fact is now correctly part of the expiry-tick emission. Full `sbt test` green (290 aws + core + examples).

### Slice 4 — Legacy retirement + close-out — **Done**
Two intertwined efforts: delete the superseded implementation, and — per Brian — **erase every "legacy" reference**
so the simulator reads on its own terms, as if a prior implementation never existed.

**Deleted.** `core/src/main/scala/stochastacy/aws/**` + `core/src/test/.../aws/**` (the first-generation simulator:
dynamodb / transfer / events), `stochastacy/workload/**` (the DSL + `samplerExports` shim), the **`visualizer`
module** (a web front-end for the DSL — orphaned by its deletion) and the dead `stochastacy.app` hello-world
scaffolding (both surfaced at compile time as the only remaining `pekko-http` users). Build pruned: `snakeyaml`,
`pekko-http`, `pekko-http-json4s` dropped; `examples` keeps `json4s-jackson`. Purely-legacy docs removed
(`docs/roadmaps/ips-phase*`, all of `docs/architecture`, all of `docs/specs`, the ips handoff, the ips runbooks).

**Reframed / scrubbed.** The 8 reconcile/equivalence specs → `*BaselineSpec` (numbers = the demo's established
baseline, divergences = intrinsic cost characteristics; no logic/number changes). "legacy" removed from every code
comment (`aws/src/main` + examples), CLAUDE.md (Current position rewritten as capabilities; the legacy source-file
and simulator sections deleted), the six `specs/` engineer guides, and the root README. The dead
`EstimatedItemCount` / `TableEstimatedItemCount` metric vocabulary was removed. Roadmaps under `docs/roadmaps/`
**retain** their "legacy" wording as an archival record (Brian's call). The `docs/runbooks/thermostat-fleet-demo.md`
runbook and CLAUDE.md's demo workflows now drive the v2 `GrafanaDemoBridge` (`--demo <name>`, six demos).

**Gated.** Full `sbt test` green (290 aws + core + examples; the four aws specs whose expiry-tick assertions the
Slice-3 TTL fact touched, and the reframed baseline specs, all pass). The repo-wide "legacy" sweep is empty outside
the archival roadmaps. Close-out coda: this roadmap COMPLETE, `v2-program.md` at its finish line, memory.

## Scope boundary

The six legacy-matched dashboards, staged from the v2 demos through the existing Postgres/Grafana pipeline, then
the legacy code deleted. **Not in scope:** dashboards for the v2-only demos (hot-key, hot-replica, session-store,
payments, auto-scaling); new metrics or visualizations beyond what the legacy dashboards show; any change to the
staging schema or docker-compose beyond what the v2 metrics require. This is the **last v2 phase** — after it, the
repository is v2-only.
