# Global Tables (Slice 10) — Design Decisions

Last updated: 2026-04-26
Status: **shipped** — slice 10 implementation complete; this document records the design decisions made before implementation.

## Context

Slice 10 of phase 3 of the Stochastacy DynamoDB simulator adds support for DynamoDB Global Tables — a single logical table replicated across multiple AWS regions. This document accumulates the design decisions made during planning, so the rationale is preserved before implementation begins. Once all decisions are locked, the implementation plan will reference this document.

## Brief on AWS DynamoDB Global Tables (v2 / 2019.11.21 schema)

- Each regional replica is a fully-functional DynamoDB table — peer, not subordinate. All replicas accept reads and writes locally.
- Replication is asynchronous and stream-driven. Each replica has its own DynamoDB Stream; a background replication service propagates each replica's stream events to the other replicas.
- Each replica bills independently for local writes (WCU), inbound replicated writes (a separate "replicated WCU" bucket), reads, storage, and cross-region data transfer.
- Conflict resolution is last-writer-wins using origin-region timestamps.
- Reads are local-region only — there is no cross-region read path. Strongly consistent reads work within a region; cross-region consistency is eventual.
- Replica set membership is a control-plane operation; per-write logic treats topology as static.

## Decision 1: Table identity and replication architecture

**Decision:** A global table is modeled as **N independent `DynamoDbTable` component graphs**, one per replica region. A new **replication coordinator stage** propagates writes between them via stream-driven sync events that mirror the DynamoDB Streams mechanism real AWS uses. The public surface is a new **`DynamoDbGlobalTable`** component that wraps the per-region instances and exposes per-region request/response/consumption/metric streams; per-region resource costs accrue independently.

**Why:** This mirrors how DynamoDB Global Tables actually work in AWS — each replica is a peer, fully-functional regional table; replication is asynchronous and stream-driven; each replica bills independently. Modeling it this way means the slice 1–9 machinery (admission, storage, item-collection limit, dynamic topology, GSI back-pressure, projection-aware reads, plan-driven index maintenance) carries forward **unchanged per region**; the cross-region behavior lives entirely in a new coordinator stage. No regional table needs to know it's part of a global table.

**Implications for downstream design:**
- Reads do not need any new cross-region logic; they remain region-local.
- The replication coordinator subscribes to a per-region "successful write" output (the slice-9 validated-admitted-sample stream is a natural fit, or a dedicated new "stream events" output) and fans replicated work to peer regions.
- Per-region consumption/metric events flow up to the public component as separate streams, so downstream usage/pricing layers can attribute work to the right region.

**Status:** Locked.

## Decision 2: Success criterion and replication lag modeling

**Decision:** Slice 10's success criterion is **per-region cost accounting** for global tables. Users can answer "is global tables worth it for our workload, in cost terms, across N replicas?". Each replica is an independent regional `DynamoDbTable` graph that handles its own admission, storage, and consumption.

The replication coordinator propagates writes between replicas with a **stochastic per-write lag** sampled from a distribution **keyed by directional `(sourceRegion, destinationRegion)` link pair**. Distributions are configured at the global-table level on a `ReplicationModel` config object (using Apache Commons Statistics distribution types — the project already depends on this library), not per-item or per-use-case. A default distribution applies to any unconfigured link.

**Conflict-resolution semantics are explicitly out of scope.** The simulator assumes workloads do not issue genuinely conflicting cross-region writes; behavior under conflicting writes is undefined. **Replica set membership is static** — no add/remove dynamics.

**Why:**
- Per-region cost is the user-visible question that justifies the slice. Without per-region accounting, "how much does global tables cost for our workload?" is unanswerable.
- Stochastic lag (vs. fixed lag) matches the project's modeling principle: every variable behavior in this simulator that depends on a workload-shape characteristic is sampled, not fixed. Severe-lag scenarios are realistically achievable with right-tail-heavy distributions (log-normal, Pareto, etc.).
- Lag is a property of the cross-region link, not of items or workloads. Real AWS replication paths are determined by network distance, link infrastructure, and replication-service queue state — none of which depend on what's being replicated. Directional keying captures the asymmetry of real cross-region paths.
- Conflict resolution is rare in well-designed workloads, hard to model meaningfully without per-key state, and orthogonal to cost prediction. Out of scope.
- Static replica set is an AWS control-plane concern (add-replica is a separate API operation), not a per-write concern. Out of scope.

**Implications for downstream design:**
- Lag-sampling logic lives in the replication coordinator, parameterized by the directional link.
- The lag value is in ticks (discretized from continuous distribution samples — `max(0, floor(sample))` or similar).
- Lagged writes need a queue / scheduler in the coordinator that holds them until their `applyTick`.
- Per-region consumption events flow up to the public component tagged with their region.

**Status:** Locked.

## Decision 3: Replicated write arrival at saturated destination region

**Decision:** Replicated writes at the destination region apply unconditionally — they bypass the destination's `TableAdmissionStage` and land directly in `TableStorageStage` as already-admitted samples. They accrue normal destination-region WCU consumption events, region-tagged. When the destination region's per-tick admission state is already over its configured WCU limit at the moment a replicated write arrives, the simulator emits a new "replicated-write-would-have-been-throttled" metric event alongside the apply. No retries, no drops.

**Why:**
- Smallest delta from existing machinery: regional tables already have admission and storage; replicated writes just skip admission. No new retry/requeue logic — and the simulator doesn't have retry semantics anywhere else, so introducing them only for replicated writes would be an asymmetric design.
- Saturation is still observable to users via the new metric event. Combined with right-tail-heavy lag distributions, severe-lag scenarios produce visible saturation clusters that users can study.
- Per-region cost accounting works correctly: replicated writes contribute WCU consumption to the destination region's totals, which flow through the existing usage/pricing layers.

**Known deferred item — rWCU as a separate capacity bucket:**
Real AWS bills replicated writes against a distinct **replicated-WCU (rWCU)** bucket on the destination region, with its own pricing. Slice 10 lumps replicated-write consumption into normal WCU (region-tagged) for simplicity. A future slice — required before the project can declare "on-demand simulation is accurate and realistic" — must split rWCU out as a separate consumption target/category and surface its distinct pricing. Tracked under "Deferred follow-on work" at the end of this document.

**Why not modeling admission for replicated writes (option B):** Retry semantics would be a project-wide concern, not a slice-10 concern. Out of scope.

**Why not separate rWCU capacity bucket now (option C):** The separate bucket adds an admission dimension whose value depends on rWCU pricing being separately modeled. Easier to add as a follow-on once cost-modeling refinements are warranted.

**Status:** Locked.

## Decision 4: Region-awareness does not propagate through the pipeline

**Decision:** Region-awareness does **not** propagate through the downstream pipeline. Each region runs its own logically-independent `DynamoDbTable + DynamoDbUsageTotals + DynamoDbPricing` chain, identical in shape to a single-region simulation, with its own per-region pricing config. No new fields are added to consumption events, usage totals, or pricing types.

Cross-region replication is handled by a dedicated **replication coordinator** stage that subscribes to each region's outbound stream-of-changes events (likely repurposing the slice-9 validated-admitted-sample output), applies per-link stochastic lag (per decision 2), and routes lagged writes into the destination regions as already-validated admitted samples (per decision 3). Cross-region traffic is carried by inter-region data streams owned by the coordinator.

Cross-region transfer cost (when modeled) is a separate consumption stream emitted by the coordinator, not attributed to any single region's pipeline.

Aggregation of per-region cost data into a grand total is a concern of the simulator's caller (or demo layer), **not** of the simulator itself. Each per-region pipeline outputs its own totals/cost; combining them is downstream-of-simulator work.

Implementation runs all regions in a **single Pekko materialization** with logical sub-graph independence (per-region sub-graphs are wired identically to a single-region table; only the coordinator stage knows about multiple regions). Promoting to separate materializations is left as a future option if needed.

**Why:**
- Mirrors AWS's actual architecture more faithfully — each region is genuinely independent in real life, with replication being a separate cross-region service.
- Smallest blast radius: no changes to existing consumption events, usage totals, or pricing types. Slice 1–9 machinery inside each region works unchanged.
- Per-region pricing differences (e.g., `us-east-1` vs `eu-west-1`) are handled by configuring different `DynamoDbPricing.Config` instances per region — no new types needed.
- Demo (JSONL/Postgres/Grafana) stays untouched for slice 10. A multi-region demo can be a separate slice 10b or phase-4 deliverable.
- The "grand total invoice" question is answered by the caller summing per-region results — not the simulator's job.

**Status:** Locked.

## Decision 5: Cross-region data transfer as a generic AWS-platform component

**Decision:** Slice 10 models cross-region data transfer as a separate consumption + pricing stream, implemented as a **generic, AWS-service-agnostic** Pekko component in a new `stochastacy.aws.transfer` package — not as a DynamoDB-specific feature.

The package contains:
- **`CrossRegionTransferEvent(sourceRegion: String, destinationRegion: String, bytes: Long, sourceService: String, eventTime: SimTime, usecase: Any)`** — the generic consumption event. The `sourceService` tag (e.g., `"DynamoDB"`, `"S3"`, `"RDS"`) is **explicitly included from slice 10 onward** so future cost reports can break down transfer cost by which service caused it. With one producer in slice 10, this tag is forward-compatible at zero present cost.
- **`CrossRegionTransferUsageTotals`** — Pekko aggregator that sums bytes by directional `(source, dest)` pair, with optional per-service breakdown. Structurally parallel to `DynamoDbUsageTotals`.
- **`CrossRegionTransferPricing`** — Pekko pricing component with `Map[String /* sourceRegion */, BigDecimal /* dollarsPerGB */]` config; flat per-source-region per-GB rate for slice 10. Output: a cost stream.

The DynamoDB replication coordinator (slice 10's contribution) is the first producer: one `CrossRegionTransferEvent` per replicated write, tagged `sourceService = "DynamoDB"`. The DDB pipeline does not know or care about transfer; the transfer pipeline does not know or care about DDB. Future cross-region producers (S3 CRR, RDS read replicas, Lambda cross-region invocations, etc.) emit the same event type and feed into the same totals + pricing pipeline. No code duplication; one canonical cost path.

Per-region cost totals do **not** include cross-region transfer cost. Total cost = `sum(per-region-cost) + cross-region-transfer-cost` — the caller aggregates the two streams (consistent with decision 4's "caller aggregates" principle).

**Why:**
- AWS bills replicated WCU and cross-region data transfer **separately** (verified against the AWS DynamoDB pricing page). Both must be modeled to predict global-tables cost honestly.
- Cross-region transfer is an AWS-platform concept, not a DynamoDB concept. A generic, reusable component pays off the moment a second producer is added; getting the schema right while there's one producer is the cheap path.
- The `sourceService` tag is essentially free in slice 10 and prevents an awkward schema change later.

**Status:** Locked.

## Decision 6: Public API surface — `DynamoDbGlobalTable.componentOf` factory

**Decision:** Slice 10 introduces a new public component `DynamoDbGlobalTable.componentOf(config)` parallel to `DynamoDbTable.componentOf`. The factory accepts a `DynamoDbGlobalTable.Config` containing:

- per-region `DynamoDbTable.Config`s (one per replica region)
- a `ReplicationModel` with per-directional-link lag distributions (from decision 2)
- a `CrossRegionTransferPricing.Config` for the transfer-cost pipeline (from decision 5)

Internally the factory wires N regional `DynamoDbTable.componentOf` graphs + a `ReplicationCoordinator` stage + the `stochastacy.aws.transfer` pipeline (transfer events → totals → pricing) into one cohesive graph. The returned graph exposes:

- N per-region request input ports (one per replica)
- N per-region response/consumption/metric output streams (per decision 4 — per-region pipelines remain distinct at the port level)
- One cross-region transfer cost output stream (from the generic transfer pipeline)

Because the port count is `2N+1` outputs and N inputs (variable in N), the public component will use a custom `Shape` that names per-region ports explicitly rather than a `FanOutShapeK` for fixed K.

**Why:**
- Matches the precedent set by `DynamoDbTable.componentOf`: complex internal wiring, simple public surface.
- Per-region distinctness preserved at the port level, consistent with decision 4 — users still see per-region totals separately; aggregation across regions is the caller's concern.
- A single factory call replaces a brittle "wire 3 regional tables + a coordinator + a transfer pipeline by hand" assembly recipe. The wiring rules are non-trivial; encapsulating them in the factory is the cheap-and-correct path.

**Status:** Locked.

## Decision 7: Replicated-write input mechanism

**Decision:** Add a separate factory variant **`DynamoDbTable.componentOfReplicated(config)`**. This new factory produces a graph whose shape extends `componentOf`'s shape with an **additional input port** for inbound replicated writes. Replicated writes injected on that port bypass `TableAdmissionStage` entirely and land directly in `TableStorageStage` as already-validated admitted samples (per decision 3).

The existing `DynamoDbTable.componentOf` is **unchanged** — its shape and behavior are preserved exactly so single-region callers and all slice 1–9 tests continue to work without modification. Only `DynamoDbGlobalTable.componentOf` (and any future replication-aware factory) calls `componentOfReplicated`.

**Why:**
- Zero breakage of slice 1–9's public API and tests. `DynamoDbTable.componentOf` keeps its shape; existing materializations don't need to add a no-op replicated-input source.
- Keeps the "replication-aware" surface area opt-in. Most simulator users (single-region scenarios) never see the extra port.
- Implementation is mostly a thin wrapper: `componentOfReplicated` reuses 95% of `componentOf`'s internal wiring and adds a Merge stage that combines admitted-sample output from admission with the new replicated-input port before feeding the storage stage. Mechanical.

**Status:** Locked.

## Decision 8: Outbound replication output port

**Decision:** `DynamoDbTable.componentOfReplicated` has a **dedicated output port** for outbound stream-of-changes events used by the replication coordinator. This is separate from slice 9's `out3` (validated admitted samples that feed the index-maintenance graph).

The dedicated port emits a stream of "this region successfully applied this write — replicate it" events, one per validated write, carrying everything the coordinator needs to fan it out to peer regions (the underlying admitted sample, plus origin-region metadata for downstream tagging).

The existing `componentOf`'s shape gains nothing from this port (single-region tables have no replication), so the dedicated outbound port lives only on `componentOfReplicated`'s shape.

**Why:**
- Slice 9's `out3` was designed for the index-maintenance graph, which has its own consumption semantics (downstream of validated writes, applies physical index effects). Repurposing it for replication conflates two distinct concerns: "feed the local index-maintenance graph" vs. "fan out to remote replicas." Keeping them separate makes the wiring explicit.
- Future evolution may want different filtering or transformations between the two streams (e.g., what if some indexes shouldn't replicate? unlikely today, but possible). Distinct ports preserve that option.
- The cost is one extra Broadcast inside `componentOfReplicated` to fork the validated-sample stream into both ports. Negligible.

**Status:** Locked.

## Decision 9: Test strategy

**Decision:** Slice 10 ships with a **rich test plan** even at the cost of significantly increasing test count. The plan covers:

- **Unit tests for `ReplicationCoordinator`**: per-link lag sampling determinism, fan-out routing correctness (1 origin → N-1 destinations), tick-boundary lag queue behavior, edge cases (zero lag, degenerate distributions, single-region "global" tables).
- **Unit tests for `stochastacy.aws.transfer` components**: `CrossRegionTransferUsageTotals` aggregation by directional pair and by `sourceService`; `CrossRegionTransferPricing` correctness with various rate configs.
- **Unit tests for `DynamoDbTable.componentOfReplicated`**: replicated-write input bypasses admission and lands in storage; dedicated outbound port emits exactly the validated writes; existing slice 1–9 behaviors unchanged when no replicated input is provided.
- **Integration tests for `DynamoDbGlobalTable.componentOf`**:
  - Write accepted in region A propagates to regions B and C after sampled lag, applies in their storage, accrues their consumption.
  - Per-region pricing differences produce expected cost split.
  - Cross-region transfer events emitted with correct directional pairs and `sourceService = "DynamoDB"`.
  - Severe-lag scenarios (heavy-tail distributions) cluster replicated writes and trigger the would-be-throttled metric.
  - Multi-region fan-out with different per-link distributions.
  - Static replica set: writes only flow between configured replicas.

**Why:**
- Slice 10 introduces a new architectural dimension (cross-region behavior) that prior slices didn't touch. Without thorough tests, regressions in this area would be invisible — there's no other slice exercising it.
- The new components (`ReplicationCoordinator`, transfer pipeline) deserve unit-level coverage before they're composed into the global-table integration tests.
- The user-facing factory (`DynamoDbGlobalTable.componentOf`) is the public contract; integration tests on it lock the behavior we're claiming to deliver.

**Status:** Locked.

## Decision 10: Demo target — slice 10 stays internal-only

**Decision:** Slice 10 ships with **no changes to the runnable order-tracking phase-2 demo, the JSONL/Postgres/Grafana stack, the demo runner, or the dashboards.** The phase-2 demo continues to work exactly as today, single-region.

A multi-region demo (Postgres schema with region columns, Grafana panels for per-region cost breakdown, demo runner extension to materialize multi-region scenarios) is left as a separate slice — slice 10b or a phase-4 deliverable — to keep slice 10's scope narrowed to simulator-internal correctness.

**Why:**
- Phase 3 has so far been entirely about simulator internals (slices 1–9 added no demo-surface work). Slice 10 maintaining that pattern preserves momentum and avoids bundling internal-correctness work with UI/reporting work that has its own design considerations (dashboard layout, schema migration, runner CLI shape).
- Per-region cost data is already accessible to any caller via the `DynamoDbGlobalTable.componentOf` outputs (per decision 4 + decision 6). A user who wants multi-region cost reporting today can build it on top of the slice-10 simulator core; they don't need a demo update to get answers.
- Splitting demo work into its own slice preserves the option of doing it well rather than rushing it.

**Status:** Locked.

## Deferred follow-on work

These items are out of scope for slice 10 but are explicitly tracked because they will be needed before the simulator can claim full on-demand-mode fidelity for global tables:

- **rWCU as a distinct capacity bucket and pricing dimension.** Replicated writes at a destination region currently bill against normal WCU; AWS bills them as rWCU with separate pricing. A future slice must split this out.
- **Tiered cross-region transfer pricing.** Real AWS transfer pricing has tiered rates ("first 10 TB/month at rate X, next 40 TB/month at rate Y, etc."). Slice 10 uses a flat per-source-region per-GB rate. A future slice should add tiered pricing, which requires the pricing component to track a billing-period bucket of cumulative GB.
- **Multi-region runnable demo (slice 10b or phase 4).** Slice 10 ships internal-only; a multi-region order-tracking demo with per-region cost panels in Grafana, a Postgres schema migration adding region columns, demo-runner extensions to materialize multi-region scenarios, and a usable user workflow for "spin up a 3-region simulation and see per-region cost" is its own slice.
- (more items will accumulate here as decisions surface them)
