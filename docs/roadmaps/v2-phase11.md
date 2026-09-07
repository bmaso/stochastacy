# v2/phase11 — Multi-region / global tables

**Status: PLANNED** — five slices (+ a close-out coda). The **last simulation feature** before legacy retirement
(phase 12 = Grafana delivery + delete the legacy code). Cross-region **write replication** on the v2 core:
replicated-write capacity (**rWCU**) billing + throttling, **cross-region transfer** bytes/cost, and **per-region
+ per-link** metrics — proven by a bespoke thermostat-flavored multi-region **hot-replica demo** and reconciled
against the legacy `DynamoDbGlobalTable` multi-region demo.

Follows `v2/phase10` (single-region throughput parity reached). Deliberately **meatier slices** than phases 9/10.

**A first-class core change opens the phase.** Replication is a cyclic dataflow (region → coordinator → region),
and the `ScheduleReleaseTransducer`'s tick barrier deadlocks any external feedback loop through a component. Rather
than work around it in AWS code, phase 11 **generalizes the core** so a component can consume a *delayed copy of
its own effects* — a **feedback/tap loopback** — the domain-agnostic shape for replication (and gossip, retry,
anti-entropy…). This is a deliberate `stochastacy.core` change (Brian-approved), isolated and de-risked as Slice 1
before any AWS work depends on it.

## Goal

Model a DynamoDB **Global Table** as N regional `DynamoDbTable`s that replicate each other's writes. A write in
region A is applied locally (WCU), then propagated to every other region after a per-link lag, where it is applied
as a **replicated write** billed at **rWCU** and carries **cross-region transfer** bytes. Each region provisions
its own rWCU; when inbound replication outruns a region's rWCU, replication **backs up** — the AWS-accurate
behavior that makes `ReplicationLatency` and `PendingReplicationCount` the true indicators of rWCU depletion.

## Design decisions (confirmed)

- **D-loopback-core (a deliberate core change — supersedes the original "no core change" plan).** The first
  design ("tap admitted writes on the consumption plane, re-merge replicated writes into the table's inlet") was
  found to **deadlock**: the `MergeTimedEventGraph` imposes a both-inputs *tick barrier*, and the tap sits
  downstream of the re-merge, so the `Tick(T)` marker must round-trip the region→coordinator→region cycle within
  tick `T` — which the barrier forbids. The legacy avoided this with a dedicated replicated-input **port on the
  table** (tapping admitted writes *upstream* of its storage re-merge). Rather than reproduce that per-component in
  AWS, phase 11 **generalizes the core**: the `ScheduleReleaseTransducer` gains a **feedback inlet + tap outlet**
  (and `ComponentSampler` an `onFeedback` handler + a `Tap` emission channel), with the **tap tick-forwarded
  eagerly** so a component can be wired into a delayed self-loop without deadlock. `Fb = Nothing` / `Tap = Nothing`
  leaves every existing component unchanged. This makes replication a first-class core capability. (Rejected
  alternatives: a bespoke AWS replicated-table stage — robust but not reusable across clouds; a fused
  multi-region mega-stage — no cycle, but re-implements per-region timing at a reconcile-fidelity risk.)
- **D-rwcu-billing.** A replicated write is **tagged** (a request wrapper / marker) so the destination bills
  `ReplicatedWriteCapacityConsumed` (rWCU), not WCU. rWCU is consumed **only by inbound replication** (local
  writes = WCU).
- **D-rwcu-gating-in-coordinator.** The **coordinator** owns each region's rWCU budget and backlog (not the
  table) — cleaner for the backlog model; the table just bills admitted replicated writes. rWCU ceiling is
  `BillingMode.Provisioned.replicatedWriteCapacityUnits` (as in the legacy).
- **D-metrics-coupled-to-depletion (a v2 correctness improvement).** `ReplicationLatency` and
  `PendingReplicationCount` are the real AWS CloudWatch Global-Tables metrics and the true depletion signal. The
  **legacy decouples them** — its queues model link lag only, and an rWCU throttle just drops the write. v2 models
  a **backlog**: a replicated write that can't be admitted stays queued and retries, so pending count grows and
  latency = link lag + backlog wait, both rising under depletion and draining on recovery.
- **D-per-source-streams (fair-share).** AWS reports both metrics **per source region**, so each destination holds
  **one pending queue per (src→dst) stream**, all drawing on that destination's single rWCU budget, split
  **fair-share per source stream**. This is what makes the per-link distinction real and growing under depletion.
- **D-demo-thermostat-flavored.** The legacy multi-region demo is thermostat-based (`ThermostatFleetBridge --mode
  multi-region`, `multiRegionDefault`), so a **focused** thermostat-flavored config (one telemetry table per
  region, 3 regions — not the capstone) reconciles **directly** (the phase-4/5/9 pattern), stronger than a
  bespoke-from-scratch demo.
- **D-reconcile-hybrid.** The reconcile arm pins per-region means against the legacy `multiRegionDefault`; the
  rWCU-depletion coupling (the depletion arm) is a **documented v2 improvement**, not reconcilable (the legacy
  can't reproduce it). Legacy is unreferenceable from this module + hashes differently — baseline captured & pinned.

## The demo (confirmed scenario shape)

A bespoke **hot-replica** demo (`stochastacy.aws.examples.hotreplica`), thermostat-flavored, **two arms**:

- **(A) Reconcile arm** — on-demand, **legacy fleets** (us-east-1 1800 / eu-west-1 900 / ap-southeast-1 300).
  Direct per-region legacy pin; healthy (`PendingReplicationCount ≈ 0`, `ReplicationLatency ≈ link-lag mean`).
- **(B) Depletion arm** (v2 showcase, not reconciled) — an **8:1 fleet discrepancy** (us-east-1 **2000** /
  eu-west-1 **250** / ap-southeast-1 300), so ap-southeast's inbound ≈ 2250 is dominated 8:1 by us-east.
  ap-southeast is provisioned with an rWCU ceiling **below** its inbound, so **both** inbound links back up with a
  **per-link distinction**: `PendingReplicationCount(us-east→ap-southeast) ≈ 8×` the eu-west link (volume), and
  higher `ReplicationLatency` (the fair-share split gives the heavy us-east stream a deep, slow-draining queue;
  reinforced by a modestly longer us-east→ap-southeast base link-lag). us-east / eu-west stay healthy by contrast.

**Metrics surfaced** (per-tick + summary): per-region RCU / WCU / **rWCU** / storage / cost (total / write / rWCU /
transfer); cross-region transfer per-link bytes + per-region/total bytes/cost; per-link **ReplicationLatency**
(mean + max/p95, so the depletion tail shows) and **PendingReplicationCount**.

## Slice status

| # | slice | status | proof (target) |
|---|---|---|---|
| 1 | Core loopback transducer + de-risking prototype | **Done** | `ScheduleReleaseTransducer` gains a feedback inlet + tap outlet (eager tick-forward), `ComponentSampler` an `onFeedback` + `Tap` channel; a toy loopback proves "effect in A reappears in B one tick later, no deadlock"; `Nothing`/`Nothing` byte-identical (full `sbt test`) |
| 2 | Multi-region composition + replication + rWCU billing | **Done** | on the loopback: a region-A write applies rWCU-billed in region B after link lag; per-source-stream queues; transfer bytes; `ReplicationLatency`/`PendingReplicationCount` (in-flight count, window-close sampled); single-region byte-identical |
| 3 | rWCU throttling + depletion backlog + metric coupling | **Done** | `replicatedWriteCapacityUnits` ceiling + fair-share-per-source drain; under depletion the backlog grows, per-source latency/pending diverge, both drain on recovery; unlimited rWCU = Slice-2 behavior |
| 4 | Hot-replica demo | **Done** | bespoke thermostat-flavored 3-region demo + `@main`; two arms (reconcile / 8:1 depletion) with per-region + per-link metrics, JSONL + console; the per-link distinction |
| 5 | Hybrid reconcile + docs + close-out | Planned | reconcile arm direct per-region pin vs legacy `multiRegionDefault`; catalog + README; close-out coda |

## Slices

### Slice 1 — Core loopback transducer + de-risking prototype
Generalize `stochastacy.core` so a component can consume a **delayed copy of its own effects** — a feedback/tap
loopback — and prove it deadlock-free with a toy prototype **before any AWS code depends on it**. Pure core; no
AWS or replication logic. `Emission` gains a `Scheduled[Tap]` channel; `ComponentSampler[S,In,Out,Cons]`
generalizes with `Fb`/`Tap` type params + a defaulted `onFeedback(fb, state, rng)` (`Nothing`/`Nothing`/no-op =
today's component); `ScheduleReleaseTransducer` grows to two inlets (`in`, `fbIn`) + three outlets (`fwd`, `cons`,
`tap`), with `in` the sole tick clock and **`tap` forwarding `Tick(t)` eagerly** (the invariant that breaks the
loop) and `fbIn` dispatched to `onFeedback`.

**Validated by:** core unit tests — a toy loopback (toy sampler + trivial coordinator) proves an effect emitted in
"A" reappears in "B" one tick later with no deadlock, deterministically, and ends cleanly at `EndOfTime`; residue
semantics hold on all three outlets; a `Nothing`/`Nothing` component is **byte-identical** to the pre-change
transducer. Gate on the **full `sbt test`** (a core/shared contract change).

**Delivered.** Unified types (zero churn to existing samplers): `LoopbackEmission[S,Out,Cons,Tap]` with
`Emission[S,Out,Cons] = LoopbackEmission[…,Nothing]` (a type alias + an `object Emission` with the familiar 3-arg
`apply`/`unapply`, so every existing `Emission(a,b,c)` and the transducer's destructuring compile unchanged);
`LoopbackComponentSampler[S,In,Fb,Out,Cons,Tap]` (base) with `ComponentSampler[S,In,Out,Cons]` its
`Fb`/`Tap`-pinned-`Nothing` subtype; `onFeedback` reuses `TickEmission` (no separate feedback-emission type). New
`LoopbackShape` (2 inlets `in`/`fbIn`, 3 outlets `fwd`/`cons`/`tap`) + `ScheduleReleaseTransducer.loopbackComponentOf`
+ a `LoopbackStage`: `in` is the sole tick clock, `tapOut` forwards `Tick(t)` **eagerly**, a window closes only once
both `in` and `fbIn` reach `Tick(t)`. The existing plain `Stage`/`componentOf` is **untouched** (byte-identical;
the ~40-line timing-logic overlap with the loopback stage is a noted future dedup). Proven by `LoopbackTransducerSpec`
(the cyclic prototype terminates — no deadlock — feeds every effect back, deterministic). Full `sbt test` green.
The option-1 AWS edits were reverted (parked in the scratchpad) so this slice is **core-only**; Slice 2 rebuilds the
AWS side on `Tap`/`onFeedback`.

### Slice 2 — Multi-region composition + replication + rWCU billing
On the loopback core: the `DynamoDbTable` sampler emits a **`Tap`** (the write to replicate) per admitted local
write and applies inbound replicated writes via **`onFeedback`**, billing **rWCU** (`ReplicatedWriteCapacityConsumed`,
base + index); loop-prevention is structural (feedback emits no tap). The `ReplicationCoordinator` holds
**per-source-stream queues** and re-injects each write into the other regions after a per-link lag
(`ReplicationModel`, min 1 tick), emitting `CrossRegionTransferEvent` + `ReplicationLatency` +
`PendingReplicationCount` per `(src→dst)` link in **link-lag form** (rWCU ungated: a cost dimension only). A
`GlobalTable` composition wires N regional tables + the coordinator through the core loopback — no custom merges,
no cycle deadlock.

**Validated by:** unit tests — the coordinator in isolation; a region-A write applies rWCU-billed in region B after
the sampled link lag; transfer bytes per link; per-source-stream queue depth; determinism; a single region (no
peers) byte-identical to a plain `DynamoDbTable`.

**Delivered.** `DynamoDbTable` is now a `LoopbackComponentSampler[TableState, DynamoDbRequest, ReplicationWrite,
DynamoDbResponse, DynamoDbConsumption, ReplicationWrite]`: each admitted Put/Update/Delete emits one
`ReplicationWrite` **tap** (`tapFor`); reads and throttled writes tap nothing; `onFeedback` re-applies an inbound
write through the behavior/mechanics (base + index maintenance + TTL) and **relabels WCU→rWCU**
(`ReplicatedWriteCapacityConsumed`, `asReplicated`), never re-tapping. `componentOf` ties `fbIn ← Source.empty`
(the Slice-1 `fbDone` gate fires on `in` alone → single-region byte-identical); `replicatedComponentOf` exposes the
full `LoopbackShape`. `ReplicationCoordinator.flow` holds one queue **per (src→dst) link**, releases a write at
`enqueueTick + max(1, ⌊lag⌋)` (`ReplicationModel`), and emits `CrossRegionTransferEvent` + `ReplicationLatencySample`
at release; **`PendingReplicationSample` is an in-flight count sampled at window close** — a write authored at tick
`t` (its tap arrives *after* `Tick(t)`) and applied at `t+lag` is counted pending over `t … t+lag-1` (a start-of-tick
sample would miss a 1-tick-lag write entirely). `GlobalTable.componentOf` wires N regional loopback tables through a
`MergeTimedEventGraph` chain → coordinator → `Broadcast`, routing `ReplicatedWriteFor(r)` to region `r`'s `fbIn` and
the rest out on `metricsOut` — a cyclic graph proven **deadlock-free** (the Slice-1 eager tap-tick forward). rWCU is
ungated (cost only); throttle/backlog/coupling are Slice 3. Full `sbt test` green (Task 0 touched core).

### Slice 3 — rWCU throttling + depletion backlog + metric coupling
Add `BillingMode.Provisioned.replicatedWriteCapacityUnits` (per-region rWCU ceiling) and drain each destination's
per-source-stream queues at that budget, **split fair-share per source stream**. A replicated write that can't be
admitted stays queued and retries, so under depletion `PendingReplicationCount` grows and `ReplicationLatency` =
link lag + backlog wait; both drain when rWCU is restored. The heavy source stream develops the deeper, slower
queue (fair-share). Unlimited rWCU (on-demand / no ceiling) → exactly Slice-2 behavior.

**Validated by:** unit tests — under a ceiling below inbound, the backlog grows and per-source latency/pending
**diverge** (heavy stream worse), then drain on recovery; fair-share split; the ceiling is never exceeded;
unlimited-rWCU byte-identical to Slice 2.

**Delivered.** `BillingMode.Provisioned` gains `replicatedWriteCapacityUnits: Option[Long] = None` — the per-region
**inbound rWCU throttle ceiling** (`None` ⇒ unlimited; every existing provisioned table byte-identical). The ceiling
is enforced entirely in `ReplicationCoordinator` (the table's `onFeedback` is untouched): `flow` takes a
`Map[dest, Option[Long]]` (derived by `GlobalTable` from each region's billing mode), groups the source streams
`byDest`, and drains each destination per tick — **no ceiling** ⇒ every eligible write releases (Slice-2 path);
**a ceiling `b`** ⇒ a **work-conserving fair-share round-robin** admits one eligible head per source stream per pass
(per-stream FIFO preserved) until the `b`-rWCU budget is spent or no head fits, so an unused share redistributes.
The ceiling governs **base-table** rWCU only — `ThroughputMath.writeCapacityUnits(bytes)`, equal to the base rWCU the
destination bills; **GSI rWCU rides outside it** (a replica's GSIs carry their own replicated capacity, as in AWS,
per Brian's D6 call). `ReplicationLatency` is now the **measured** `releaseTick − enqueueTick` (link lag with no
backlog; link lag + backlog wait under depletion) — Slice-2's constant sampled lag was replaced (equal when the link
keeps up, so the unlimited path stays byte-identical). Under an 8:1 two-source overload the heavy stream builds the
deeper, slower queue and its pending/latency diverge above the light stream's, both draining on recovery; the ceiling
is never exceeded. Coordinator-driven `RwcuThrottlingSpec` (4 cases) + the Slice-2 specs unchanged (byte-identity
guard). Full `sbt test` green (shared-contract change to `BillingMode`).

### Slice 4 — Hot-replica demo
`HotReplica{Config,Behavior,Workload}` + a standalone multi-region trial runner + `HotReplicaMonteCarloRunner` +
`@main HotReplicaDemo`, running the two arms with per-region + per-link metrics, per-tick streaming JSONL, and a
console summary.

**Validated by:** `HotReplicaSpec` — the depletion arm shows the per-link distinction (us-east→ap-southeast pending
≈ 8× and latency > eu-west→ap-southeast, rising then draining); the reconcile arm stays healthy; determinism. Plus
a demo smoke-run.

**Delivered.** A bespoke `stochastacy.aws.examples.hotreplica` package (`@main HotReplicaDemo`), **reusing the
thermostat telemetry table** per region so arm A stays reconcilable: `RegionConfig` wraps a `ThermostatConfig`
(fleet size + billing mode) and `HotReplicaConfig` assembles three into a `GlobalTable.Config` with a per-link
`ReplicationModel`. `HotReplicaTrialRunner` drives the coupled cyclic `GlobalTable` (each region's framed workload →
`requestIn`; per-region consumption folded by `RegionAccountingState`; the single `metricsOut` by
`ReplicationMetricsState`), materialized with a 3-region + metrics `createGraph`. **rWCU priced by billing mode
exactly as WCU** (D7): consumed under on-demand, reserved capacity-hours under provisioned (rWCU reservation
included); cross-region transfer priced per **source** region ($/GiB). `HotReplicaMonteCarloRunner` folds trials
into cross-trial means incrementally (`MonteCarlo.stream` + `Sink.fold`, bounded memory), keeping the first trial's
per-tick link series for the streaming JSONL. **No system-error `ChaosGate`** inside the Global Table graph (the
legacy's ~0.1 % is within reconcile tolerance). Two shipped arms: `reconcileDefault` (on-demand 1800/900/300, all
links latency ≈ link-lag, pending bounded) and `depletionDefault` (8:1 2000/250/300; `ap-southeast-1` provisioned
with a **12-rWCU inbound ceiling** below its ~74/tick combined inbound + a longer us-east→ap-southeast link). A
representative run shows both inbound links into ap-southeast backing up with a clear per-link distinction —
us-east→ap-southeast pending ≈ **12×** and latency ≈ 2.5× the eu-west stream, while every other link stays healthy.
`HotReplicaSpec` (4 cases: divergence / healthy arm / determinism / smoke-run). Note: this continuously-loaded demo
never drains a backlog — rise-then-drain is unit-tested in `RwcuThrottlingSpec`. Full `sbt test` green.

### Slice 5 — Hybrid reconcile + docs + close-out
`HotReplicaReconciliationSpec`: direct per-region pin of the reconcile arm against a captured legacy
`multiRegionDefault` baseline (phase-4/5/9 style); the depletion coupling documented as a v2 improvement.
`specs/aws-component-catalog.md` (multi-region / replication / rWCU / the two metrics) + `specs/README.hot-replica.md`
+ a CLAUDE.md demo entry. Close-out coda: roadmap COMPLETE, program roadmap, memory, full `sbt test`.

## Scope boundary

Multi-region write replication with rWCU + cross-region transfer + the two replication metrics; per-region
provisioned rWCU. Not in scope: replica auto-scaling of rWCU, global-table add/remove-region dynamics, strongly
consistent cross-region reads. After this phase, **phase 12** ports the Grafana pipeline and deletes the legacy code.
