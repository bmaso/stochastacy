# v2/phase11 — Multi-region / global tables

**Status: PLANNED** — four slices (+ a close-out coda). The **last simulation feature** before legacy retirement
(phase 12 = Grafana delivery + delete the legacy code). Cross-region **write replication** on the v2 core:
replicated-write capacity (**rWCU**) billing + throttling, **cross-region transfer** bytes/cost, and **per-region
+ per-link** metrics — proven by a bespoke thermostat-flavored multi-region **hot-replica demo** and reconciled
against the legacy `DynamoDbGlobalTable` multi-region demo.

Follows `v2/phase10` (single-region throughput parity reached). Deliberately **meatier slices** than phases 9/10.

## Goal

Model a DynamoDB **Global Table** as N regional `DynamoDbTable`s that replicate each other's writes. A write in
region A is applied locally (WCU), then propagated to every other region after a per-link lag, where it is applied
as a **replicated write** billed at **rWCU** and carries **cross-region transfer** bytes. Each region provisions
its own rWCU; when inbound replication outruns a region's rWCU, replication **backs up** — the AWS-accurate
behavior that makes `ReplicationLatency` and `PendingReplicationCount` the true indicators of rWCU depletion.

## Design decisions (confirmed)

- **D-replication-tap (no core change).** Replication is driven by **tapping each region's admitted-write stream**
  with a `ReplicationCoordinator` stage that re-injects lagged replicated writes into the other regions' inlets;
  the v2 `DynamoDbTable` is unchanged. (Rejected: a replicated-table variant with a dedicated replication-output
  plane — a core/protocol change.)
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
| 1 | Multi-region composition + replication + rWCU billing | Planned | a region-A write applies rWCU-billed in region B after link lag; per-source-stream queues; transfer bytes; `ReplicationLatency`/`PendingReplicationCount` in link-lag form; single-region byte-identical |
| 2 | rWCU throttling + depletion backlog + metric coupling | Planned | `replicatedWriteCapacityUnits` ceiling + fair-share-per-source drain; under depletion the backlog grows, per-source latency/pending diverge, both drain on recovery; unlimited rWCU = Slice-1 behavior |
| 3 | Hot-replica demo | Planned | bespoke thermostat-flavored 3-region demo + `@main`; two arms (reconcile / 8:1 depletion) with per-region + per-link metrics, JSONL + console; the per-link distinction |
| 4 | Hybrid reconcile + docs + close-out | Planned | reconcile arm direct per-region pin vs legacy `multiRegionDefault`; catalog + README; close-out coda |

## Slices

### Slice 1 — Multi-region composition + replication + rWCU billing
Compose N regional `DynamoDbTable`s into a Global Table; the `ReplicationCoordinator` taps each region's
admitted-write plane, holds **per-source-stream queues**, and re-injects each write into the other regions after a
per-link lag (`ReplicationModel`), tagged so the destination bills **rWCU** (`ReplicatedWriteCapacityConsumed`).
Emits `CrossRegionTransferEvent` + `ReplicationLatency` + `PendingReplicationCount` per `(src→dst)` link — in their
**link-lag form** (rWCU ungated: a cost dimension only, no ceiling). Per-region consumption/metric streams out.

**Validated by:** unit tests — a region-A write appears as an rWCU-billed write in region B after the sampled link
lag; transfer bytes per link; per-source-stream queue depth = writes awaiting their lag; determinism; a single
region (no peers) is byte-identical to a plain `DynamoDbTable`.

### Slice 2 — rWCU throttling + depletion backlog + metric coupling
Add `BillingMode.Provisioned.replicatedWriteCapacityUnits` (per-region rWCU ceiling) and drain each destination's
per-source-stream queues at that budget, **split fair-share per source stream**. A replicated write that can't be
admitted stays queued and retries, so under depletion `PendingReplicationCount` grows and `ReplicationLatency` =
link lag + backlog wait; both drain when rWCU is restored. The heavy source stream develops the deeper, slower
queue (fair-share). Unlimited rWCU (on-demand / no ceiling) → exactly Slice-1 behavior.

**Validated by:** unit tests — under a ceiling below inbound, the backlog grows and per-source latency/pending
**diverge** (heavy stream worse), then drain on recovery; fair-share split; the ceiling is never exceeded;
unlimited-rWCU byte-identical to Slice 1.

### Slice 3 — Hot-replica demo
`HotReplica{Config,Behavior,Workload}` + a standalone multi-region trial runner + `HotReplicaMonteCarloRunner` +
`@main HotReplicaDemo`, running the two arms with per-region + per-link metrics, per-tick streaming JSONL, and a
console summary.

**Validated by:** `HotReplicaSpec` — the depletion arm shows the per-link distinction (us-east→ap-southeast pending
≈ 8× and latency > eu-west→ap-southeast, rising then draining); the reconcile arm stays healthy; determinism. Plus
a demo smoke-run.

### Slice 4 — Hybrid reconcile + docs + close-out
`HotReplicaReconciliationSpec`: direct per-region pin of the reconcile arm against a captured legacy
`multiRegionDefault` baseline (phase-4/5/9 style); the depletion coupling documented as a v2 improvement.
`specs/aws-component-catalog.md` (multi-region / replication / rWCU / the two metrics) + `specs/README.hot-replica.md`
+ a CLAUDE.md demo entry. Close-out coda: roadmap COMPLETE, program roadmap, memory, full `sbt test`.

## Scope boundary

Multi-region write replication with rWCU + cross-region transfer + the two replication metrics; per-region
provisioned rWCU. Not in scope: replica auto-scaling of rWCU, global-table add/remove-region dynamics, strongly
consistent cross-region reads. After this phase, **phase 12** ports the Grafana pipeline and deletes the legacy code.
