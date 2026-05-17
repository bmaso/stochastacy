# Workload YAML Schema

Reference schema for the stochastacy workload DSL. See `docs/architecture/workload-dsl.md`
for design concepts and rationale.

This schema describes Slice 4 scope: single-table workloads, stateless samplers, no sampler
combination, no multi-table flows.

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
```

No additional fields.

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

## Complete Example

```yaml
workloads:

  telemetry-ingest:
    flows:
      - type: put-item
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
      - type: query
        target: { index: $support-index }
        rate: 15
        read-consistency: eventually-consistent

  fleet-dashboard:
    flows:
      - type: scan
        target: { index: $dashboard-index }
        rate: 2
        read-consistency: eventually-consistent

  device-commands:
    flows:
      - type: transact-write-items
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
