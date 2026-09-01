# AWS Component Catalog — Engineer's Guide

A catalog of the reusable **AWS-specific** building blocks in the `stochastacy-aws` module (`aws/`,
package `stochastacy.aws.dynamodb`). It is the sibling of the [core component catalog](component-catalog.md):
that one covers the domain-agnostic engine; this one covers the components that model concrete AWS
resources on top of it. Like the core catalog, it describes the parts as **building blocks you can drop
into your own simulator** — what each is, the properties it guarantees, when to reach for it, and how the
pieces compose — as distinct from the [demo guides](README.ordertracking-v2.md), which explain what a
particular simulation *shows* and how to run it.

Scope today is **DynamoDB** — a single table with **Query/Scan and secondary indexes**, **on-demand or
provisioned billing** (with throttling and scheduled reconfiguration), **item TTL** (storage expiry), and
**transactions** (2× capacity), composable into **multi-table** simulations. This catalog grows as the AWS
line does; auto-scaling and multi-region are expected with the thermostat-fleet capstone.

## How to read an entry

Each primary entry follows the same template as the core catalog:

- **Purpose** — one line.
- **Signature** — the type it presents.
- **Properties** — the logical guarantees it upholds.
- **When to use** — the problem it solves.
- **Composition** — how it fits with other components.
- **Exercised by** — the demo and the tests that prove each property.

---

## The DynamoDB table

### `DynamoDbTable`

**Purpose.** Model a single DynamoDB table — with its secondary indexes — as a v2 component: turn a stream
of requests into responses and the resource-consumption facts (capacity, storage) that price out to a bill.

**Signature.**
```scala
DynamoDbTable.componentOf(config: DynamoDbTable.Config, rng: UniformRandomProvider)
  : Graph[FanOutShape2[Timed[DynamoDbRequest], Timed[DynamoDbResponse], Timed[DynamoDbConsumption]],
          Future[ComponentResult[TableState]]]

final case class Config(
  initialState:           TableSummaryState,         // the base table's pre-loaded contents
  behavior:               TableBehavior,             // the injected domain (see below)
  latency:                StatelessSampler[Double],  // per-op service latency, fractional ticks
  globalSecondaryIndexes: Vector[GlobalSecondaryIndex] = Vector.empty,
  localSecondaryIndexes:  Vector[LocalSecondaryIndex]  = Vector.empty)
  // .withGlobalSecondaryIndex(…) / .withLocalSecondaryIndex(…) builders append indexes
```
*(element types abbreviated; the wire carries `TimedElement[Timed[…]]`.)*

**Configuration.** A table is configured, not wired: you give it its initial contents, a domain behavior,
a latency distribution, and **declare its secondary indexes on it** (via the `with…SecondaryIndex`
builders). Indexes are *intrinsic table structure* — a table *with* an index accepts different requests
and bills differently — so they live in the `Config`, never as separate graph nodes. (There is no
`readConsistency` knob: read consistency is a per-read domain decision the behavior bakes into each read.)

**Properties.**
- **Generic mechanics, injected domain.** The table owns the *mechanics* — capacity math, storage
  evolution, index maintenance, response shaping — and takes the *domain* as a plug-in
  [`TableBehavior`](#tablebehavior) (the v2 counterpart to the legacy `DynamoDbTable` + `UseCaseSampler`
  split). The same component serves any single-table workload.
- **Composite stochastic-summary state.** State is a [`TableState`](#tablesummarystate--tablestate) — the
  base [`TableSummaryState`](#tablesummarystate--tablestate) plus one summary per secondary index (each an
  item count + total-bytes figure), never a per-key map. Cost is near-constant in request volume and
  key-space size. The materialized value carries the **final `TableState`** (base + every index).
- **Operations.** Point ops `GetItem` / `PutItem` / `UpdateItem` / `DeleteItem`, and multi-item reads
  `Query` / `Scan` that carry a [`DynamoDbTarget`](#the-protocol) (the base table or a named index) and a
  read consistency. Every consumption fact is **tagged with its target**, so per-index usage breaks out
  downstream.
- **Reads consult their target's own state.** A `Query`/`Scan` against a GSI/LSI is resolved against *that
  index's* summary (its population and its projected entry size), not the base table's — the table routes
  the target's state to the behavior. So a scan can be sized against the whole (projected) index and a
  query's selectivity bounded by the index's population.
- **Writes maintain the indexes.** A base write fans out to each index via
  [`SecondaryIndexMechanics`](#secondaryindexmechanics): the index's entry is inserted / replaced /
  deleted (per its projection), consuming the index's own WCU and moving its storage — **GSI maintenance
  asynchronously** (a per-index propagation delay), **LSI synchronously**. Index summaries are seeded from
  the base's pre-loaded items (projected) — the entries a fresh index over an existing table already holds.
- **Two output planes.** One forward **response** per request (in-band — the response *is* the outcome),
  and zero-or-more target-tagged **consumption facts** ([`DynamoDbConsumption`](#the-protocol)) on the
  metric plane.
- **Execution-time metering, latency on the response.** Consumption is stamped at execution time
  (`delay 0`, GSI maintenance at its propagation delay) — capacity is consumed and storage changes when
  the operation runs — while the response is delayed by the per-op service latency. Latency affects only
  response timing, never a total.
- **Deterministic & reproducible** — given the seed, output is identical (the mechanics are rng-free; all
  randomness is the behavior's and the latency sampler's).

**When to use.** Estimate a DynamoDB table's (and its indexes') capacity consumption, storage growth, and
on-demand cost for a workload you can describe as a request mix — without provisioning anything or tracking
per-item state.

**Composition.** The table is a **leaf**, and the composable graph-level unit: requests in, responses +
consumption out. Drive it with a tick-framed `Timed[DynamoDbRequest]` source (see
[`OrderTrackingWorkload`](README.ordertracking-v2.md)) and fold the consumption plane downstream into usage
totals and cost. A **multi-table** demo composes several `DynamoDbTable`s as independent legs, reported
per-table (`Table:<name>:…`) — the shared demo harness generalizes to N tables via a per-table `TableSpec`,
reusing the single-table accounting / aggregation / streaming primitives (see the
[Thermostat multi-table demo](README.thermostat-v2.md#multi-table-composition-several-tables-in-one-simulation)).
**Indexes never appear at graph level** — one consistent rule (*decoration for cross-cutting edge behavior;
configuration for intrinsic table structure*). Because it presents a `Req → Resp` edge, a core
[`Interface.wrap`](component-catalog.md#interfacewrap) gate decorates it transparently: the
[Thermostat-fleet demo](README.thermostat-v2.md) wraps the table with a `ChaosGate` at the table's inlet to
model DynamoDB's intrinsic ~0.1 % transient failures (a rejected request consumes nothing) — the first
realized decoration on an AWS table.

**Billing mode, throttling, and reconfiguration** (intrinsic config, not a gate). A `BillingMode` on the
`Config` selects **on-demand** (pay per consumed unit; uncapped) or **provisioned** (a reserved RCU/WCU
capacity, base plus explicitly-provisioned GSIs, billed by **capacity-hours**). A provisioned table
**throttles** — an internal, **per-target** weighted per-tick budget (base + LSI share the base; each GSI its
own, base-fallback ceiling) held in `TableState`: a request whose mechanics-computed demand would push any
target past its ceiling is rejected whole with a `ThrottledResponse`, consuming nothing and mutating no state
(reset each tick). Capacity-unit throttling is coupled to the billing mode and the table's own capacity math,
so it lives **inside** the table rather than in an edge gate — the gate family stays the tool for request-rate
limits. A `ReconfigurationSchedule` applies `SwitchBillingMode` / `UpdateProvisionedCapacity` at tick
boundaries (24 h switch cooldown); the mode-in-force is a pure `billingModeAt(tick)` fold shared by the table
and the accounting. See the [mixed-mode demo](README.thermostat-v2.md#mixed-mode-provisioned-capacity--throttling--reconfiguration).

**Burst capacity** (provisioned only). A `burstWindowTicks` on the `Config` turns the per-tick throttle
budget from a hard reset into a **carry-forward bank**: at each tick boundary a target banks its unused
capacity (`ceiling − admitted`) into `[0, ceiling × burstWindowTicks]`, and a tick may admit demand up to
`ceiling + banked` — so a short spike is absorbed by banked capacity before throttling, and a sustained
over-ceiling load drains the bank and then throttles (`ThrottleBudget.rollForward`). The window is expressed
in **ticks** (DynamoDB's ~300 s of burst is `300 / tick-seconds` ticks); the bank is **table-level** (per
budget target: base+LSI, each GSI), a per-partition refinement deferred to hot-partition modeling. `0` = off
(the budget resets exactly as before, on-demand tables unaffected).

**Reactive auto-scaling** (provisioned only). An `AutoScalingPolicy` on the `Config` makes the table's
**base** read/write capacity track a target utilization (default 0.70): `onTick` reads the just-completed
tick's utilization (admitted base capacity ÷ current ceiling), keeps a rolling `evaluationWindowTicks`
window, and when the average crosses the scale-up (`> target`) or scale-down (`< target ×
scaleDownThresholdFactor`) threshold — and the direction's cooldown has elapsed — schedules a target-tracking
change (`ceil(consumed / target)`, clamped to `[min, max]`) that applies after a reaction delay
(scale-up-fast / scale-down-slow). A faithful port of the legacy auto-scaler's logic, but as pure `onTick`
mechanics (state threaded in `TableState`), **mutually exclusive** with a `ReconfigurationSchedule`. Base-table
only (per-GSI auto-scaling is out of scope). The scaled capacity drives both throttling *and* cost: because it
is chosen at runtime rather than from a static schedule, `onTick` emits the tick's reserved capacity as a
`ProvisionedCapacitySnapshot` (via tick-boundary emission), which the accounting integrates in place of the
`billingModeAt` fold — so provisioned capacity-hour cost tracks the actual per-tick capacity. See the
[auto-scaling telemetry demo](README.thermostat-v2.md#auto-scaling--burst-capacity-provisioned-throughput-dynamics).

**TTL (time-to-live storage expiry)** (intrinsic config, not a gate). A `ttlPeriodTicks` on the `Config`
makes each written item expire that many ticks after it is written. It is **generic table mechanics** — the
expiry is deterministic, so no behavior hook is needed: an immutable `Vector`-backed `TtlRingBuffer`
(`ttlPeriodTicks + 1` slots of `(count, bytes)`) threaded in `TableState` records each write (an overwrite
re-ages the item; an explicit delete removes one from the soonest-to-expire slot), and `onTick` drains the
cohort written `ttlPeriodTicks` ago — using the core's [tick-boundary consumption emission](component-catalog.md)
to free **base and per-index** storage as negative, target-tagged `StorageBytesDelta` facts (projection-sized,
the exact inverse of write-time index maintenance), **consuming no capacity**. Pre-loaded items carry no
write tick, so they never TTL-expire. A table with no `ttlPeriodTicks` is byte-identical to one before TTL
existed. See the [session-store demo](README.session-store-ttl.md).

**Transactions** (`TransactWriteItems` / `TransactGetItems`). A transactional write carries several sub-item
writes applied **all-or-nothing** (one `Emission`; under provisioned billing the whole transaction is
throttled as a unit, mutating nothing); a transactional read groups several strongly-consistent gets. Capacity
follows **AWS's two-phase-commit billing**, which is *target-dependent*: the base-table write and its
**synchronous, co-located LSI** maintenance are billed **2×**, while a **GSI** back-fill — which propagates
*asynchronously after* the commit — is billed at the standard **1×**; transactional reads are 2× strongly
consistent per item. (This deliberately diverges from the legacy simulator, which billed both index types at
1×.) Each sub-write flows through the same storage, per-index maintenance, and TTL machinery as a single write.
See the [payments-ledger demo](README.payments-transactions.md).

**Point-In-Time Recovery** (a cost dimension, not a table mechanic). A `pointInTimeRecoveryEnabled` flag on the
demo's `TableSpec` / scenario bills **continuous-backup storage** at a PITR GB-month rate on the table's stored
byte-ticks (base + indexes — the same integral as storage), folded into the estimated cost and surfaced as
`TotalPitrCost` only when enabled. It has no effect on requests, consumption, or throttling — so it lives in the
accounting, not `DynamoDbTable`. `false` = off = byte-identical.

**Scope.** On-demand or provisioned billing (with throttling, scheduled reconfiguration, **burst capacity**, and
**reactive auto-scaling**), item TTL, transactions, **PITR** (backup cost), a single table with Query/Scan +
GSIs/LSIs, and none of the remaining advanced models (hot-partition, adaptive, replication). Those belong to
later phases. Transaction conditional-checks / partial-failure (`TransactionCanceledException`) are out of scope.

**Exercised by.** [Order-Tracking v2](README.ordertracking-v2.md) (single table, then Query/Scan + two
All-projection GSIs); the [Thermostat-fleet demo](README.thermostat-v2.md) (a growing fleet with **mixed
index projections** — KeysOnly / Include / All — an **inbound `ChaosGate`**, a **multi-table** composition of
two thermostat tables, and a **mixed-mode** run exercising provisioned billing + throttling + scheduled
reconfiguration); the [session-store demo](README.session-store-ttl.md) (item **TTL** — storage plateaus as
creations balance expiries); the [payments-ledger demo](README.payments-transactions.md) (**transactions** —
the ≈2× capacity premium vs. equivalent single operations); `aws/…/DynamoDbTableSpec.scala`
(timed response + execution-time consumption, state threading, GSI+LSI maintenance, read routing to a
projected GSI, control-event preservation, determinism); `aws/…/DynamoDbTableTtlSpec.scala` and
`aws/…/TtlRingBufferSpec.scala` (TTL expiry timing, base + per-index freeing, delete-vs-expire, TTL-off
byte-identity); `aws/…/DynamoDbTableTransactionSpec.scala` (base/LSI 2× + GSI 1×, atomic all-or-nothing, TTL
over sub-writes); the `OrderTrackingEquivalenceSpec.scala`,
`OrderTrackingIndexedReconciliationSpec.scala`, and `ThermostatFleetReconciliationSpec.scala` (reconcile
against the legacy demos).

### Supporting types

#### `TableBehavior`
The injected domain seam: `outcomeFor(request, state, rng, tick): TableMechanics.OperationOutcome`. Given a
request and **the state of the target it hits** (the sampler routes it — base for writes/gets/table reads,
the index's summary for a GSI/LSI read) and the current `tick` (so a behavior can be time-dependent, e.g. a
growing fleet), it *draws what the operation did* — a read hit/miss and size, the
bytes written, whether an item existed, or a read's shape (how many items/bytes were evaluated vs.
returned). All operation-level randomness lives here; the mechanics that follow are pure. Implement one per
domain (the demo's is `OrderTrackingBehavior`).

#### `TableMechanics`
The rng-free base-table mechanics. `resolve(outcome, state): Resolution` maps an `OperationOutcome`
(`Get` / `Put` / `Update` / `Delete` / `Query` / `Scan`, each carrying whatever it needs — reads their
target + consistency + `ReadShape`) to the response, the target-tagged consumption facts, and the next
state — RCU from a read's *evaluated* bytes, WCU/storage from a write. Pure and seedless, so exhaustively
unit-testable without a graph.

#### `SecondaryIndexMechanics`
The rng-free index-maintenance mechanics — the sibling of `TableMechanics`, over an index's own
`TableSummaryState`. `maintain(index, newBaseBytes, prevBaseBytes, indexState): Maintenance` projects the
base write's new/previous item to index-entry sizes (per the index's `IndexProjection` — `All`, `KeysOnly`
capped at a 128 B key floor, or `Include(n)`), decides insert / replace / delete / no-op, and returns the
index's WCU + storage-delta (tagged with the index target) and its next state. GSI and LSI share this math;
their only difference — asynchronous vs. synchronous timing — is applied by the caller.

#### `TableSummaryState` / `TableState`
`TableSummaryState` is an immutable `(itemCount, totalItemBytes)`, average derived, with pure
`applyWrite` / `applyDelete` transitions — the summary of one store (the base table or one index).
`TableState` is the whole-table state the sampler threads: the base summary plus one `TableSummaryState`
per index (keyed by name), seeded from the base's projected items.

#### `SecondaryIndex` / `IndexProjection`
`GlobalSecondaryIndex(indexName, projection = All, propagationDelayTicks = 0.0)` and
`LocalSecondaryIndex(indexName, projection = All)` — the value objects a table declares. A GSI is an
independent sub-store maintained asynchronously (raise `propagationDelayTicks` to model eventual-consistency
lag); an LSI shares the base partition and is maintained synchronously. `IndexProjection` (`All` /
`KeysOnly` / `Include(nonKeyBytes)`) sets each index entry's size, and so its storage and maintenance cost.

#### The protocol
`DynamoDbRequest` (`GetItemRequest` / `PutItemRequest(itemBytes)` / `UpdateItemRequest(itemBytes)` /
`DeleteItemRequest` / `QueryRequest(target, consistency)` / `ScanRequest(target, consistency)`) and
`DynamoDbResponse` are **timeless** payloads — timing lives on the `Timed[E]` envelope. `DynamoDbTarget`
(`Table` | `Gsi(name)` | `Lsi(name)`) names the store a request/fact concerns. `DynamoDbConsumption` is the
metric plane, each fact tagged with its `target`: `ReadCapacityConsumed(units, consistency, target)`,
`WriteCapacityConsumed(units, target)`, `StorageBytesDelta(bytesDelta, target)`. `ReadConsistency` sets the
RCU multiplier (strong ×1, eventual ×0.5), applied by `ThroughputMath` (4 KB read / 1 KB write chunks,
one-chunk minimum).

---

## Foundations

The table is a `ComponentSampler` and rests entirely on the domain-agnostic core — the
`ScheduleReleaseTransducer` runs it, `TickFraming` frames its input, the distribution samplers feed its
workload and latency, and `MonteCarlo` / `SeedSequence` drive the ensemble. Those are documented once in
the [core component catalog](component-catalog.md#foundations); they are not repeated here.

## Quick reference

| I want to… | Component |
|---|---|
| model a DynamoDB table's capacity + storage + on-demand cost | `DynamoDbTable` |
| add a secondary index to a table | `Config.withGlobalSecondaryIndex` / `withLocalSecondaryIndex` |
| plug my domain's read/write/query outcomes into a table | implement `TableBehavior` |
| check per-op RCU/WCU/storage without a graph | `TableMechanics.resolve` |
| check an index's maintenance without a graph | `SecondaryIndexMechanics.maintain` |
| choose how much of an item an index projects | `IndexProjection` (`All` / `KeysOnly` / `Include`) |
| add throttling to a table (later) | a core `Interface.wrap` gate on the table's edge |

## See also

- [Order-Tracking v2 — DynamoDB on the v2 core](README.ordertracking-v2.md) — the table as a worked example.
- [Core component catalog](component-catalog.md) — the engine the table is built on.
