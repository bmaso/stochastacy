# AWS Component Catalog — Engineer's Guide

A catalog of the reusable **AWS-specific** building blocks in the `stochastacy-aws` module (`aws/`,
package `stochastacy.aws.dynamodb`). It is the sibling of the [core component catalog](component-catalog.md):
that one covers the domain-agnostic engine; this one covers the components that model concrete AWS
resources on top of it. Like the core catalog, it describes the parts as **building blocks you can drop
into your own simulator** — what each is, the properties it guarantees, when to reach for it, and how the
pieces compose — as distinct from the [demo guides](README.ordertracking-v2.md), which explain what a
particular simulation *shows* and how to run it.

Scope today is **DynamoDB** (a single on-demand table). This catalog grows as the AWS line does; a second
component (multi-table, multi-region) is expected with the thermostat-fleet capstone.

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

**Purpose.** Model a single DynamoDB table as a v2 component: turn a stream of requests into responses and
the resource-consumption facts (capacity, storage) that price out to a bill.

**Signature.**
```scala
DynamoDbTable.componentOf(
  config: DynamoDbTable.Config,   // initialState, behavior, latency, readConsistency
  rng:    UniformRandomProvider
): Graph[FanOutShape2[Timed[DynamoDbRequest], Timed[DynamoDbResponse], Timed[DynamoDbConsumption]],
         Future[ComponentResult[TableSummaryState]]]
```
*(element types abbreviated; the wire carries `TimedElement[Timed[…]]`.)*

**Properties.**
- **Generic mechanics, injected domain.** The table owns the *mechanics* — capacity math, storage
  evolution, response shaping — and takes the *domain* as a plug-in [`TableBehavior`](#tablebehavior).
  The same component serves any single-table workload; the demo supplies order-tracking's behavior. (This
  is the v2 counterpart to the legacy `DynamoDbTable` + `UseCaseSampler` split.)
- **Stochastic-summary state.** State is a [`TableSummaryState`](#tablesummarystate) — an item count and a
  total-bytes figure, average derived — not a per-key map. Cost is near-constant in request volume and
  key-space size (the project's core modelling principle).
- **Two output planes.** One forward **response** per request (in-band — the response *is* the outcome),
  and zero-or-more **consumption facts** ([`DynamoDbConsumption`](#the-protocol)) on the metric plane. The
  materialized value carries the table's **final `TableSummaryState`**.
- **Execution-time metering, latency on the response.** Consumption is stamped at execution time
  (`delay 0`) — capacity is consumed and storage changes when the operation runs — while the response is
  delayed by a per-op service latency drawn from a `StatelessSampler[Double]`. Latency affects only
  response timing, never a total.
- **Deterministic & reproducible** — given the seed, output is identical (the mechanics are rng-free; all
  randomness is the behavior's and the latency sampler's).

**When to use.** Estimate a DynamoDB table's capacity consumption, storage growth, and on-demand cost for
a workload you can describe as a request mix — without provisioning anything or tracking per-item state.

**Composition.** The table is a **leaf**: requests in, responses + consumption out. Drive it with a
tick-framed `Timed[DynamoDbRequest]` source (see [`OrderTrackingWorkload`](README.ordertracking-v2.md));
fold the consumption plane downstream into usage totals and cost. Because it presents a `Req → Resp` edge,
a core [`Interface.wrap`](component-catalog.md#interfacewrap) gate could later decorate it (e.g. to add
throttling) — the natural v2 home for admission control, which this Phase-1 table deliberately omits.

**Scope (Phase-1).** On-demand billing with **no throughput cap → no throttling**, a single table, no
GSI/LSI, and none of the advanced models (hot-partition, burst, adaptive, PITR, TTL, replication). Those
belong to later phases.

**Exercised by.** [Order-Tracking v2](README.ordertracking-v2.md); `aws/…/DynamoDbTableSpec.scala` (timed
response + execution-time consumption, multi-request state threading, control-event preservation,
determinism); the equivalence gate `OrderTrackingEquivalenceSpec.scala` (reproduces the legacy demo).

### Supporting types

#### `TableBehavior`
The injected domain seam: `outcomeFor(request, state, rng): TableMechanics.OperationOutcome`. Given a
request and the current summary, it *draws what the operation did* — a read hit/miss and size, the bytes
written, whether an item existed. All operation-level randomness lives here; the mechanics that follow are
pure. Implement one per domain (the demo's is `OrderTrackingBehavior`).

#### `TableMechanics`
The rng-free mechanics. `resolve(outcome, readConsistency, state): Resolution` maps an `OperationOutcome`
(`Get` / `Put` / `Update` / `Delete`) to the response, the consumption facts, and the next state —
computing RCU/WCU via `ThroughputMath` and the storage delta from the state transition. Being pure and
seedless, it is exhaustively unit-testable without a graph.

#### `TableSummaryState`
Immutable `(itemCount, totalItemBytes)`, average derived; `applyWrite` / `applyDelete` are the pure
transitions (matching the legacy recorder semantics). This is the functionally-threaded `ComponentSampler`
state — the immutable v2 counterpart to the legacy mutable `SummaryTableState`.

#### The protocol
`DynamoDbRequest` (`GetItemRequest` / `PutItemRequest(itemBytes)` / `UpdateItemRequest(itemBytes)` /
`DeleteItemRequest`) and `DynamoDbResponse` are **timeless** payloads — timing lives on the `Timed[E]`
envelope. `DynamoDbConsumption` is the metric plane: `ReadCapacityConsumed(units, consistency)`,
`WriteCapacityConsumed(units)`, `StorageBytesDelta(bytesDelta)`. `ReadConsistency` sets the RCU multiplier
(strong ×1, eventual ×0.5), applied by `ThroughputMath` (4 KB read / 1 KB write chunks, one-chunk
minimum).

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
| plug my domain's read/write outcomes into a table | implement `TableBehavior` |
| check per-op RCU/WCU/storage without a graph | `TableMechanics.resolve` |
| add throttling to a table (later) | a core `Interface.wrap` gate on the table's edge |

## See also

- [Order-Tracking v2 — DynamoDB on the v2 core](README.ordertracking-v2.md) — the table as a worked example.
- [Core component catalog](component-catalog.md) — the engine the table is built on.
