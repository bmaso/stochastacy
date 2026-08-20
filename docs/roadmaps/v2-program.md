# v2 program roadmap — porting the legacy AWS demos onto the domain-agnostic core

This is the **program-level** map: how the v2 effort (a domain-agnostic `stochastacy.core` engine, proven
by example simulators) progresses from its first redefinition to full parity with the legacy AWS DynamoDB
line, at which point the legacy code is retired. Each **phase** is a demo-provable increment with its own
detailed roadmap under `docs/roadmaps/v2-phaseN.md`; this document is the index and the rationale for the
sequence.

## Guiding method

- **Smallest viable increment that forces the essential new component into existence**, proven by a demo.
- **Reconcile against a legacy scenario** where one exists — asserting equivalence where we stayed
  faithful and *quantifying* any divergence where we deliberately improved the model (as with the phase-2
  storage-billing fix and the phase-3 scan-cost model).
- **The table is the composable graph-level unit.** Cross-cutting *edge* behavior (throttling, latency,
  failure) is added by decoration (`Interface.wrap` gates from the core); *intrinsic table structure*
  (indexes, billing mode, TTL) is added by configuration. This rule, settled in phase-3, is what lets the
  later phases slot throttling in as a gate and compose multiple tables without new graph machinery.
- The legacy `stochastacy.aws` code and legacy `examples` demos stay **frozen** — run only to capture
  reconciliation baselines — until the final phase deletes them.

## Done

- **v2/phase0** — Redefined `stochastacy.core` as the domain-agnostic engine; proven by the **Store demo**.
- **v2/phase1** — The reusable **interface / gating components** (latency, throttle, burst, chaos), proven
  by **Store Demo V2**.
- **v2/phase2** — The AWS line begins on the v2 core: **Order-Tracking Phase-1** (single on-demand table,
  get/put/update/delete) re-implemented in the new `aws` module; found + fixed a legacy storage-billing
  bug. Roadmap: `v2-phase2.md`.
- **v2/phase3** — **Indexed Order-Tracking** (Query/Scan + GSIs/LSIs): indexes as intrinsic table config,
  `SecondaryIndexMechanics`, an improved read model (scan cost grows with the table), per-GSI reporting.
  Roadmap: `v2-phase3.md`.

At this point the entire legacy **`ordertracking`** demo (both phases) is ported and reconciled. The only
remaining legacy demo is the **thermostat-fleet** family (`examples/…/thermostatfleet`, driven by
`ThermostatFleetBridge`) — a set of scenarios of increasing complexity that culminate in a 4-table,
multi-region capstone.

## Planned — to parity, then retirement

Ordering is smallest-leap-first, and every phase keeps a **clean legacy reconcile**. The thermostat domain
(a telemetry behavior + workload) leads, because the remaining legacy demos — including the multi-table one
— are all *thermostat* scenarios; porting the single-table thermostat demo first (no engine changes) gives
every later phase a legacy scenario to reconcile against. Feature-depth phases (6–7) are largely
independent and the capstone (8) integrates them, so their relative order can shift by priority.

- **v2/phase4 — Thermostat single-table demo (single-region).** Port the single-region
  `ThermostatFleetScenarioConfig`: **one on-demand `device-telemetry` table + 2 GSIs + the thermostat
  telemetry behavior / workload**, on the existing indexed table (Query/Scan + GSIs). **No engine
  changes** — a new demo *domain* only. Proves the single-region thermostat scenario, and introduces the
  thermostat behavior/workload that every later phase reuses.
- **v2/phase5 — Multi-table composition.** Compose several v2 `DynamoDbTable`s (the now-available thermostat
  tables) into one simulation; per-table + overall reporting (the legacy `Table:<name>:…` metric names).
  Proves `MultiTableScenarioConfig`. Cashes in the "table is the composable graph-level unit" design and
  erects the capstone's skeleton.
- **v2/phase6 — Provisioned capacity + throttling + auto-scaling.** Provisioned billing (capacity-hour
  cost), **throttling** (requests over per-tick capacity are rejected), then auto-scaling (capacity tracks
  utilization). **Central decision:** throttling via an `Interface.wrap` gate vs. internal admission — the
  fork deferred since phase-2; the gate machinery was built for exactly this.
- **v2/phase7 — TTL + transactions.** Item **TTL** expiry (frees storage over ticks) and **transactions**
  (`TransactWriteItems` / `TransactGetItems`, 2× capacity, atomic multi-item) — two mostly-independent
  single-table capability slices. Proves the Telemetry (TTL) and Commands (transaction) patterns.
- **v2/phase8 — Thermostat-fleet capstone (single-region).** Assemble the full **4-table fleet** (Registry
  on-demand+GSIs, Telemetry provisioned+auto-scaling+TTL, Commands transactions, Alerts spike) with a
  **time-varying "polar-vortex" workload** (tick-varying rates are already expressible with the core
  samplers — config, not engine). Proves `ThermostatFleetCapstoneConfig` (single-region) — the integration
  proof.
- **v2/phase9 — Multi-region / global tables.** Cross-region **replication** (global tables →
  `ReplicatedWriteCapacityConsumed`), cross-region **transfer** bytes/cost, per-region metrics. Proves the
  multi-region thermostat scenarios.
- **v2/phase10 — Grafana delivery + legacy retirement.** Port the `generate → stage → view` Postgres/Grafana
  pipeline to the v2 demos (likely a separate `aws-grafana` bridge module — the `aws` module is
  deliberately JDBC-free); then **delete the legacy `stochastacy.aws` code and the legacy `examples`
  demos** once parity is confirmed everywhere.

**Reorder notes.** The polar-vortex spike is not its own phase (workload config). Grafana delivery
(phase 10) is orthogonal to the simulation features and can be pulled forward as a standalone bridge phase
whenever visualization is wanted. Legacy retirement is the finish line — only after every legacy demo has a
reconciled v2 counterpart.

## Modules at parity

- `core/` — the v2 engine (`stochastacy.core`) + the frozen legacy `stochastacy.aws` / `stochastacy.workload`
  (deleted at phase 9).
- `aws/` — the v2 AWS line (`stochastacy.aws.dynamodb` + `stochastacy.aws.examples.*`); grows each phase.
- `examples/` — the store demos (v2) + the frozen legacy `ordertracking` / `thermostatfleet` (deleted at
  phase 9).
- (phase 9) a new `aws-grafana` bridge module, if the Grafana pipeline is ported.
