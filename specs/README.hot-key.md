# Hot-Key: Hot-Partition Throttling & Adaptive Capacity (DynamoDB on the v2 core) — Engineer's Guide

A small, purpose-built demo that makes DynamoDB's **spatial** capacity model visible: provisioned capacity is
split across physical partitions, so a workload that concentrates on a few **hot keys** drives one partition to
throttle *even while the table has aggregate spare*. **Instant adaptive capacity** relieves it up to the
per-partition physical max, and **split-for-heat** grows the partition topology under sustained heat. Three
arms on the identical workload make the effects legible by contrast.

The example lives in the `aws/` module, package `stochastacy.aws.examples.hotkey`; the reusable table
component it drives lives in `stochastacy.aws.dynamodb` (see the
[AWS component catalog](aws-component-catalog.md#the-dynamodb-table) — *Hot-partition throttling + instant
adaptive capacity* and *Split-for-heat*).

---

## 1. What the demo demonstrates

### The fictional domain
A single **provisioned** table (`Provisioned(read 3000, write 4000)` → a derived **5** physical partitions;
per-partition physical max **1000 WCU**, which sits *below* the table's 4000, so a hot partition genuinely
binds). Each tick a Poisson number of puts overwrite items in place (storage flat — the demo isolates
*throughput*, not storage). The hotness lives entirely in the behavior's `partitionAccessFor`: with probability
`hotFraction` a request targets one of `hotKeyCount` **hot** keys, otherwise a distinct **cold** key spread
across `coldKeySpace`.

### Three arms, one workload
| arm | what it isolates |
|---|---|
| **hot, adaptive on** | the realistic DynamoDB default — the hot partition is relieved to the physical max, and split-for-heat grows the topology |
| **hot, adaptive off** | the fair-share baseline (`capacity / partitionCount`) — the same hot partition throttles harder |
| **well-distributed** | `hotFraction = 0` — no per-partition hotspot, so throttling all but vanishes |

### A representative run (20 trials × 100 ticks, default single hot key)
| arm | mean offered | mean throttled | throttle rate |
|---|---|---|---|
| hot, adaptive on | 299,949 | 88,463 | **29.5 %** |
| hot, adaptive off | 299,949 | 123,891 | **41.3 %** |
| well-distributed | 299,949 | 0 | **0.0 %** |

**Adaptive relief (throttles avoided, on vs off): 28.6 %.** The well-distributed arm confirms it is a
*per-partition* effect — the table has spare (3000 offered writes/tick < the 4000 cap). The hot arm's **effective
partition count grows 5 → 20** (base + heat-splits).

## 2. The mechanisms

- **Per-partition throttling.** The partition count is *derived* from capacity + storage
  (`PartitionTopology.derive`), and each request's key is hashed to a partition. A partition throttles when its
  admitted demand would exceed its ceiling — while the table as a whole still has room. State is bounded by the
  partition count, never the key space.
- **Instant adaptive capacity.** With `adaptiveCapacity` on (the default), the per-partition ceiling is the
  **physical max** (3000 RCU / 1000 WCU): adaptive capacity is *instant and always-on*, so a hot partition
  instantly borrows idle table capacity up to that limit (the table-total bound is enforced separately). With it
  off, the ceiling drops to the **fair share** — the without-adaptive baseline the demo contrasts against.
- **Split-for-heat.** A partition sustained-hot for `HeatSplitPolicy.windowTicks` **permanently** grows the
  effective partition count, re-hashing a hot key *range* across more partitions.

### The single-hot-key nuance (honest limit)
The default uses **one** hot key, which gives reliable, legible throttling — but a lone key is the **AWS
single-item limit**: split-for-heat *activates* (the count climbs to the cap, here 20) yet **cannot relieve** it,
because a single key always hashes to one partition. That is why the on-arm still throttles ~29 % despite the
growth. Split-for-heat's *relief* needs a hot **range** of colliding keys that separate as the count grows — the
model does this, proven directly in `HeatSplitSpec` (two keys colliding at count 5 admit 1000 combined, but
> 1000 once split to count 6). Set `hotKeyCount` above the partition count to explore it.

## 3. The hybrid reconcile

The legacy hot-partition / adaptive models are unreferenceable from this module and hash differently
(`MurmurHash3` vs `String.hashCode`), so `HotKeyReconciliationSpec` reconciles **internally + transitively**:

- **Control arm (tight).** On a well-distributed, table-saturating workload the per-partition machinery is
  *inert* — the access-on path matches the **table-level-only** path (access off) within ~2 %. That access-off
  path *is* the phase-6/8 table-level path already reconciled against the legacy, so the control arm inherits
  that reconcile transitively.
- **Hot arm (directional + documented).** Adaptive-**on** throttles strictly fewer than **off**, and the
  effective count grows. The legacy's *lagged* adaptive would land *between* off and on; the legacy *configures*
  the partition count v2 *derives*; and its heat-split grows the count as v2 does (matched in *direction*).

## 4. Running it

No external services; writes per-tick JSONL for the adaptive-on arm plus a console summary.

```bash
sbt 'aws/runMain stochastacy.aws.examples.hotkey.HotKeyDemo --output /tmp/hot-key.jsonl --trials 20 --ticks 100 --seed 1'
```

Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>` `--parallelism <int>`.
Each per-tick JSONL row is `{"arm","tick","meanOffered","meanThrottled","meanAdmitted"}` — the across-trial mean
throttling profile, chartable directly. The run is **deterministic**: a fixed seed reproduces every arm exactly.
