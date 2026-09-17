# MM1: A Closed Loop Checked Against Queueing Theory (circuits on the v2 core) — Engineer's Guide

A small, purpose-built demo that proves stochastacy can simulate a **closed feedback loop** exactly. A paginated
client talks to a single FIFO server: every response may trigger the client's next request, so requests flow in a
loop, and at the default load a whole session makes several round trips **inside one tick**. The model is
**M/M/1 with Bernoulli feedback**, a queue with an exact closed-form solution, so the pass criterion is not a
captured baseline: every measured quantity must agree with **queueing theory**, within its Monte Carlo confidence
interval.

The example lives in the `examples/` module, package `stochastacy.examples.mm1`. The loop is a **circuit** from
`stochastacy.core.component.circuit` — see the [core component catalog](component-catalog.md#circuits), including
the [rubric](component-catalog.md#when-a-circuit-is-required) for when a circuit is required.

---

## 1. What the demo demonstrates

### The fictional domain
Users open **sessions** with a paginated service, arriving as a Poisson process at rate **λ = 40 sessions per tick**.
Each session requests page 1; after every page the user asks for another with probability **p = 0.6**, so a session
reads a geometric number of pages, 2.5 on average. One **server** handles every page request, one at a time,
first-in first-out, with an exponential service time at rate **μ = 125 per tick**. The server therefore sees
**λ_eff = λ / (1 − p) = 100** page requests per tick and runs at utilization **ρ = λ_eff / μ = 0.8**.

### The circuit
```
sessions (Poisson λ) ──▶ CLIENT ──page request──▶ SERVER (FIFO, one worker, Exp(μ) service)
                           ▲ fb                          │
                           └──────── page response ◀─────┘
```
Two nodes and three wires: the external input feeds the client's `in`, the client's requests feed the server, and
the server's responses feed the client's **feedback** port, which is where the next request comes from. Both nodes
report through the circuit's consumption outlet; nothing leaves on the forward outlet.

### Two arms, one workload
| arm | what it isolates |
|---|---|
| **immediate** (`mm1-immediate`) | the next page is requested at the response's own instant — a **zero-delay** loop |
| **think time** (`mm1-think-time`) | the next page waits an exponential think time, mean 0.02 tick — a **sub-tick, non-zero** loop delay |

Both arms use the same session arrivals, so they measure the same sessions.

### A representative run (200 trials × 300 ticks, warm-up 60, seed 1)
| metric | immediate | think time | theory |
|---|---|---|---|
| pages per session | 2.4996 ± 0.0016 | 2.4991 ± 0.0014 | 2.5000 |
| mean number in system | 4.0012 ± 0.0263 | 3.9851 ± 0.0251 | 4.0000 |
| time per page (ticks) | 0.0400 ± 0.0002 | 0.0398 ± 0.0002 | 0.0400 |
| session duration (ticks) | 0.1002 ± 0.0006 | 0.1297 ± 0.0006 | 0.1000 / 0.1300 |
| busy fraction | 0.8005 ± 0.0008 | 0.8003 ± 0.0008 | 0.8000 |
| page rate (per tick) | 100.02 ± 0.09 | 100.01 ± 0.09 | 100.00 |

**Every metric is within its confidence interval of theory in both arms.** Each arm measured 1,760,806 sessions,
with no cohort session left unfinished at the horizon. Think time lengthens a session by about 0.03 tick and leaves
every server-side number unchanged, exactly as the theory predicts.

## 2. The theory

A single server fed by Poisson arrivals, with exponential service and each finished job rejoining the queue with
probability `p`, is a **Jackson network**. Its stationary distribution has a *product form*: the server behaves as an
M/M/1 queue at the effective arrival rate `λ_eff = λ / (1 − p)`, even though the stream it actually receives
(fresh sessions plus returning pages) is not Poisson. That gives exact results for everything the demo measures
(`MM1Theory`):

| quantity | closed form | at λ = 40, p = 0.6, μ = 125 |
|---|---|---|
| pages per session | geometric from 1: `P(k) = (1 − p) p^(k−1)`, mean `1 / (1 − p)` | 2.5 |
| number in system (waiting + in service), time-average | `P(N = n) = (1 − ρ) ρⁿ`, mean `ρ / (1 − ρ)` | 4 |
| time per page (queue wait + service) | `1 / (μ − λ_eff)` | 0.04 tick |
| session duration | pages × time per page = `1 / ((1 − p) μ − λ)` | 0.1 tick |
| server busy fraction | `ρ` | 0.8 |
| page rate | `λ_eff` | 100 per tick |

**Think time** adds an infinite-server station between a response and the next request. It does not change the rate
at which pages reach the server — every session still requests the same geometric number of pages — so every
server-side quantity is unchanged, and a session is longer by one think time for each page after the first:
`(1/(1 − p) − 1) × 0.02 = 0.03` tick.

**Little's law** (`L = λW`) ties three of the measurements together independently of the closed forms: mean number in
system = page rate × time per page (4 = 100 × 0.04).

## 3. The mechanisms

- **The workload** (`MM1Workload`) accumulates exponential inter-arrival times from conceptual time 1.0 and stamps each
  session at its exact instant — tick `⌊τ⌋`, intra-tick `τ − ⌊τ⌋` — so arrivals are sorted overall and within every
  tick.
- **The client** (`ClientNode`) is a *stateless* loopback sampler. `sample(session)` emits page 1 with zero delay.
  `onFeedback(response)` draws whether the session continues: if so, it emits the next page — at the response's own
  instant, or after a think-time draw — and otherwise it emits a `SessionCompleted` fact. The session's start time rides
  on every request and returns on every response, so the client never holds per-session state.
- **The server** (`ServerNode`) is an ordinary component. A request arriving at `a` starts at `max(a, freeAt)` and
  finishes an exponential draw later, so **queue waiting is computed exactly**, never sampled; the response's delay is
  the whole sojourn, and a `PageServed(sojourn)` fact is stamped at completion.
- **Exact time averages.** Every job's finish time is known when it arrives, so at each tick boundary the server's
  `onTick` knows the whole trajectory of N(t) across the window that just closed. It emits two facts per window:
  `WindowIntegral` (∫N dt as each open job's overlap with the window, and busy time as each job's service overlap) and
  `WindowLevels` (N(t) walked as a step function — arrivals before finishes at equal instants — totalling the time
  spent at each level, with the top slot collecting everything above). Jobs that can no longer touch a later window are
  dropped, so the state stays bounded by the number in flight.
- **Why this needs a circuit.** The loop's delay is zero in one arm and a fraction of a tick in the other, so trips
  around it take less than a tick — the [rubric](component-catalog.md#when-a-circuit-is-required)'s first
  **must**. The FIFO server is also order-sensitive: it reads each request's `at`, which only means what it should if
  requests reach it in conceptual-time order. The circuit's calendar dispatches every event that way, and a routed
  response that lands inside the current tick is dispatched in the same pass — which is how a whole session can run
  within one tick.

## 4. Measurement design

Each choice below is there because a simpler choice was measured to be wrong. The trial runner (`MM1TrialRunner`)
folds the consumption plane into running totals, so a trial never holds its facts.

- **Warm-up excluded.** The queue starts empty, and the closed forms describe steady state. Measurement runs over
  `[warmupTicks + 1, simulationTicks + 1)`; the demo's default warm-up is 20 % of the horizon, far more than the opening
  transient, which relaxes in about `1 / (μ (1 − √ρ)²)` ticks — roughly 3 at ρ = 0.9.
- **Integrals are divided by the windows received.** A window's integral is stamped at the boundary that closes it,
  and the window closed by the flush tick has no later boundary to be released at — it is post-horizon residue, by
  engine design. Dividing by the nominal number of measured ticks biased mean number in system and busy fraction low by
  `(M − 1)/M` (busy fraction read **0.7971** against 0.8 in the first full run). The runner divides by the windows it
  actually received, recorded per trial as `windowsMeasured`.
- **The session cohort is chosen by start time.** Per-session averages cannot use sessions still running at the
  horizon — and simply dropping those under-represents *long* sessions, the ones most likely to be caught unfinished
  (pages per session read −10σ at ρ = 0.9 over 8 000 trials). Instead the cohort is every session that **starts** in
  the measured window at least `cohortMarginTicks` before the horizon (`MM1Config.inCohort`), so every cohort session has
  time to finish and nothing is selected by length.
- **The cohort margin was measured, not assumed.** A first 5-tick margin, chosen from the *mean* session length, left
  sessions unfinished: near saturation the queue makes long excursions, and a session caught in one re-queues every
  page behind a long line. Over 11.7 M sessions at ρ = 0.9, 375 ran past 5 ticks and the longest took 10.85, so the
  margin is **20 ticks**. `sessionsInFlight` counts any cohort session still unfinished, and is expected to be zero.
- **Distribution slots follow a rule stated up front.** A rarely visited queue level is a zero-inflated per-trial
  variable — at ρ = 0.5, 55 % of trials spent no time at all at `N ≥ 11` — and the normal approximation behind a
  confidence interval is weakest there. `MM1Theory.adequateQueueLevels` reports individual levels only while
  `P(N = n) ≥ 1 %`, then one tail slot, capped at 12 slots: 7 slots at ρ = 0.5 and 12 at ρ = 0.8 and 0.9. Pages per session
  uses 6 slots (1 to 5 pages, then 6 or more).
- **Error bars come from independent trials.** Each estimate is the mean of per-trial values with its across-trial
  standard error; trials are independent, so correlation within a trial is already accounted for. Every **ensemble gets
  its own master seed**: ensembles that shared one replayed the same noise and showed a spurious −3σ "calibration
  bias" at all three loads, which vanished with independent seeds.

## 5. What proves it

**`MM1TheoryBaselineSpec`** (examples module) is the proof. It runs six ensembles — ρ = 0.5, 0.8, 0.9 (λ = 25, 40, 45)
× immediate and think time — each 1 000 trials × 100 measured ticks on its own fixed master seed, and asserts:

- **Every closed form.** Per ensemble: the six means, the queue-length distribution, the pages-per-session
  distribution, and Little's law (from per-trial differences, which cancels the correlation between L, λ and W within
  a trial) — **140 checks**. A check passes when `|estimate − theory| ≤ k · stderr`, where `k` holds the chance of *any*
  false failure across all checks to 5 % (Bonferroni, computed from the actual check count: **k = 3.570**). All pass;
  the largest |z| is 2.60.
- **No unfinished cohort session**, in any ensemble.
- **A negative control.** A spec that passes proves nothing unless it could have failed, so the known Slice 5 bias —
  integrals divided by nominal ticks — is recomputed from the same ensemble and must be rejected. It is, at
  **z = −13.67**.

Its header carries the integrity rule: a failure is investigated to root cause, and never answered by changing a seed,
loosening `k`, widening a band, or re-running until it passes.

Supporting specs: `ServerNodeSpec` (hand-worked starts, sojourns, window integrals and level times, including a job
spanning windows); `MM1WorkloadSpec` (arrival rate, within-tick sort, reproducibility); `MM1DemoSpec` (one result per
trial, reproducibility, JSONL shape, page conservation, the windows-received regression, both distributions summing to
one, the think-time arm leaving server load unchanged). The circuit engine beneath it is proven in core — see the
catalog's [`Circuit`](component-catalog.md#circuit) entry.

## 6. Running it

No external services; prints an estimate-vs-theory table per arm and writes one JSONL line per trial.

```bash
sbt 'examples/runMain stochastacy.examples.mm1.MM1Demo --output /tmp/mm1.jsonl --trials 200 --ticks 300 --seed 1'
```

Flags (all optional): `--output <path>` `--seed <long>` `--trials <int>` `--ticks <long>` `--warmup <long>` (default
20 % of `--ticks`) `--parallelism <int>`. The run takes a few seconds and is **deterministic**: a fixed seed reproduces
every number exactly, at any parallelism.

**The console table.** One block per arm: the configuration (λ, μ, p, think time, λ_eff, ρ), then each metric's
estimate ± standard error beside its closed form, and a *within CI* column (informational here — the theory spec
asserts it), then the number of cohort sessions measured and how many were unfinished at the horizon.

**The JSONL.** One flat object per trial: `scenario`, `master_seed`, `trial`; the configuration `lambda`, `mu`, `p`,
`think_time_mean` (0 for the immediate arm), `rho`, `ticks`, `warmup_ticks`; the metrics `pages_per_session`,
`session_duration`, `page_time`, `mean_in_system`, `busy_fraction`, `page_rate`; the counts `sessions_measured`,
`sessions_in_flight`, `pages_measured`, `windows_measured`; and the two distributions, `queue_level_fractions` (fraction
of time at N = 0, 1, …, last slot the tail) and `pages_distribution` (fraction of cohort sessions with 1, 2, … pages,
last slot the tail).

To run the theory baseline itself (about a minute):

```bash
sbt 'examples/testOnly stochastacy.examples.mm1.MM1TheoryBaselineSpec'
```
