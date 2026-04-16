# TableStage4

## Purpose

`TableStage4` models DynamoDB table data-plane execution after higher-level throttling and admission decisions have already been made.

## Graph Contract

`TableStage4` is a Pekko graph component with:

- 1 input stream: timed `DynamoDBRequest` elements
- 3 output streams:
  - timed `DynamoDBResponse` elements
  - timed `ResourceConsumptionEvent` elements
  - timed metric/telemetry elements

All streams use the simulator's timed-event protocol.

## Placement In The Full Table Component

In a future composed DynamoDB `Table` graph:

- upstream stages decide whether the request reaches the data plane
- `TableStage4` decides what storage-level effect the admitted request has
- downstream consumers aggregate responses, costs, and metrics across the whole simulation

If an upstream stage throttles or rejects a request, that response should usually be produced before `TableStage4`, and the request should not be forwarded into `TableStage4`.

## Responsibility Boundary

`TableStage4` is responsible for:

- evaluating request behavior against the current table state
- mutating table state for write-like operations
- generating the logical response for admitted requests
- emitting storage/resource facts caused by the operation
- emitting data-plane metric events

`TableStage4` is not responsible for:

- account-wide quotas
- provisioned throughput admission checks
- burst-capacity scheduling
- retry policy
- client behavior
- orchestration outside the table

## Input Assumptions

The input stream must satisfy the simulator's timed-event source rules.

In particular:

- time advances only by interleaved control events
- request events are logically ordered
- all request events in a given window belong to the currently active logical time

`TableStage4` may rely on upstream stages to provide a valid timed stream.

## Output Guarantees

For every admitted request:

- exactly one synchronous response event must be emitted
- zero or more resource-consumption events may be emitted
- one or more metric/telemetry events may be emitted

Control timing events must be propagated so each output remains a valid timed event stream.

## Request Handling Model

Each supported operation kind is interpreted through a use-case-specific sampler or behavior definition. That behavior may depend on current `TableState`.

For each request, `TableStage4` performs these conceptual steps:

1. Read the current logical table state.
2. Resolve the request's use-case behavior.
3. Sample or compute the operation outcome.
4. Apply any state mutation implied by the outcome.
5. Emit the response event.
6. Emit resource-consumption events.
7. Emit metric events.

These steps are logically atomic with respect to a single input request.

## State Model

`TableStage4` owns the storage-oriented state of the table at this layer. The model should eventually cover facts such as:

- item count
- total item bytes
- item existence / hit-or-miss behavior
- bytes read and written
- bytes deleted
- other physical storage facts needed for cost and metric derivation

The state representation should support both:

- simple deterministic tests using fixed state
- stochastic simulations where behavior depends probabilistically on state

## GetItem Semantics

For `GetItem`, `TableStage4` must support at least these outcomes:

- hit: an item exists and is returned
- miss: no item is returned

Behavioral requirements:

- every `GetItemRequest` must produce exactly one `GetItemResponse`
- the response must distinguish hit from miss, either directly or by attached outcome data
- a hit may emit resource-consumption and metric events reflecting bytes returned and read capacity used
- a miss may still emit resource-consumption and metric events if the model says the lookup consumed resources
- `GetItem` must not mutate table storage state

## Write Semantics

Future write-like operations such as `PutItem`, `UpdateItem`, and `DeleteItem` should:

- produce exactly one synchronous response each
- mutate table state when the operation succeeds
- emit resource-consumption events reflecting write-side physical effects
- emit metric events describing observed writes and resulting storage changes

## Consumption Stream

The resource-consumption output is the accounting-facing stream. It should eventually emit normalized facts that can be priced or aggregated later, for example:

- read capacity consumed
- write capacity consumed
- burst capacity consumed
- bytes read
- bytes written
- bytes deleted
- storage occupancy changes

`TableStage4` should emit raw facts, not final billing totals.

## Metric Stream

The metric/telemetry output is the observability-facing stream. It should represent facts that can be aggregated into CloudWatch-like metrics or simulation diagnostics, for example:

- request observed
- item returned
- bytes returned
- write observed
- write success/failure
- item count changed
- table byte total changed

Metric events should be additive and aggregation-friendly.

## Timing Semantics

By default, `TableStage4` should preserve the input request timestamp for outputs generated directly by the request unless a future latency model explicitly shifts the response into a later time window.

The contract should allow future enhancement where:

- request observation occurs at request time
- synchronous response may be emitted at the same or later simulated time
- background effects may appear in later windows

The stage contract must therefore not assume request time and response time are always identical.

## Error Handling

`TableStage4` should fail fast when:

- it receives a request type it does not support
- it receives a request whose use-case has no registered behavior
- it encounters impossible internal state transitions

It should not silently drop admitted requests.

## Non-Goals For The Initial Implementation

The first complete `TableStage4` implementation does not need to model:

- every DynamoDB operation
- every CloudWatch metric
- exact AWS billing rules
- background table maintenance such as TTL scavenging
- partition-level hot-key effects

It only needs a coherent, extensible contract that correctly handles a narrow set of operations and emits well-formed outputs.

## Definition Of Done For The First Vertical Slice

The initial `TableStage4` milestone should be considered complete when:

- `GetItem` requests produce exactly one response per request
- hit and miss outcomes are representable
- control timing events are preserved on all output streams
- metric output reflects observed gets and returned items/bytes
- resource consumption has a stable event model, even if initially minimal
- the component can be embedded unchanged inside a future higher-level `Table` graph
