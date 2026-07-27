# Generic Workload Layer

Status: proposed
Motivation: enable out-of-repo projects to define workloads over their own request
types, without forking the arrival protocol.

## Why

The `stochastacy.workload` package is already two things bolted together:

- **Generic machinery** — `Sampler[S, T]`, the distribution samplers, the combinators,
  `TemporalShapeFunctions`, `RandomBurstSampler`, `ErasedSampler`. Zero AWS imports.
  Reusable as-is.
- **DynamoDB-bound arrival machinery** — `RequestShape`, `RequestShapeDefinition`,
  `WorkloadDefinition`, `WorkloadRequestStream`. Hard-typed to `DynamoDBRequest`.

An out-of-repo consumer cannot add cases to a sealed `RequestShape`, so today the only
way to model a non-DynamoDB workload is to reimplement the arrival protocol. That
protocol is not trivial — `Tick` framing, the `EndOfTime` terminal sentinel, the
three-independent-RNG discipline, and the intra-tick arrival draw are all invariants
this project documents heavily and would be easy to get subtly wrong in a copy.

This spec makes the arrival layer polymorphic over request type and, in the same pass,
fixes naming that went stale.

## Target vocabulary

Three names, each with one job.

```scala
/** Mints a single request. The unit of "what does this flow emit". */
trait RequestFactory[Req <: TimedEvent]:
  def build(tick: Long, usecase: String, flowId: String,
            rng: UniformRandomProvider, intraTick: Double): Req

/** A factory that also knows its arrival rate. */
final case class RatedRequestFactory[Req <: TimedEvent](
  rate:    StatelessSampler[Int],
  factory: RequestFactory[Req]
) extends RequestFactory[Req]:
  def build(tick: Long, usecase: String, flowId: String,
            rng: UniformRandomProvider, intraTick: Double): Req =
    factory.build(tick, usecase, flowId, rng, intraTick)

/** One named flow within a workload. Replaces `FlowDefinition`. */
sealed trait WorkloadFlow[Req <: TimedEvent]:
  def id: String

object WorkloadFlow:
  final case class Independent[Req <: TimedEvent](
    id: String, factory: RatedRequestFactory[Req]) extends WorkloadFlow[Req]

  final case class FollowOn[Req <: TimedEvent](
    id: String, sourceId: String, sourceFlowId: String, outcome: OutcomeFilter,
    proportion: Double, lagTicks: Int, factory: RequestFactory[Req]) extends WorkloadFlow[Req]

  final case class Retry[Req <: TimedEvent](
    id: String, sourceId: String, sourceFlowId: String,
    proportion: Double, lagTicks: Int) extends WorkloadFlow[Req]
```

### Naming rationale

- `RequestFactory` — it mints requests; "shape" described inert data and stopped fitting
  the moment construction moved onto the type. `Shape` survives where it is still
  accurate: `TemplateShape`, the DSL's parsed form.
- `RatedRequestFactory extends RequestFactory` — the IS-A is real, and the subtype
  relation lines up with a distinction the ADT already makes. `Independent` needs a rate;
  `FollowOn`/`Retry` deliberately do not (their rate derives from source outcomes). So
  `Retry` resolution can copy a source flow's factory uniformly, rated or bare.
- `WorkloadFlow` replaces `FlowDefinition` — `Workload*` is already this package's naming
  convention (`WorkloadDefinition`, `WorkloadGraph`, `WorkloadDsl`, `WorkloadEvaluator`,
  `WorkloadFile`, `WorkloadTemplate`, `WorkloadRequestStream`); `FlowDefinition` was the
  odd one out. The prefix also removes any ambiguity with Pekko's `Flow`, which is in
  scope simultaneously in `WorkloadGraph.scala` and `FollowOnTransformerStage.scala`.

`Retry[Req]` carries a phantom `Req` — it holds no factory, but must fit
`WorkloadFlow[Req]`. Acceptable.

## Scope boundary — what stays DynamoDB-specific

Deliberately **not** genericized in this pass:

| Component | Why |
|---|---|
| `FollowOnTransformerStage` | Typed `Flow[TimedElement[DynamoDBResponse], TimedElement[DynamoDBRequest], NotUsed]`; matches `ThrottledResponse`; reads `resp.flowId`. Genericizing needs an outcome-classifier abstraction over responses — a separate, larger design. |
| `WorkloadGraph` | Same coupling, plus it duplicates the derive/delay-queue logic inside a custom `GraphStage`. |
| `WorkloadDsl`, `WorkloadFile`, `WorkloadTemplate`, `TemplateShape` | All `private[workload]` DynamoDB parsing. They produce `WorkloadDefinition[DynamoDBRequest]` and are otherwise untouched. |
| `ResolvedDerivedFlow` | Pin its factory field to `RequestFactory[DynamoDBRequest]`. |

Consequence for the standalone consumer: **independent flows only**. Derived flows
(`FollowOn`, `Retry`) key off simulator response outcomes and are unavailable without a
simulator. That is the correct near-term limitation — a workload-only model has no
outcomes to observe.

## Slice plan

Each slice compiles and leaves `sbt core/test` and `sbt examples/compile` green on its own.

### Slice 1 — Introduce `RequestFactory`; move construction onto the shapes

No generics, no renames. Highest value, lowest risk.

- Add `trait RequestFactory[Req <: TimedEvent]` (new file `RequestFactory.scala`).
- Make `sealed trait RequestShape extends RequestFactory[DynamoDBRequest]`; give each of
  the eight variants a `build` body lifted verbatim from the corresponding match arm of
  `WorkloadRequestStream.buildRequest`.
- Replace the three call sites with `factory.build(...)`:
  - `WorkloadRequestStream.apply:50`
  - `FollowOnTransformerStage:98`
  - `WorkloadGraph:277`
- Delete `WorkloadRequestStream.buildRequest`.

Note `build` returns `Req`, **not** `TimedElement[Req]` — no branch of the old
`buildRequest` ever produced a control event. The `TimedElement` widening stays in
`WorkloadRequestStream`, which is what interleaves `Tick`/`EndOfTime`.

Verify: no behavioural change intended. Existing tests should pass unedited unless
`WorkloadRequestStreamSpec` calls `buildRequest` directly (it is `private[workload]`, so
it may) — see open question 4.

### Slice 2 — `RequestShapeDefinition` → `RatedRequestFactory`

- Rename the type; add `extends RequestFactory[DynamoDBRequest]` delegating `build` to
  the inner factory.
- Rename its `shape` field to `factory`.
- Update the eight convenience constructors in its companion.
- Update `WorkloadTemplate.bind:46`, `WorkloadDefinition.ofIndependent`, and the demo
  scenario configs (`ThermostatFleetScenarioConfig`, `EasScenarioConfig`).

### Slice 3 — `FlowDefinition` → `WorkloadFlow`

Pure mechanical rename, no semantic change. Touches `WorkloadDefinition.scala`,
`FollowOnTransformerStage.scala`, `WorkloadGraph.scala`, `WorkloadTemplate.scala`,
`WorkloadFile.scala`, `WorkloadDsl.scala`, plus `FollowOnTransformerStageSpec` and
`WorkloadDslSpec`. Also rename `WorkloadDefinition.independentFlows` / `.derivedFlows`
bodies accordingly.

### Slice 4 — Genericize over `Req`

- Add `[Req <: TimedEvent]` to `RatedRequestFactory`, `WorkloadFlow`,
  `WorkloadDefinition`, `WorkloadRequestStream`.
- Pin every DynamoDB-bound consumer to `WorkloadDefinition[DynamoDBRequest]` (see scope
  table). Consider `type DynamoDbWorkload = WorkloadDefinition[DynamoDBRequest]` to keep
  those signatures readable.
- Resolve the `tableName` question (open question 1).

`WorkloadRequestStream.apply` needs no logic change here — its body is already fully
generic apart from the deleted `buildRequest`. Only the signature moves.

### Slice 5 — Relocate the DynamoDB factory namespace

- Rename `object RequestShape` → `DynamoDbRequests` (open question 2), so call sites read
  `DynamoDbRequests.GetItem`, `DynamoDbRequests.PutItem(sampler)`.
- Update demos and tests. Mechanical; the compiler finds every site.

Deliberately last: it is the widest-blast-radius, lowest-risk edit, and doing it after
the semantics have settled avoids redoing it.

### Slice 6 — Publish and verify downstream

- `sbt core/publishM2` → `~/.m2/repository/com/bmaso/stochastacy_3/0.1.0-SNAPSHOT/`.
- In a scratch project, depend on it and define one trivial non-DynamoDB
  `RequestFactory` + `WorkloadDefinition`, run `WorkloadRequestStream`, and assert the
  stream shape: `Tick(1)…Tick(N)`, flush `Tick(N+1)`, `EndOfTime` last, every request
  carrying `intraTick ∈ [0.0, 1.0)`.

This is the actual acceptance test for the whole refactor — if a request type defined
entirely outside the repo flows through the arrival protocol unchanged, the extension
point works.

## Open questions

1. **`WorkloadDefinition.tableName`.** *Inventory done.* `WorkloadRequestStream` uses
   `usecase` but never `tableName`. The only direct readers of the field on a
   `WorkloadDefinition` are two assertions in `OrderTrackingScenarioConfigSpec:43,50`
   (`defn.tableName shouldBe "orders"`); every other `.tableName` hit in the repo is on
   scenario-config or batch-metadata types, unrelated. Dropping it from the generic type
   therefore costs two test edits. Recommend dropping it and letting the DynamoDB layer
   carry the table name, since `WorkloadTemplate.bind` already takes `tableName` as a
   parameter. Decide during slice 4.
2. **DynamoDB factory namespace.** `DynamoDbRequests` is the working proposal. Also
   decide package placement: stay in `stochastacy.workload`, or move to
   `stochastacy.aws.dynamodb.workload` to make the generic/specific split visible in the
   directory tree.
3. **Should `RatedRequestFactory` extend `RequestFactory`?** This spec says yes. The
   alternative is plain composition, which avoids a `build` that ignores `rate` — a mild
   trap for callers. Revisit if the IS-A causes confusion in practice.
4. ~~**Does `WorkloadRequestStreamSpec` call `buildRequest` directly?**~~ *Resolved: no.*
   `grep -rn buildRequest core/src/test/` returns nothing — every caller is in main
   sources. Slice 1 is genuinely test-neutral: if any test changes, the refactor changed
   behaviour and something is wrong.

## Non-goals

- Genericizing derived flows (`FollowOn`/`Retry`) or the response-outcome pipeline.
- A generic YAML DSL. `TemplateShape` stays DynamoDB-specific; revisit only if the
  standalone consumer actually wants YAML.
- Any change to `Sampler` or the combinators. They are already generic and correct.
