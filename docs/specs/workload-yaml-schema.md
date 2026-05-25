# Workload YAML Schema

Reference schema for the stochastacy workload DSL. See `docs/architecture/workload-dsl.md`
for design concepts and rationale.

This schema covers Phase 7 Slice 4 (single-table workloads, stateless samplers, `include:`
composition, all independent flow types) and Phase 8 Slice 1 (derived flows: `follow-on` and
`retry`). Multi-table follow-on and variable lag distributions are deferred to a later slice.

---

## File Structure

A workload YAML file is a single YAML document with one top-level key: `workloads`. Its value
is a mapping from workload name to workload record. Names are arbitrary strings; they serve as
identifiers for `include:` references and for the `resolve(name)` call at playback time.

```yaml
workloads:
  <workload-name>: <workload-record>
  <workload-name>: <workload-record>
  ...
```

A file must contain at least one workload. Workload names must be unique within the file.

---

## Record: `workload`

Defines one named workload. A workload has an optional `include:` list, an optional `flows:`
list, or both. A workload with neither is valid but produces no flows (useful only as a named
empty placeholder; not an error).

```yaml
include:              # optional. Ordered list of workload names from the same file.
  - <name>            # Flows from included workloads are prepended to this workload's own flows,
  - <name>            # in the order listed. Circular references are a parse error.
flows:                # optional. Ordered list of flow records.
  - <flow-record>
  - <flow-record>
```

### Flow `id:` field

Every flow record may carry an `id:` field. The `id:` is required when any flow in the file
uses a `follow-on` or `retry` type, because those types reference other flows by id. When no
derived flows exist in the file, `id:` is optional and the parser assigns synthetic ids.

```yaml
flows:
  - id: my-flow       # optional unless derived flows are present
    type: get-item
    rate: 50
```

Flow ids must be unique within the resolved (post-include) flow list of the workload that
contains the derived flow's `source:` reference. Self-referential `source:` ids are legal
(IIR feedback); circular `include:` references remain a parse error.

---

## Record: `get-item` flow

Models a stream of `GetItem` requests against the default (bound) table.

```yaml
- type: get-item
  rate: <rate-sampler>    # required.
```

---

## Record: `delete-item` flow

Models a stream of `DeleteItem` requests against the default table.

```yaml
- type: delete-item
  rate: <rate-sampler>    # required.
```

---

## Record: `put-item` flow

Models a stream of `PutItem` requests against the default table.

```yaml
- type: put-item
  rate: <rate-sampler>           # required.
  item-bytes: <byte-sampler>     # required. Sampled size of the written item.
```

---

## Record: `update-item` flow

Models a stream of `UpdateItem` requests against the default table.

```yaml
- type: update-item
  rate: <rate-sampler>           # required.
  item-bytes: <byte-sampler>     # required. Sampled size of the item after the update.
```

---

## Record: `query` flow

Models a stream of `Query` requests. The target may be the default table (primary-key query)
or a named GSI. There is no default GSI: omitting `target:` always means the base table.

```yaml
- type: query
  rate: <rate-sampler>           # required.
  target: { index: $<var> }      # optional. Names a GSI index variable. Default: base table.
  read-consistency: <string>     # optional. Default: eventually-consistent.
```

Valid `read-consistency` values: `eventually-consistent`, `strongly-consistent`.

---

## Record: `scan` flow

Models a stream of `Scan` requests. Same target rules as `query`.

```yaml
- type: scan
  rate: <rate-sampler>           # required.
  target: { index: $<var> }      # optional. Names a GSI index variable. Default: base table.
  read-consistency: <string>     # optional. Default: eventually-consistent.
```

---

## Record: `transact-write-items` flow

Models a stream of `TransactWriteItems` requests. Each call in the stream is a transaction
containing exactly `per-item-bytes.length` items. Item byte sizes are sampled independently
per item per call.

```yaml
- type: transact-write-items
  rate: <rate-sampler>           # required. Number of TransactWriteItems calls per tick.
  per-item-bytes:                # required. One entry per item in the transaction.
    - <byte-sampler>             #   Minimum one entry. All calls in this flow have the same
    - <byte-sampler>             #   transaction length (equal to this list's length).
```

---

## Record: `transact-get-items` flow

Models a stream of `TransactGetItems` requests. The number of items per call is itself
stochastic, sampled from `item-count` on each call.

```yaml
- type: transact-get-items
  rate: <rate-sampler>           # required. Number of TransactGetItems calls per tick.
  item-count: <rate-sampler>     # required. Items per call. Uses the same grammar as rate:.
```

---

## Record: `follow-on` flow

A derived flow whose arrival rate at tick `t` is proportional to the count of a specific
outcome class from a source flow at tick `t - lag-ticks`. The derived request count per tick
is a Binomial draw: `Binomial(n = sourceOutcomeCount, p = proportion)`.

`follow-on` flows have no `rate:` sampler of their own — their volume is entirely driven by
the source flow's outcomes. They require the workload to be wired with a feedback arc from the
simulator's response stream (see `WorkloadGraph`).

```yaml
- id: <string>                  # required (see Flow id: field above).
  type: follow-on
  source: <workload-name>       # required. Another workload name in the same file whose
                                #   resolved flow list contains the referenced source id.
                                #   Self-reference (source == containing workload) is legal.
  source-flow: <flow-id>        # required. The id of the flow within source whose outcomes
                                #   drive this derived flow.
  outcome: <success|throttled>  # required. Which outcome class from source-flow drives this flow.
  proportion: <number>          # required. Per-outcome probability of generating a derived request.
                                #   Should be in [0.0, 1.0].
  lag-ticks: <integer>          # required. Derived requests are emitted this many ticks after
                                #   the source outcome. Must be >= 1.
  request:                      # required. Shape of each derived request.
    type: <flow-type>           #   Any independent flow type (get-item, put-item, etc.).
    ...                         #   Same fields as the corresponding independent flow, minus rate:.
```

---

## Record: `retry` flow

Shorthand for a `follow-on` where `outcome: throttled` and the derived request has the same
type as the source flow. A retry flow models the AWS SDK's automatic retry-on-throttle behavior.

```yaml
- id: <string>                  # required.
  type: retry
  source: <workload-name>       # required. Same semantics as follow-on source:.
  source-flow: <flow-id>        # required. The flow being retried. The retry's request type
                                #   is inferred from this flow's type; no separate request: block.
  proportion: <number>          # required. Fraction of throttled outcomes that generate a retry.
  lag-ticks: <integer>          # required. Must be >= 1.
```

`retry` is strictly equivalent to a `follow-on` with `outcome: throttled` and a `request:`
block whose type and parameters match the source flow. Prefer `retry` for clarity when
expressing SDK backoff behavior.

---

## Sub-language: `rate-sampler`

Used for `rate:` fields and for `item-count:` in `transact-get-items`. Produces a
`StatelessSampler[Int]`.

### Variant A — integer shorthand

A positive integer literal. Equivalent to a Poisson sampler with a constant lambda equal to
the integer value.

```yaml
rate: 50
```

Produces `PoissonSampler.constant(50)`.

### Variant B — Poisson distribution

```yaml
rate:
  distribution: poisson
  lambda: <value-expr>      # required. The λ parameter. Should be > 0 at every tick.
```

### Variant C — binomial distribution

```yaml
rate:
  distribution: binomial
  n: <positive-integer>     # required. Number of trials. Constant; not time-varying.
  p: <value-expr>           # required. Per-trial success probability. Should be in [0.0, 1.0].
```

### Variant D — constant (non-stochastic)

```yaml
rate:
  distribution: constant
  value: <non-negative-integer>   # required. Always emits exactly this count per tick.
```

---

## Sub-language: `byte-sampler`

Used for `item-bytes:` and for entries in `per-item-bytes:`. Produces a
`StatelessSampler[Long]`. Sampled values are cast to `Long` by flooring; negative values
are not guarded by the parser (behavior at simulation time depends on the downstream consumer).

### Variant A — integer shorthand

A positive integer literal. Equivalent to a constant sampler.

```yaml
item-bytes: 512
```

Produces `ConstantSampler(512L)`.

### Variant B — log-normal distribution

Typical choice for item byte sizes. Output is always positive.

```yaml
item-bytes:
  distribution: log-normal
  mu: <value-expr>          # required. Mean of the underlying normal (log-space).
  sigma: <value-expr>       # required. Std dev of the underlying normal. Must be > 0.
```

### Variant C — normal distribution

```yaml
item-bytes:
  distribution: normal
  mean: <value-expr>        # required.
  stddev: <value-expr>      # required. Must be > 0.
```

### Variant D — uniform distribution

```yaml
item-bytes:
  distribution: uniform
  min: <value-expr>         # required. Lower bound.
  max: <value-expr>         # required. Upper bound. Must be ≥ min at every tick.
```

### Variant E — constant

```yaml
item-bytes:
  distribution: constant
  value: <positive-integer>  # required.
```

---

## Sub-language: `value-expr`

Used as a parameter value inside sampler expressions wherever a `Long => Double` function
is needed (e.g., Poisson lambda, log-normal mu). A value expression is evaluated at every
tick to produce the parameter value for that tick's samples.

### Variant A — constant

A number literal (integer or decimal). The parameter is the same at every tick.

```yaml
lambda: 100.0
mu: 5.7
```

### Variant B — sinusoidal cycle

Produces a smooth sinusoidal curve oscillating between `min` and `max` over `period-ticks`.

```yaml
lambda:
  shape: sinusoid
  min: <number>             # required. Value at the trough.
  max: <number>             # required. Value at the peak. Must be > min.
  period-ticks: <integer>   # required. Full period length in simulation ticks.
  peak-tick: <integer>      # required. Tick within the period [0, period-ticks) at peak.
```

### Variant C — linear growth factor

Produces `1.0 + rate × tick`. At tick 0 the value is exactly 1.0. Multiply against a base
sampler to model steady growth or decay.

```yaml
lambda:
  shape: linear-factor
  rate: <number>            # required. Growth rate per tick. Negative values model decay.
```

### Variant D — triangular peak

Produces a value that ramps linearly from 1.0 to `multiplier` at the midpoint of
`[start-tick, end-tick]`, then ramps back down to 1.0. Outside that range the value is 1.0.

```yaml
lambda:
  shape: triangular-factor
  start-tick: <integer>     # required. Tick at which the ramp begins.
  end-tick: <integer>       # required. Tick at which the ramp returns to baseline.
  multiplier: <number>      # required. Peak multiplier at the midpoint.
```

### Variant E — weekday mask

Produces 1.0 on Monday–Friday and 0.0 on Saturday–Sunday. Assumes tick 0 = midnight Monday.
Multiply against a base sampler to suppress weekend traffic.

```yaml
lambda:
  shape: weekdays
  ticks-per-day: <integer>   # required. Number of simulation ticks per calendar day.
```

### Variant F — time window

Passes through the `inner` expression within `[start-tick, end-tick]` and produces 0.0
outside that range. The `inner` expression may itself be any `value-expr` variant, including
another `time-window`.

```yaml
lambda:
  shape: time-window
  start-tick: <integer>     # required.
  end-tick: <integer>       # required. Inclusive upper bound.
  inner: <value-expr>       # required. Evaluated and passed through within the window.
```

---

## Variable Syntax

Index variable references are strings of the form `$<identifier>` where `<identifier>` matches
`[a-zA-Z][a-zA-Z0-9-]*`. They appear only in the `index:` field of a `target:` spec.

```yaml
target: { index: $support-index }
target: { index: $dashboard-index }
```

The set of variable names required to bind a workload equals the union of all `$<identifier>`
references found transitively in the workload's own flows and in all included workloads'
flows. The `$` is stripped when reporting required names and when looking up values in the
binding map.

---

## Complete Example — Independent Flows

```yaml
workloads:

  telemetry-ingest:
    flows:
      - id: telemetry-put
        type: put-item
        rate:
          distribution: poisson
          lambda:
            shape: sinusoid
            min: 10.0
            max: 200.0
            period-ticks: 1440
            peak-tick: 720
        item-bytes:
          distribution: log-normal
          mu: 5.7
          sigma: 0.25

  customer-support:
    flows:
      - id: support-query
        type: query
        target: { index: $support-index }
        rate: 15
        read-consistency: eventually-consistent

  fleet-dashboard:
    flows:
      - id: dashboard-scan
        type: scan
        target: { index: $dashboard-index }
        rate: 2
        read-consistency: eventually-consistent

  device-commands:
    flows:
      - id: command-transact
        type: transact-write-items
        rate: 5
        per-item-bytes:
          - 200
          - 150

  thermostat-fleet:
    include:
      - telemetry-ingest
      - customer-support
      - fleet-dashboard
      - device-commands
```

Binding `thermostat-fleet` requires: `support-index`, `dashboard-index`.

```scala
file.resolve("thermostat-fleet")
   .bind(
     tableName = "device-telemetry",
     usecase   = config.scenarioId,
     indices   = Map(
       "support-index"   -> "CustomerSupportIndex",
       "dashboard-index" -> "FleetDashboardIndex"
     )
   )
// => WorkloadDefinition
```

---

## Complete Example — Derived Flows (follow-on + retry)

This example models an emergency alert scenario with an A1→A1 retry IIR loop and an
A1→A2 follow-on FIR. `A1` is the combined flow (baseline + retry); both `A2` and `A1-retry`
reference `A1` as their source so that retries of retries cascade correctly.

```yaml
workloads:

  A1-baseline:
    flows:
      - id: a1-poll
        type: query
        target: { index: $by-region-index }
        rate:
          distribution: poisson
          lambda: 400.0
        read-consistency: eventually-consistent

  A1:
    include:
      - A1-baseline
    flows:
      - id: a1-retry
        type: retry
        source: A1            # self-reference: retries of the full A1 flow (IIR)
        source-flow: a1-poll
        proportion: 0.90
        lag-ticks: 1

  A2:
    flows:
      - id: a2-fetch
        type: follow-on
        source: A1            # follows successful A1 queries (FIR)
        source-flow: a1-poll
        outcome: success
        proportion: 0.15      # alpha — cache-miss rate
        lag-ticks: 1
        request:
          type: get-item

  emergency-alert:
    include:
      - A1
      - A2
```

The `A1` workload's resolved flow list contains both `a1-poll` (from `A1-baseline`) and
`a1-retry`. The `source: A1` reference in both `a1-retry` and `a2-fetch` resolves to
this combined flow list, ensuring retries of retries are observed by both derived flows.
