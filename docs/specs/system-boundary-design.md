# System Boundary Component — Design

> Status: **design / not yet implemented.** Captured 2026-07-06. This document records the
> motivation, the behavioral features we intend to include, the reuse targets, and the design
> decisions still open. It is the design anchor for the remaining work in Phase 8.

Working name: **`SystemBoundaryStage`**, in a new generic package `stochastacy.aws.boundary`
(service-agnostic, mirroring how `stochastacy.aws.transfer` is generic across AWS services).
The name and package are provisional — see [Open Design Decisions](#open-design-decisions).

---

## Motivation

Every call in an AWS architecture crosses an interprocess boundary: client → service,
service → service, Lambda → DynamoDB, cross-AZ, cross-VPC, through a VPC endpoint or NAT
gateway. Those boundaries impose **transport constraints** — finite throughput, propagation
latency, transient loss, concurrency limits — and, on the billed direction, **data-transfer
cost**. Today stochastacy models neither: requests hop from the workload/SDK layer straight
into `DynamoDbTable` as if the wire between them were infinite-bandwidth and zero-latency.

A `SystemBoundaryStage` is the reusable component that models the wire. In the EAS demo it sits
between `SdkClientStage` and `DynamoDbTable` and imposes a per-tick throughput ceiling; the same
component (directly or via subtypes) models cross-region, cross-VPC, and cross-AZ boundaries in
other architectures.

Four reasons this is a well-carved abstraction, not an EAS artifact:

1. **It recurs everywhere.** Every edge in every model crosses one of these. The second and
   third use cases (cross-region, cross-VPC/AZ) are already on the table, not hypothetical.
2. **It is orthogonal to the throttling we already model.** DynamoDB throttling is capacity-unit
   exhaustion *at the service* (per-partition, GSI back-pressure, burst reservoirs). A boundary
   limit is bytes/requests-per-tick *on the wire*. Either can bind independently. As long as we
   keep the delineation crisp (see [Relationship to existing models](#relationship-to-existing-models)),
   there is no double-counting.
3. **It composes with `SdkClientStage` into an emergent failure mode.** If the boundary drops or
   errors an over-budget crossing, that drop surfaces as a timeout / connection error — exactly
   the retryable response that feeds the SDK stage's retry loop. Composing the two yields
   network-saturation → timeouts → retries → more load → more saturation: a real cascading-failure
   dynamic that neither component contains on its own. This composition is the strongest signal the
   joint is in the right place.
4. **We already built the hard parts.** The delay-scheduling machinery — parking events into
   future-tick buckets, draining them when the window opens, honoring the timed-event protocol —
   is exactly what `SdkClientStage`'s `delayBuckets` / window rule already does (and the
   protocol-violation trap that hung the graph in Slice D is now understood). A boundary is a
   *simpler* consumer of that pattern. Likewise the burst-reservoir model already exists in
   `TableAdmissionStage`, and the transfer consumption/pricing pipeline already exists in
   `stochastacy.aws.transfer`.

---

## Scope and reuse targets

The component is designed generically and validated concretely. Reuse targets, in priority order:

| Target | How it uses the component | Delivered in Phase 8? |
|--------|---------------------------|-----------------------|
| **EAS demo** | Insert one boundary between `SdkClientStage` and `DynamoDbTable`; per-tick throughput ceiling; over-budget crossings become timeouts that feed the SDK retry loop. | **Yes — required for phase completion.** |
| **Cross-region transfer demo** (thermostat-fleet `--mode multi-region`) | Retrofit the cross-region path so the boundary (or a subtype) meters and prices bytes crossing region links, emitting the existing `CrossRegionTransferEvent`. Replaces / subsumes ad-hoc transfer metering. | **Yes — required for phase completion.** |
| **Cross-VPC boundary** | Subtype / configuration modeling a VPC-peering or Transit-Gateway hop (latency + per-GB processing cost). | Enabled, not necessarily demoed. |
| **Cross-AZ boundary** | Subtype / configuration modeling intra-region cross-AZ transfer (low latency, symmetric per-GB charge both directions). | Enabled, not necessarily demoed. |

**Design generically, validate against EAS first.** We will not add a feature that the EAS (and
cross-region) retrofits cannot actually exercise. Additional boundary flavors are configuration or
thin subtypes over the same core.

---

## Behavioral feature catalog

Throughput is the seed feature; the rest are what make a boundary realistic. "v1" = intended for
the initial implementation that the Phase-8 retrofits require; "later" = enabled by the design but
deferred until a use case needs it.

| # | Feature | v1? | Why it's real | Fits existing pattern |
|---|---------|-----|---------------|-----------------------|
| 1 | **Per-tick throughput ceiling** (bytes and/or requests per tick) | **v1** | EC2/ENI baseline & burst bandwidth; the seed constraint. | Delay-into-future-tick buckets, as in `SdkClientStage`. |
| 2 | **Base transport latency, drawn from a distribution** | **v1** | Even an idle link has propagation delay, and it varies; `LogNormalSampler` gives realistic fat-tailed p99. Nearly as fundamental as throughput. | Distribution samplers (Apache Commons Statistics); `latencyMs` timing already exists in `TableStorageStage`. |
| 3 | **Loss / transient-error rate** (`Bernoulli(p)` → timeout/reset) | **v1** | Makes the SDK-retry composition meaningful *without* needing saturation — retry storms from flaky links, not just overloaded ones. | `BernoulliSampler`; error responses feed `SdkClientStage.in1`. |
| 4 | **Directional asymmetry (ingress vs. egress)** | **v1** | Limits differ by direction, and AWS bills **egress**. For DynamoDB the response direction (query results) is both the larger payload and the priced one — a request-path-only boundary misses the dominant direction on both perf and cost. | Ties to `CrossRegionTransferPricing` (source-region outbound rate). Drives the bidirectional-topology decision below. |
| 5 | **Concurrency (max in-flight) limit** | later | Distinct from throughput: connection-pool size, Lambda concurrency, and AWS security-group **connection-tracking** exhaustion (a real outage cause). Coupled to latency by Little's Law. | New bounded counter; over-limit → queue or reject. |
| 6 | **Burst-credit throughput model** (baseline + refilling reservoir) | later | AWS network is often burstable (t-family, gp3-style credits). Makes the ceiling behave like real burstable network, not a hard wall. | Burst-reservoir logic already in `TableAdmissionStage`. |
| 7 | **Data-transfer cost metering** (emit transfer consumption events) | **v1 (for cross-region retrofit)** | The boundary is the natural, single place bytes-crossing-a-boundary get metered and priced (data-transfer-out, cross-AZ, NAT processing). | Emit `CrossRegionTransferEvent` (or a generalized `DataTransferEvent`) into the existing usage/pricing pipeline. |

Explicitly **out of scope** (too fine-grained for a stochastic-summary model): per-request MTU /
framing overhead, packet-level head-of-line blocking, ordering guarantees.

---

## Composition with `SdkClientStage`

The intended EAS topology after retrofit:

```
WorkloadGraph.requestOut ─▶ SdkClientStage.in0
                            SdkClientStage.out ─▶ SystemBoundaryStage(request dir) ─▶ DynamoDbTable.in
DynamoDbTable.out ─▶ SystemBoundaryStage(response dir) ─▶ (broadcast) ─▶ SdkClientStage.in1
                                                                       └▶ WorkloadGraph.responseIn
                                                                       └▶ throttle/telemetry sinks
```

When the boundary drops or errors an over-budget crossing it must synthesize a timeout/error
**response** onto the response path (not silently discard the request). That response is retryable,
so it re-enters `SdkClientStage.in1` and drives a retry — closing the saturation feedback loop.
This is the behavior that makes "throttles beget throttles" extend to "the network begets
throttles."

---

## Relationship to existing models (anti-double-counting)

The boundary must not silently re-charge or re-delay things other stages already model:

- **Latency.** `DynamoDbTable`/`TableStorageStage` already sample *service processing* latency, and
  `SdkClientStage` schedules *retry backoff*. Boundary latency is *transport* latency — a third,
  distinct term. We need crisp semantics so total end-to-end latency stays physical and
  attributable. This is the discipline most likely to bite; it must be nailed at design time.
- **Throttling.** Boundary throughput limiting ≠ DynamoDB capacity throttling. Both may fire; they
  are different constraints and are allowed to co-occur. Metrics must distinguish them.
- **Transfer cost.** If the boundary emits transfer consumption events, the cross-region retrofit
  must **replace** the current ad-hoc metering rather than add a second source, or costs will
  double. This is the crux of the cross-region retrofit.

---

## State and boundedness guarantees

The component obeys the project's core principle: **bounded cardinality** — memory footprint does
not grow with request volume or simulation length. (Value-accumulators whose numeric value grows —
the transfer-cost byte totals — are fine and expected; they are a fixed set of scalars, exactly like
the existing `DynamoDbUsageTotals` byte-tick accumulators.)

**Complete inventory of persistent state**, each verified bounded:

| State | Bound |
|-------|-------|
| Per-direction throughput counters (ingress/egress bytes-this-tick) | Two `Long`s, reset at each tick boundary. Fixed. |
| Per-direction delay queues (over-budget elements awaiting future budget) | One FIFO per direction, **hard-capped** depth/bytes, drop-on-full. Bounded by construction. |
| Parked synthetic-timeout set (request-side drops awaiting their response-side window) | **Hard-capped**, drop-oldest on full. See note below. |
| In-flight / connection counter (feature 5, later) | Single `Long`, `+1` on `requestOut`, `−1` on `responseIn`. Balances by arithmetic — **no correlation map** (every request reaching the table yields exactly one response on `responseIn`; request-side drops never incremented). |
| Transfer-cost accumulators | Running byte sums; cardinality bounded by topology (region-pairs × direction), not by volume. Value grows, footprint fixed. |
| Latency / loss sampler state | Stateless (`LogNormalSampler`, `BernoulliSampler`). |
| Window/tick tracking (`lastForwardedTick` per outlet, `pendingTick`, current windows) | Fixed handful of `Long`s. |
| Output emit queues (ready-to-push buffers) | Bounded by one tick's worth of elements between downstream pulls; a real sink always pulls, so they drain each tick. |

**The three queues are hard-capped by construction, not merely bounded by a well-behaved workload.**
This is the key hardening decision: the two per-direction delay queues *and* the parked
synthetic-timeout set all have hard caps with a defined drop policy on overflow. A dropped parked
timeout simply means that one request's retry never fires and it silently dies — the same degradation
already accepted for undrained retries at end-of-stream in `SdkClientStage`. Without the cap on the
parked-timeout set, its standing size would be (drops/tick) × (request→response lag) — bounded only
*statistically* (because the workload is well-behaved and `maxAttempts` caps retry chains). The cap
makes it structural.

**Edge cases walked:**

- **Sustained saturation / retry storm** (EAS worst case): delay queues pinned at cap, parked
  timeouts pinned at cap, accumulators grow in value only. Retry amplification is itself bounded —
  `SdkClientStage` stops generating retries at `clientAttempt + 1 >= maxAttempts`, so each original
  request spawns at most a fixed number of drops-and-retries before terminating.
- **Throughput = 0 / loss = 100%**: every request drops → one timeout → one retry → drops again, up
  to `maxAttempts`, then dead. Fixed multiplier per original arrival; no cross-tick growth.
- **Response stream stalls / large lag**: parked-timeout population would grow with lag — exactly why
  the hard cap on that set is required.
- **`EndOfTime` with backlog**: on ingress `EndOfTime`, clear the ingress delay queue (drop) and stop
  generating request-side timeouts; on egress `EndOfTime`, flush-or-drop parked timeouts, then emit
  `EndOfTime` **last** so it stays the terminal sentinel. Nothing survives teardown.
- **Idle tick (zero arrivals)**: budgets reset, queues drain, nothing accumulates.

The only unbounded-in-principle *input* — per-tick arrival count — is never accumulated across ticks:
each tick's batch is processed to pass / capped-queue / drop within that tick, leaving only capped
residue. No structure is keyed by request, by (region-pair × key), or by anything that scales with
volume or time.

---

## Element-type abstraction (protocol seam)

The stage is generic over the element type via **two type parameters** `Req` / `Resp` and a small
protocol type class. This follows through on the generic-package positioning (`stochastacy.aws.boundary`)
without gold-plating: the stage only touches the element type in three places, all of which factor
into a minimal interface.

```scala
trait BoundaryProtocol[Req, Resp]:
  def requestBytes(req: Req): Long                                          // throughput + transfer-cost sizing
  def responseBytes(resp: Resp): Long                                       // throughput + transfer-cost sizing
  def timeoutResponse(req: Req, eventTime: SimTime, intraTick: Double): Resp // synthesize a retryable timeout for a dropped request
  def withTiming(req: Req, eventTime: SimTime, intraTick: Double): Req       // restamp a delayed request into a new window
```

The stage is `SystemBoundaryStage[Req, Resp](config, protocol, rng)` over a Pekko
`BidiShape[TimedElement[Req], TimedElement[Req], TimedElement[Resp], TimedElement[Resp]]`.

Design notes:

- **Control events are already generic.** `Tick` / `EndOfTime` live in `stochastacy.sim`, so the
  stage handles the timed-event protocol with no protocol-instance help. The protocol seam covers
  only the domain payloads.
- **Two type parameters, not one.** A request-side drop consumes a `Req` and emits a `Resp` (the
  synthetic timeout) on the response outlet — the types genuinely cross. `DynamoDBRequest` and
  `DynamoDBResponse` share no round-trippable supertype, and unifying them is a far bigger change than
  this seam. The four independently-typed `BidiShape` ports carry the crossing cleanly.
- **Response-side drops need no extra method.** The real response is in hand and already carries
  `originalRequest: Option[DynamoDBRequest]`, so the replacement timeout is built from the carried
  request via the same `timeoutResponse`.
- **Byte-sizing is required regardless of the cap unit**, because cost metering (decision #3) needs
  bytes. This makes the seam mandatory even if we never add a second service, and it firms up the
  lean toward **byte-based** queue caps (one sizing function serves both throughput limiting and cost).
- **The timeout must be retryable.** `timeoutResponse` must return a response for which the active
  `SdkRetryStrategy.retryable` is `true`, or the drop won't drive a retry and the cascade breaks.
  Recommendation: a **dedicated `BoundaryTimeoutResponse`** added to `AwsDefaultRetryable`, rather than
  reusing `SystemErrorResponse` — it keeps boundary-induced failures distinguishable from
  service-induced `SystemError`s in telemetry, supporting the "distinguish drop kinds" requirement.
- **Ship DynamoDB-only.** Design against the seam from day one, but provide a single canonical
  `given DynamoDbBoundaryProtocol extends BoundaryProtocol[DynamoDBRequest, DynamoDBResponse]` in the
  DynamoDB layer (not the generic `boundary` package). Future services (S3 CRR, RDS, …) each add one
  more `given`, exactly as they'd each add a `sourceService` tag to the transfer package. **Do not**
  build a second concrete protocol now to "validate" the abstraction — that is speculative until a
  non-DynamoDB service actually needs a boundary. The seam's value is the clean split between protocol
  mechanics and boundary logic; one exercised instance is enough.

The DynamoDB instance is feasible today: responses carry `originalRequest`, byte-bearing fields exist
(`itemBytes`, `returnedBytes`), and `ThrottledResponse` / `SystemErrorResponse` are already retryable
(so the pattern for a retryable `BoundaryTimeoutResponse` is established).

---

## Open design decisions

These are the decisions to settle before/at implementation. None are blockers; each is a lane to
pick.

1. **Over-limit policy: bounded queue vs. drop-as-timeout vs. back-pressure.** *(Still open — the
   next to lock.)*
   Lean: a **bounded** queue (respects the no-unbounded-state principle) that **drops-as-timeout when
   full** — bounded *and* composes with SDK retries. Pure Pekko back-pressure does not map cleanly
   onto simulated time, so we avoid relying on it as the model. Locked so far via the boundedness
   analysis: **all three queues (two per-direction delay queues + the parked synthetic-timeout set)
   are hard-capped with a defined drop-on-full policy.** A separate **loss coin flip** (feature 3)
   drops independently of budget. Still to settle: the actual cap values / units (depth vs. bytes),
   whether over-budget delays before dropping or drops immediately, and drop-oldest vs. drop-incoming.

2. **Topology: one stage or two (bidirectional).** *(RESOLVED.)*
   **Option C: a single bidirectional component** — a custom `GraphStage` over a `BidiShape`
   (`requestIn` / `requestOut` / `responseIn` / `responseOut`) with shared state. Throughput is
   modeled as **two independent per-direction budgets** inside it (full-duplex fidelity), while the
   shared concerns (in-flight/connection count, drop→timeout synthesis, cost metering) live in the
   shared state. Request-path-only (A) was rejected — it misses the dominant, billed direction
   (responses) and cannot synthesize timeout responses. Two independent stages (B) was rejected —
   timeout synthesis needs the response outlet, and bidirectional concurrency/connection state can't
   live in either independent stage.
   **Both drop sides are supported**, and they fall out of the per-direction design rather than being
   a bolt-on: ingress saturation/loss → **request-side drop** (request never reaches the table — no
   service capacity consumed, no state change); egress saturation/loss → **response-side drop**
   (table did the work — capacity consumed, state mutated — but the result is lost, so the retry
   causes duplicate work / double-writes). Neither side needs per-request correlation state: on a
   request-side drop the request is in hand to build the timeout (`originalRequest = Some(req)`); on a
   response-side drop the real response is in hand to drop-and-replace. Telemetry **must distinguish
   the two drop kinds** (never-reached vs. done-but-lost) — their cost signatures differ and that
   difference is a primary teaching point.

3. **Cost-metering scope.**
   Does the component *emit* transfer consumption events (unifying with `CrossRegionTransferPricing`)
   or stay purely performance and leave cost elsewhere? Lean: **emit**, because it makes the boundary
   the single metering point — but that widens its remit and forces the "replace, don't add" care in
   the cross-region retrofit.

4. **State boundedness.**
   Consistent with the project's core principle: no per-request maps, bounded delay buckets
   (≤ `ceil(maxLatency / tickDuration)` + queue cap), summary counters only. Any queued-request
   representation must be bounded and, ideally, stochastic-summary rather than a literal element
   buffer where volume is high.

5. **Sub-tick granularity.**
   Per-tick throughput at 1-second ticks is a per-second average; microburst behavior is lost. This
   is consistent with the already-noted "sub-second tick resolution" future-work item and is
   accepted for v1.

6. **Naming and packaging.**
   `SystemBoundaryStage` / `stochastacy.aws.boundary` are provisional. Alternatives considered:
   `TransportBoundary`, `NetworkLink`, `Interconnect`, `Channel`. "System boundary" best captures the
   general interprocess framing (not only network — a future message-queue boundary would have very
   different semantics: durability, ordering, at-least-once). Decide whether v1 commits to
   *network-transport* semantics specifically or keeps the door open for non-network boundaries.

7. **Configuration surface & subtype strategy.**
   Whether cross-VPC / cross-AZ / cross-region are configurations of one component or thin subtypes.
   Following the topology resolution, the config is **per-direction** (there is *no* `dropSide` enum):
   ingress throughput / egress throughput, ingress loss prob / egress loss prob, ingress/egress
   latency distribution, plus the shared cost rate / rate source. A "request-side-failure" boundary is
   one configured with ingress limits and no egress limits; a "flaky-return-path" boundary is the
   mirror; cross-VPC / cross-AZ are different fillings-in of the same per-direction knobs. Still to
   settle: exact `Config` shape and whether the boundary flavors are subtypes or just presets.

---

## Phase 8 completion definition

Phase 8 is complete when **all** of the following hold:

1. **Component implemented.** `SystemBoundaryStage` exists with v1 features (throughput ceiling,
   distribution-drawn transport latency, loss/error rate, directional asymmetry, transfer-cost
   emission), bounded state, and unit tests, with the open decisions above resolved and recorded.
2. **EAS demo retrofitted.** The EAS demo uses `SystemBoundaryStage` between `SdkClientStage` and
   `DynamoDbTable`; over-budget crossings surface as timeouts that drive SDK retries; the demo still
   runs end-to-end (generate → stage → view) and the boundary's effect is observable in metrics.
3. **Cross-region transfer demo retrofitted.** The thermostat-fleet multi-region path uses the
   component (or a subtype) to meter and price cross-region transfer, emitting
   `CrossRegionTransferEvent`, with the previous ad-hoc metering **replaced** (no double-counting),
   and cross-region costs unchanged in order of magnitude versus the pre-retrofit baseline.
4. **Full suite green** and both retrofitted demos visually verified.

Enabling (not requiring) cross-VPC and cross-AZ modeling via configuration/subtypes is part of the
component's design remit but does not gate phase completion.

---

## Prior Phase-8 work (context)

Phase 8's SDK-client side-quest is already complete and is the foundation this component builds on:
`SdkRetryStrategy` / `JitterStrategy` / `BackoffDistribution` and `SdkClientStage` (retry client
graph component), validated 2026-07-05 (commit `db1b2a1`). The delay-scheduling window rule and the
`clientAttempt` attribution field established there are directly reused here. The EAS demo already
exercises the SDK stage; this component slots into that same topology.
