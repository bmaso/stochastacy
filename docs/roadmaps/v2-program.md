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
- **v2/phase4** — **Thermostat single-table demo (single-region)**: the thermostat domain begins on the v2
  core — one on-demand `device-telemetry` table + **3 GSIs + 1 LSI (mixed KeysOnly/Include/All projections)**
  + the fleet-growth, temporally-shaped telemetry behavior/workload; a **system-error `ChaosGate`** (first
  `Interface.wrap` on an AWS table); a **clean equivalence** reconcile with legacy (~2% on every dimension).
  Plus an impromptu harness slice (**6c**) making the demo output **streaming / bounded-memory**. Roadmap:
  `v2-phase4.md`.
- **v2/phase5** — **Multi-table composition**: compose several independent thermostat `DynamoDbTable`s into
  one simulation with **per-table** (`Table:<name>:…`) reporting, reconciling the legacy `twoTableDefault`
  (device-registry + device-telemetry) as a **clean per-table equivalence** (~2%). **Generalized the demo
  harness** — a per-table `TableSpec` + `MultiTable{Scenario,Trial,MonteCarlo}Runner` reuse the single-table
  accounting / aggregation / streaming primitives (single-table byte-identical). Roadmap: `v2-phase5.md`.
- **v2/phase6** — **Provisioned capacity + throttling**: the first non-on-demand billing mode
  (`BillingMode` as intrinsic config, priced by capacity-hours), **internal per-target throttling** (a
  reusable weighted per-tick budget in `TableState` — capacity-unit throttling is intrinsic, not a gate), and
  **scheduled reconfiguration** (`ReconfigurationSchedule` applied at tick boundaries). Reconciles the legacy
  `mixed-mode` demo — **clean on the simulation** (~1 %) with the mixed **cost a documented divergence** (v2's
  clean per-tick billing attribution vs the legacy's inconsistent mixed-cost accounting; provisioned pricing
  reserves base + explicitly-provisioned GSIs). Auto-scaling deferred to phase 8. Roadmap: `v2-phase6.md`.

At this point the entire legacy **`ordertracking`** demo (both phases) and the single-region + multi-table +
mixed-mode **thermostat** demos are ported and reconciled. The remaining legacy demos are the rest of the
**thermostat-fleet** family (`examples/…/thermostatfleet`, driven by `ThermostatFleetBridge`) — feature-depth
single-table capabilities (TTL / transactions, then burst + auto-scaling, then hot-partition + adaptive) and a
4-table single-region capstone, before multi-region — all reusing the now-available thermostat domain,
multi-table harness, and provisioned/throttling machinery.

## Planned — to parity, then retirement

Ordering is smallest-leap-first, and every phase keeps a **clean legacy reconcile**. The thermostat domain
(a telemetry behavior + workload) led (phase 4), multi-table composition followed (phase 5), and provisioned
capacity + throttling landed (phase 6); the remaining legacy demos are all *thermostat* scenarios. Feature-depth
phase 7 is largely independent and the capstone (9) integrates everything, so their relative order can shift
by priority.

- **v2/phase7 — TTL + transactions (with a core enhancement: tick-boundary emission) — DONE.** Opened with a
  small, principled **core engine change**: a `ComponentSampler`'s `onTick` gained the ability to **emit
  consumption facts at tick boundaries** — `onTick(tick, state): TickEmission[S, Cons]` (new state plus
  scheduled *metric-plane* facts; **never** forward outputs, so 1:1 request/response is preserved by
  construction). The `ScheduleReleaseTransducer` stamps these at the boundary time `(t, 0)`, buffers them,
  and releases them in time order like any tick-`t` output, so all timed-event invariants (tick framing,
  single `Tick` per window, `EndOfTime` last, intra-tick monotonicity) still hold. This is the **first
  deliberate v2 core change since the engine redefinition** — taken because the old `onTick: S` contract
  *blocked* (not supported) TTL, whose storage expiry is inherently a tick-boundary effect; the
  domain-agnostic contract is otherwise respected. On that foundation, two mostly-independent single-table
  capability slices: **TTL** — item expiry frees base-table storage over ticks (`ttlPeriodTicks`), modeled
  as a behavior-supplied tick-boundary expiry emitting a negative `StorageBytesDelta` that the existing
  accounting integrates unchanged (proves the Telemetry pattern); and **transactions** — `TransactWriteItems`
  / `TransactGetItems`, a new request type carrying multiple item bytes charged **2× capacity** and applied
  atomically, processed in `sample()` like any operation (proves the Commands pattern). Reconcile note: the
  legacy sets TTL and transactions only in the **capstone** (phase 9), so phase 7 validates these with
  focused single-region demos + unit tests, deferring the full multi-table reconcile to the capstone.
  **Delivered:** `onTick` needs **no `rng`** (TTL is deterministic — a ring buffer); TTL frees **base + GSI +
  LSI** storage (not just base-table), as generic table mechanics rather than a behavior hook; the transaction
  multiplier is the **AWS-accurate target-dependent** rule (base + synchronous LSI **2×**, async GSI back-fill
  **1×**) — researched against AWS billing, deliberately diverging from the legacy's uniform 1× on indexes;
  and the two capabilities were proven by **bespoke session-store + payments-ledger demos** (the thermostat is
  a device registry / mixed-mode — neither fits an accumulate-then-expire or atomic-transfer story).
  Roadmap: `v2-phase7.md`.
- **v2/phase8 — Provisioned throughput dynamics: burst capacity + reactive auto-scaling — DONE.** Two coupled
  **temporal** capacity mechanisms on the phase-6 provisioned/throttling foundation. **Burst capacity**
  extends `ThrottleBudget` from a hard per-tick ceiling into a **carry-forward accumulator** — unused capacity
  banks up to a cap (~300 s worth), so short spikes are absorbed before throttling; it is the spike-smoother
  that sits *underneath* auto-scaling (a spike is drained from banked burst first, and only *sustained* load
  drives a scale-up). **Reactive auto-scaling** (deferred from phase 6) is a rolling-utilization control loop
  driving `UpdateProvisionedCapacity` with reaction-lag and scale-up-fast / scale-down-slow. Its **input**
  signal — the per-tick admitted/consumed capacity — is already accumulated in `TableState` (the phase-6
  `ThrottleBudget`, readable in `onTick` before reset); its **output** — the dynamic per-tick capacity it
  chooses — must reach the accounting at runtime, since the static `billingModeAt` schedule fold no longer
  describes the capacity in force (open design point: likely **emitted as a tick-boundary fact via the phase-7
  `TickEmission`**, the second use of that core change). Validated by unit tests + a focused single-region
  telemetry demo (spike → burst absorbs → sustained load → scale-up, with throttling where the reaction-lag
  bites); full legacy reconcile **deferred to the capstone** (the phase-7 pattern). **Delivered:** burst as a
  `ThrottleBudget` carry-forward bank (`burstWindowTicks`, capped at `ceiling × window`); auto-scaling as a
  pure `onTick` port of the legacy `DynamoDbAutoScaler` control loop (target-tracking, reaction lag, asymmetric
  cooldowns, base target only) via an `AutoScalingPolicy`; dynamic capacity → accounting through a
  per-tick `ProvisionedCapacitySnapshot` (the `TickEmission`'s second use); proven by the
  `thermostat-fleet-autoscaling` demo (~57 k fewer throttles than a fixed reservation, at higher cost). Roadmap:
  `v2-phase8.md`.
- **v2/phase9 — Thermostat-fleet capstone (single-region) — DONE.** Assemble the full **4-table fleet** — Registry
  (on-demand + GSIs), Telemetry (provisioned + burst + auto-scaling + TTL + **PITR**), Commands (transactions),
  Alerts (spike) — under a **time-varying "polar-vortex" workload** (5× writes on 40 % of a **fixed** 50 k-device
  fleet, ticks 600–700; tick-varying rates are config, not engine — so auto-scaling here is **vortex-/storm-driven,
  not growth-driven**). Opens with one **new mechanism**: **PITR** (Point-In-Time Recovery) — the legacy telemetry
  table enables it, and it bills continuous-backup storage that v2 does not yet model; a contained storage-cost
  dimension (byte-ticks × PITR rate), the last of the missing legacy features. The **integration proof**, and
  where the **two** deferred phase-7 reconciles land: **Telemetry** (TTL + auto-scaling + burst + PITR) *and*
  **Commands** (transactions — its first legacy comparison; the payments demo was bespoke) are the new reconcile
  targets against `ThermostatFleetCapstoneConfig`; Registry and Alerts compose already-reconciled features. The
  auto-scaling trajectory match (v2's pure `onTick` port vs. the legacy actor) is the phase's key reconcile risk.
  Telemetry sets `burstWindowTicks = 300` to match the legacy's always-on provisioned burst. **Delivered:** PITR
  as a continuous-backup cost dimension; a transactional command path in the thermostat domain; the 4-table
  `capstoneDefault` with per-table provisioned/PITR/GSI surfacing; and the per-table reconcile — **RCU tight ~2 %**,
  on-demand tables ~8 %, with documented bounded v2 improvements (transaction LSI 2×, TTL freeing base+GSI+LSI,
  provisioned billing by reservation). v2 ran ~7× faster than the legacy; both capstone defaults were reduced
  50 k → 5 k, and a latent legacy rollup bug (unregistered PITR metric) was fixed. Roadmap: `v2-phase9.md`.
- **v2/phase10 — Hot-partition throttling + adaptive capacity.** The **spatial** capacity dimension — how a
  table's provisioned capacity distributes across partitions — a coupled pair orthogonal to phase-8's temporal
  auto-scaler. **Hot-partition throttling**: capacity is split across partitions, so load concentrated on one
  partition throttles even while the table has aggregate spare, modeled as a **stochastic summary of
  per-partition load skew** (a distribution, not per-key maps). **Adaptive capacity**: the mitigation —
  DynamoDB isolates and boosts a hot partition, relieving the throttle after a lag. Validated by a focused
  **hot-key scenario** + unit tests; its legacy reconcile target is whichever legacy demo exercises hot
  partitions (**to confirm at planning**: if the capstone's Telemetry table itself hot-partitions, this phase
  moves *ahead* of the capstone, → phase 9).
- **v2/phase11 — Multi-region / global tables.** Cross-region **replication** (global tables →
  `ReplicatedWriteCapacityConsumed`), cross-region **transfer** bytes/cost, per-region metrics. Proves the
  multi-region thermostat scenarios.
- **v2/phase12 — Grafana delivery + legacy retirement.** Port the `generate → stage → view` Postgres/Grafana
  pipeline to the v2 demos (likely a separate `aws-grafana` bridge module — the `aws` module is
  deliberately JDBC-free); then **delete the legacy `stochastacy.aws` code and the legacy `examples`
  demos** once parity is confirmed everywhere.

**Reorder notes.** The polar-vortex spike is not its own phase (workload config). Grafana delivery
(phase 12) is orthogonal to the simulation features and can be pulled forward as a standalone bridge phase
whenever visualization is wanted. Burst pairs with auto-scaling (phase 8) because it is what makes the
auto-scaler's spike→lag→throttle story faithful; hot-partition + adaptive (phase 10) are a separable spatial
pair that can shift *before* the capstone if the capstone's Telemetry table proves to hot-partition. Legacy
retirement is the finish line — only after every legacy demo has a reconciled v2 counterpart.

## Modules at parity

- `core/` — the v2 engine (`stochastacy.core`) + the frozen legacy `stochastacy.aws` / `stochastacy.workload`
  (deleted at phase 12).
- `aws/` — the v2 AWS line (`stochastacy.aws.dynamodb` + `stochastacy.aws.examples.*`); grows each phase.
- `examples/` — the store demos (v2) + the frozen legacy `ordertracking` / `thermostatfleet` (deleted at
  phase 12).
- (phase 12) a new `aws-grafana` bridge module, if the Grafana pipeline is ported.
