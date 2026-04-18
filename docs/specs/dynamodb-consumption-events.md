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

That initial slice has now been completed and extended.

The current implementation also includes:

- `WriteCapacityConsumed`
- `StorageBytesWritten`
- `StorageBytesDelta`
- size-based read and write capacity calculation
- explicit table targets and read-consistency configuration
- a downstream usage aggregation layer

## `GetItem` Semantics

For the initial `GetItem` slice:

- a hit should emit exactly one `ReadCapacityConsumed`
- a hit may also emit one `StorageBytesRead`
- a miss may still emit `ReadCapacityConsumed`
- a miss may emit `StorageBytesRead(bytes = 0)` or omit the byte-read event entirely
- `GetItem` should not emit storage-delta events because it does not mutate storage

Current implementation:

- `GetItem` always emits `ReadCapacityConsumed`
- hits emit `StorageBytesRead`
- misses omit `StorageBytesRead`
- read-capacity units are derived from item-size chunks and read consistency

## `PutItem` Semantics

For the current `PutItem` slice:

- a successful put emits exactly one `WriteCapacityConsumed`
- a successful put emits exactly one `StorageBytesWritten`
- a successful put emits exactly one `StorageBytesDelta`
- the storage delta reflects insert vs overwrite behavior in the mutable summary state
- these events remain raw accounting facts rather than priced totals

## Separation From Pricing

The pricing layer should remain separate from `TableStage4`.

Recommended flow:

1. `TableStage4` emits `DynamoDbConsumptionEvent`
2. a usage aggregation layer folds those events into usage totals
3. pricing translates usage totals into cost estimates

This separation keeps the model easier to evolve and easier to explain.

## Countable Usage Vs Time-Based Usage

Not all DynamoDB cost components should be priced from the same kind of aggregate.

There are two different classes of pricing inputs in this simulator design:

### Countable usage

Examples:

- read capacity consumed
- write capacity consumed
- bytes read
- bytes written

These are additive quantities. Pricing for these can be derived accurately from aggregate usage totals, because only the summed quantity matters.

This is the purpose of the usage aggregation layer:

- raw timed consumption events are emitted by `TableStage4`
- those events are folded into stable usage totals
- pricing for countable consumption can be computed from those totals

### Time-based usage

Examples:

- storage occupancy over time
- future provisioned-capacity charges
- any charge whose meaning depends on how long a quantity remained in effect

These cannot be derived accurately from a single final total alone. For time-based pricing, the simulator must preserve the relationship between:

- a resource change event
- the passage of simulation time
- the next change to that resource

This is one of the key reasons the simulator uses the timed-event source model. Time-based pricing should be derived from timed event streams, or from windowed rollups built from those streams, rather than from a single whole-run total.

## Pricing Input Strategy

The current architectural direction is:

- use aggregate usage totals for countable, additive consumption
- use timed event streams and time progression for duration-based consumption

That means the pricing layer should not assume one totals type can price every kind of DynamoDB cost component.

Instead:

- request-priced usage may be priced from `DynamoDbUsageTotals`
- duration-priced usage should be priced from time-aware rollups or occupancy derived from timed consumption events

This keeps the current totals model useful without forcing it to represent time-dependent billing by itself.

## Open Questions

- How should DynamoDB table and index identities be represented in phase 1 if requests do not yet carry explicit names?
- Should `StorageBytesRead(bytes = 0)` be emitted for misses, or should misses simply omit the byte-read event?
- How should `UpdateItem` and `DeleteItem` map into raw write-side accounting facts?
- When index support arrives, should index write amplification be emitted in the same request-time window or in a later one?

## Recommended Next Step

The next implementation slice should be:

1. add the pricing layer on top of aggregated usage totals
2. extend the consumption model to additional write operations
3. incorporate index-targeted usage into the same aggregation model
