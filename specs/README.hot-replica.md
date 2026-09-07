# Hot-Replica: Multi-Region Global Tables (DynamoDB on the v2 core) — Engineer's Guide

A thermostat-flavored, three-region **Global Table** demo that makes DynamoDB's multi-region model visible: every
local write **replicates** to the other regions (billed there as **rWCU**, not WCU), and an under-provisioned
replica's inbound **rWCU ceiling** makes replication **back up** — its `PendingReplicationCount` and
`ReplicationLatency` climbing, the true indicators of rWCU depletion. Two arms on the same shape make the effect
legible: a healthy reconcile arm and an 8:1 depletion arm.

The example lives in the `aws/` module, package `stochastacy.aws.examples.hotreplica`; the reusable components it
drives live in `stochastacy.aws.dynamodb` (see the
[AWS component catalog](aws-component-catalog.md#multi-region--global-tables) — `GlobalTable` and
`ReplicationCoordinator`).

---

## 1. What the demo demonstrates

### The fictional domain
Three regions — **us-east-1 / eu-west-1 / ap-southeast-1** — each running a thermostat **telemetry** table (the
reused single-region `ThermostatConfig`: mixed-projection GSIs + LSI, temporally-shaped telemetry writes, customer
queries, fleet scans), composed into one `GlobalTable`. Each region's writes replicate to the other two after a
per-link lag; every replica ends up a full copy of the whole fleet's data.

### Two arms
| arm | what it isolates |
|---|---|
| **reconcile** | all on-demand, the legacy fleets 1800 / 900 / 300 — healthy replication: `PendingReplicationCount` bounded (≈ arrivals × lag), `ReplicationLatency` ≈ the link-lag mean on every link |
| **depletion** | an **8:1** fleet discrepancy (2000 / 250 / 300); `ap-southeast-1` is **provisioned with an inbound rWCU ceiling below its combined inbound**, and its `us-east-1 →` link is modestly longer — so **both** inbound links back up, and **diverge** |

### A representative depletion run (per-link, into the rWCU-capped ap-southeast-1)
| source link | pending (mean) | latency (mean / max) |
|---|---|---|
| **us-east-1 → ap-southeast-1** (heavy) | ~3660 | ~56 / ~110 |
| **eu-west-1 → ap-southeast-1** (light) | ~290 | ~21 / ~43 |

Both inbound links lag, with a clear **per-link distinction** — the heavy `us-east-1` stream builds the deeper,
slower queue (pending ≈ **12×** the light stream; the 8:1 arrival ratio becomes a larger backlog ratio under the
equal fair-share drain). Every *other* link — the two large regions' own inbound — stays healthy (latency ≈ 1,
pending bounded). The reconcile arm shows all links healthy.

## 2. The mechanisms

- **Replication = full copies.** A local admitted write **taps** its resolved outcome; the coordinator delays it
  per link and routes it to each peer, where `onFeedback` **replays** that outcome — so every replica converges to
  the same dataset, matching AWS (each replica is a full copy, storage billed per region).
- **rWCU billing.** An inbound replicated write bills `ReplicatedWriteCapacityConsumed` (base + index), priced at
  the **AWS-correct rate (rWRU = WRU)**. It never re-taps (loop-prevention is structural).
- **rWCU throttling — fair-share.** A provisioned replica's `replicatedWriteCapacityUnits` ceiling is a per-tick
  budget its inbound source streams share, drained work-conserving round-robin. It gates **base** rWCU only (a
  replica's GSIs carry their own replicated capacity in AWS).
- **The two depletion metrics.** `ReplicationLatency` = the **measured** release − enqueue latency; 
  `PendingReplicationCount` = the **in-flight count** at window close. Under depletion both grow and the source
  streams diverge; on recovery they drain. (This coupling to the rWCU ceiling is a v2 improvement — the legacy
  decouples the metrics from throttling.)
- **No transfer charge.** AWS does not bill cross-region transfer for global-table replication; the demo reports
  transfer **bytes** as a volume metric, at no cost.

## 3. Reconcile + AWS-accuracy posture

The reconcile arm reproduces the legacy `thermostat-fleet-multi-region` demo per region (matched fleets, growth,
and per-region pricing). `HotReplicaReconciliationSpec` **pins RCU and WCU** (clean, ~1–4 % — validating the
workload and replication *volume*) and **documents** storage and cost as bounded divergences:

- **Storage** (uniform ~16 %): a **summary-model saturation-pollution** limitation — a region's insert/overwrite
  heuristic reads replication-polluted state, so the converged per-region population deviates from the true
  key-space union (bounded, **negligible cost**). Recorded under
  [Known discrepancies](aws-component-catalog.md#known-discrepancies) with a fix sketch.
- **Cost** (up to +59 % at the rWCU-heavy replica): v2 is *higher because it is more accurate* — it prices rWCU at
  the AWS rate (rWRU = WRU), where the legacy underprices it.

Building this arm fixed two AWS-accuracy bugs the reconcile surfaced (both grounded in the AWS docs, not the
legacy): replicas now hold the full-copy union (replayed source outcome), and replication transfer is free.

## 4. Running it

No external services; runs both arms, writes per-tick JSONL for the depletion arm plus a console summary.

```bash
sbt 'aws/runMain stochastacy.aws.examples.hotreplica.HotReplicaDemo --output /tmp/hot-replica.jsonl --trials 50 --ticks 300 --seed 1'
```

Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>` `--parallelism <int>`.
The console summary reports, per arm: per-region RCU / WCU / rWCU / throttles / cost, and per-link pending +
latency (mean / p95 / max). Each per-tick JSONL row is
`{"scenarioId","tick","sourceRegion","destRegion","pending","latencyMean"}` — the depletion arm's first-trial
per-link trace, chartable directly. The run is **deterministic**: a fixed seed reproduces both arms exactly.
