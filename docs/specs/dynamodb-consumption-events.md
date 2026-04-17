# DynamoDB Consumption Events

## Purpose

This note proposes the initial event model for DynamoDB resource-consumption output in `ips/phase1`.

The goal is to let the simulator emit raw, additive facts about physical resource usage so that later layers can:

- aggregate usage
- estimate cost
- explain why a request consumed resources

These events are not billing totals. They are simulation facts that should be stable inputs to later pricing and reporting layers.

## Design Goals

- represent raw resource usage rather than pre-priced cost
- keep events additive and easy to aggregate
- preserve timing by emitting events as timed events
- separate pricing concerns from table execution concerns
- support both tables and indexes
- start small for phase 1, but avoid immediate redesign later

## Relationship To `TableStage4`

`TableStage4` is the storage-facing data-plane component for DynamoDB table execution.

Its outputs should conceptually remain split into:

- responses
- resource-consumption facts
- metric or telemetry facts

The consumption stream is the accounting-facing output. It should carry normalized facts such as capacity units consumed and storage bytes affected.

## What Counts As A Consumption Event

Consumption events are facts about underlying resources used by servicing a request.

Examples:

- read capacity consumed
- write capacity consumed
- bytes read from storage
- bytes written to storage
- bytes deleted from storage
- storage occupancy change

These are distinct from metric events such as:

- request observed
- item returned
- bytes returned to caller
- success or failure counters

Metric events are observability-facing. Consumption events are pricing-facing.

## Proposed Package Location

For phase 1, these events should live under the DynamoDB table simulator package tree.

Recommended package:

- `stochastacy.aws.dynamodb.table`

A future refinement could move them to a DynamoDB-wide package if multiple components need to emit the same event types.

## Proposed Base Types

```scala
sealed trait DynamoDbConsumptionEvent extends ResourceConsumptionEvent:
  def target: DynamoDbTarget

sealed trait DynamoDbTarget

object DynamoDbTarget:
  final case class Table(name: String) extends DynamoDbTarget
  final case class GlobalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbTarget
  final case class LocalSecondaryIndex(tableName: String, indexName: String) extends DynamoDbTarget
```

## Proposed Initial Event Types

```scala
object DynamoDbConsumptionEvent:

  final case class ReadCapacityConsumed(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    units: BigDecimal,
    consistency: ReadConsistency
  ) extends DynamoDbConsumptionEvent

  final case class WriteCapacityConsumed(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    units: BigDecimal
  ) extends DynamoDbConsumptionEvent

  final case class StorageBytesDelta(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    bytesDelta: Long
  ) extends DynamoDbConsumptionEvent

  final case class StorageBytesRead(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    bytes: Long
  ) extends DynamoDbConsumptionEvent

  final case class StorageBytesWritten(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    bytes: Long
  ) extends DynamoDbConsumptionEvent

  final case class StorageBytesDeleted(
    eventTime: SimTime,
    usecase: Any,
    target: DynamoDbTarget,
    bytes: Long
  ) extends DynamoDbConsumptionEvent
```

## Read Consistency

To keep the model explicit and priceable, read-capacity events should record consistency mode.

```scala
enum ReadConsistency:
  case EventuallyConsistent
  case StronglyConsistent
```

## Why These Fields

### `eventTime`

Consumption events must remain part of the timed-event protocol so later aggregation can preserve simulation timing.

### `usecase`

This keeps the events attributable to the originating workload or scenario partition.

### `target`

The target is included now so the model can naturally support:

- table-only accounting
- table plus index accounting
- separate rollups for base table vs index usage

Even if phase 1 initially uses placeholder identities, including the field now avoids a later redesign.

### `units: BigDecimal`

Capacity units should use `BigDecimal` rather than `Long`.

This keeps the model flexible enough for:

- eventually consistent vs strongly consistent reads
- partial-unit modeling if needed later
- refined pricing logic without changing the core event type

### byte-oriented fields

The byte-oriented events support:

- explanation of simulator behavior
- later storage pricing
- validation of state transitions
- clearer demo outputs

## Initial Phase-1 Slice

The full event family above is a reasonable target, but phase 1 should start with the smallest useful subset.

Recommended first slice:

- define `DynamoDbConsumptionEvent`
- define `DynamoDbTarget`
- define `ReadConsistency`
- implement `ReadCapacityConsumed`
- optionally implement `StorageBytesRead`

This is enough to make `GetItem` emit priceable usage facts.

## `GetItem` Semantics

For the initial `GetItem` slice:

- a hit should emit exactly one `ReadCapacityConsumed`
- a hit may also emit one `StorageBytesRead`
- a miss may still emit `ReadCapacityConsumed`
- a miss may emit `StorageBytesRead(bytes = 0)` or omit the byte-read event entirely
- `GetItem` should not emit storage-delta events because it does not mutate storage

## Separation From Pricing

The pricing layer should remain separate from `TableStage4`.

Recommended flow:

1. `TableStage4` emits `DynamoDbConsumptionEvent`
2. aggregation folds those events into usage totals
3. pricing translates usage totals into cost estimates

This separation keeps the model easier to evolve and easier to explain.

## Open Questions

- How should DynamoDB table and index identities be represented in phase 1 if requests do not yet carry explicit names?
- Should `StorageBytesRead(bytes = 0)` be emitted for misses, or should misses simply omit the byte-read event?
- Should capacity consumption modeling begin with a simplified deterministic rule before richer read-consistency behavior is added?

## Recommended Next Step

The next implementation slice should be:

1. define `DynamoDbConsumptionEvent`
2. add `ReadCapacityConsumed`
3. emit it from `TableStage4` for `GetItem`
4. add test helpers that aggregate consumption totals
5. leave pricing as a separate follow-up layer
