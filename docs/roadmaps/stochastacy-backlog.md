# Stochastacy backlog — deferred library work

Changes to stochastacy discovered while building **downstream projects** (starting with `tailgate`, the
throttle-comparison simulator for the stochastic-processes article) that were **deliberately deferred** so the
downstream work keeps focus. Each entry records where it was found, why it matters, and the downstream impact of
making the change. Remove an entry (or mark it DONE with the commit) when it lands.

Triage rule: fix in stochastacy immediately only when it **blocks** downstream work; otherwise log it here.

---

## Build / publishing

### B1. Set real artifact coordinates
- **Found:** tailgate Slice 0 scaffold (2026-09-15).
- **Issue:** `build.sbt` sets no `organization`, so `sbt publishLocal` publishes under the project-name default:
  `stochastacy % stochastacy_3 % 0.0.1` (likewise `stochastacy-aws`, `stochastacy-examples`).
- **Fix:** `ThisBuild / organization := "com.bmaso"`; decide a version scheme (e.g. `0.1.0-SNAPSHOT` while
  iterating vs. fixed releases).
- **Downstream impact:** tailgate's `build.sbt` dependency line changes to the new group/version; republish.

### B2. Don't publish `examples`
- **Found:** tailgate Slice 0 (2026-09-15).
- **Issue:** `publishLocal` also published `stochastacy-examples` (the store demos + Grafana bridge) — an
  application, not a library.
- **Fix:** `examples / publish / skip := true` (decide separately whether `aws` stays published).

### B3. Core leaks a logging backend
- **Found:** tailgate Slice 0 (2026-09-15).
- **Issue:** `core` (and `aws`, `examples`) declare `logback-classic` as a **compile** dependency, forcing a
  logging implementation onto every consumer.
- **Fix:** drop it from `core`/`aws` (or scope it `Test`/`Runtime`); leave backend choice to applications.

### B4. Core declares dependencies it doesn't use
- **Found:** tailgate Slice 0 (2026-09-15), verified by grep of `core/src`.
- **Issue:** `core` depends on `json4s-jackson`, `com.typesafe:config`, and `scala-logging`, but **no core source
  uses any of them**. json4s is used only by `aws` (2 files) and `examples` (5 files); config and scala-logging
  are used nowhere. Contradicts "core stays domain-agnostic" and bloats consumers' classpaths.
- **Fix:** move `json4s-jackson` to `aws` (examples inherits it); remove `config` and `scala-logging`.
- **Downstream impact:** a consumer that relied on these transitively (tailgate shouldn't) must declare them.

## Engine capabilities

### C1. Gates are tick-granular; samplers can't see an input's timestamp
- **Found:** tailgate Slice 0, reading core for the throttle design (2026-09-15).
- **Issue:** `TokenBucketGate` refills once per tick in `onTick`, and `FlatThrottleGate` resets per tick, so their
  resolution is the tick. `ComponentSampler.sample(in, state, rng)` receives only the payload — not the input's
  `eventTime`/`intraTick` — so a sampler cannot model continuous-time state (e.g. a token bucket refilling
  continuously between requests) unless the domain copies the timestamp into the payload.
- **Fix (options):** a continuous-refill token bucket gate; or pass the input's conceptual time to `sample`
  (an engine contract change — needs explicit design approval).
- **Downstream impact:** tailgate implements its own throttles, carrying arrival time in the request payload
  (spec §5.1/§13 require continuous refill). Not blocking.
- **Note:** the existing gates are *correct* for tick-granular models (they were built for per-second capacity
  models). With a 1-second tick, `FlatThrottleGate` is exactly tailgate's `FIXED_WINDOW` (counter reset on each
  second boundary, aligned to t=0). The gap is sub-tick time, addressed by C2.

### C2. GOAL: a continuous-refill token-bucket gate in core — harvested from tailgate (do after tailgate)
- **Agreed:** 2026-09-15, Brian. **Scheduled for after tailgate is complete** — do not start before.
- **Goal:** add a continuous-refill token-bucket gate to `stochastacy.core.component.gate`, alongside (not
  replacing) the tick-granular `FlatThrottleGate` / `TokenBucketGate`, which stay correct for tick-level models.
- **Plan:**
  1. Tailgate builds its throttles first (arrival time carried in the request payload — a tailgate-local
     workaround) and proves them against its spec §12 acceptance checks: throttle bounds (§12.4, admissions over
     any interval T ≤ B + r·T), null-case equivalence (§12.3), pairing integrity.
  2. Harvest the proven design into core **together with the C1 contract fix** — give samplers the input's
     conceptual time (`eventTime + intraTick`) so the gate needs no timestamped payload. That contract change is
     an engine design change: sketch it and get Brian's explicit approval before implementing; gate on the full
     `sbt test` across all modules; republish.
  3. Port tailgate's throttle tests into core as the gate's tests; add the gate to `specs/component-catalog.md`.
  4. Optionally migrate tailgate onto the core gate afterwards (drops its payload workaround).
- **Source to harvest:** tailgate's throttle implementation + tests
  (`/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/tailgate/src/…`).

### C3. No time-ordered request feedback loop (client retries can't be composed)
- **Found:** tailgate slice breakdown, designing the client retry loop (2026-09-15).
- **Issue:** a retry is a *new request* re-entering an upstream component (throttle) after a backoff that can be
  far shorter than a tick (spec: 50 ms, or `uniform(0, 50 ms)` with full jitter). The loopback machinery
  (`LoopbackComponentSampler` / `loopbackComponentOf`, built for global-table replication) doesn't fit:
  `onFeedback` returns a `TickEmission` (no forward output — a fed-back request can't produce a response), and
  feedback events are applied as they arrive, **not time-ordered against the primary inputs of the same tick
  window**. No tick size fixes the ordering for arbitrarily small jittered delays.
- **Fix (options):** a request-level feedback primitive whose fed-back inputs are merged into the primary input
  in conceptual-time order and produce forward outputs; needs a design for zero/sub-tick loop delays. Engine
  design change — needs explicit approval.
- **Downstream impact:** **BLOCKING** — tailgate's purpose is to show stochastacy models loops; the monolithic
  event-loop sampler workaround was rejected as heroic.
- **Status:** **PROMOTED to a new stochastacy development phase** (closed-loop capability), 2026-09-15. Tailgate is
  on hold until it lands.

### C4. Histogram quantile resolution is coarse for exact within-trial percentiles
- **Found:** tailgate slice breakdown (2026-09-15).
- **Issue:** `stats.Histogram` uses ~8 % geometric buckets with linear interpolation — fine for mergeable
  cross-trial summaries, but its quantization error can blur small **paired** per-trial p99 differences, which
  is exactly tailgate's primary comparison.
- **Fix (options):** a configurable bucket base, or an exact/HDR-style quantile accumulator alongside it.
- **Downstream impact:** tailgate computes within-trial percentiles exactly (sorted samples). Not blocking.

## Documentation

### D1. Stale `CLAUDE.md` facts
- **Found:** tailgate Slice 0 (2026-09-15).
- **Issue:** Build section says `sbt core/publishM2 # (com.bmaso, 0.1.0-SNAPSHOT)` — wrong on group, version,
  and the command actually used (`publishLocal`). Current-position section still says
  **"v2/phase12 — IN PROGRESS"** though phase 12 is complete and merged.
- **Fix:** correct both (after B1 settles the coordinates).
