# v2/phase13 — closed-loop capability: design notes

Working notes for the phase that gives `stochastacy.core` **closed loops by composition** — motivated by `tailgate`
(the throttle-comparison simulator for Brian's article, on hold until this lands; spec at
`../../../tailgate/docs/simulation-spec.md`). Nothing here is approved until the phase goals/plan say so.

**Status (2026-09-16):** **Concept K (circuits) was chosen and implemented** in v2/phase13 Slices 2–4 and proven by
the MM1 demo (Slices 5–6); see `v2-phase13.md` and the circuits section of `specs/component-catalog.md`. **Concept F1
(registers)** was not built; it remains recorded here as the candidate mechanism for loops *between* circuits, which
are out of scope. These notes are kept as the design record.

## The problem, precisely

Tailgate's loop: `client → throttle → bounded queue → c workers → response → client`, where a rejection or timeout
triggers a **retry** after a backoff of 50/100 ms (`NO_JITTER`) or `uniform(0, 50 ms)` (`FULL_JITTER`), a 500 ms
attempt timeout, and a 1 s throttle window. The retry is a *new request* re-entering the throttle.

Today's core can't compose this. Three separate gaps:

1. **Emission.** `LoopbackComponentSampler.onFeedback` returns `TickEmission` (consumption only) — a fed-back item
   cannot produce a forward output. A retry *is* a request; a success produces *no* request.
2. **Ordering.** Fed-back items are applied when they happen to arrive on `fbIn` — a Pekko scheduling accident
   relative to the same window's primary inputs — not in conceptual-time order.
3. **Cycle-breaking.** A cycle of transducers that each wait for `Tick(k+1)` from upstream before finishing
   window `k` deadlocks (the phase-11 finding). Phase 11 broke its cycle with the "eager tap-tick forward"
   special case, which works only because taps are sampled eagerly on push.

Also in scope: **C1** (samplers don't see their input's conceptual time; needed for e2e latency and for a
continuous-refill throttle) — see `stochastacy-backlog.md`.

Assumption for all concepts: `sample` stays **1:1** (exactly one forward output per primary input).

---

## Concept F1 — "registers": feedback emits + a ≥ 1-tick loop rule (SAVED, not chosen yet)

Treat the graph as a **synchronous digital circuit**. Each transducer is combinational logic processed at the
barrier `Tick(k+1)`. A cycle is legal iff it contains a **register**: a plane whose every output is delayed
**≥ 1 tick**. A register may **run ahead** — because everything it will emit in window `k+1` came from inputs in
windows `≤ k`, it emits `[window-(k+1) items] + Tick(k+2)` when window `k` closes, one barrier early — and that
early tick is what the rest of the cycle waits for. Deadlock-freedom is an induction: with a register in every
cycle, a stage's window-`k` barrier depends only on windows `≤ k−1` elsewhere. Phase 11's eager tap-tick is a
special case (the coordinator's `max(1, lag)` is its register).

**Contract / machinery:**

| Piece | Change |
|---|---|
| `onFeedback` | returns `FeedbackEmission(newState, output: Option[Scheduled[Out]], consumption, taps)`. `None` for phase 11 (a replicated write answers nothing); `Some(retry)` for tailgate. |
| Registers | (a) a core `LoopDelay(horizon)` flow — an explicit "network hop": re-stamps each item **+1 tick** (intra-tick preserved) and runs ahead; and/or (b) a sampler-declared minimum delay on its fwd/tap plane (asserted by the transducer) so that plane runs ahead with no added latency. |
| Horizon | registers **must know `N`**: at the last barrier a register would emit `Tick(N+2)`, which (i) leaves `MergeTimedEventGraph` waiting for a tick the other input never sends → shutdown hang, and (ii) leaks post-horizon items that are residue today. Every runner knows `simulationTicks`; pass it. Deferring the early tick until the next input element re-creates the deadlock, so it can't be inferred on the wire. |
| C1 | `sample`/`onFeedback` gain `at: SimInstant` (conceptual time). Mechanical migration (~12 samplers). |

**F1 (ordered)** additionally window-buffers `in` + `fbIn` in the loopback stage and dispatches the window's
primaries and feedback **merged by conceptual time** at the barrier (tie-break needed: feedback-before-primary
proposed). Consequences: eager tap-tick forward is removed (incompatible with deferred sampling); the phase-11
coordinator migrates to a register (its window-close `PendingReplicationCount` sampling shifts a tick); the
hot-replica baseline must be re-run (the replicated-write interleave changes from arbitrary to defined). Plain
`componentOf` stages stay streaming-on-push → byte-identical.

**F1-lite (arrival order)** — Brian's proposed constraint (2026-09-15): *"feedback loops cannot be tighter than a
single tick"*, and feedback within a window is applied in **wire-arrival order** (today's phase-11 semantic).
Drops the buffering/merge; **keeps eager tap-tick; phase 11 untouched and byte-identical**. Delta ≈ `onFeedback`
return type + `Option`-output handling in the loopback stage + `LoopDelay`. Adequate for tailgate because the
ordering accident touches only the loopback stage's *own* state — its outputs are stamped and **sorted by the
transducer at release**, so every downstream stage sees exact order. Documented limitation: a loopback stage's
`onFeedback` may see times out of order relative to `sample`; time-aware samplers must tolerate it. The ordered
dispatch remains an additive refinement (it changes only *when* the stage calls the sampler).

**Tailgate on F1-lite** (three small samplers + wiring):

```
arrivals ─▶ CLIENT ─fwd: requests (fresh δ=0, retries δ=backoff)─▶ Interface.wrap(QUEUE/SERVICE, THROTTLE) ─▶ rejoin
              ▲ fbIn                                                                                           │
              └── merge ◀── LoopDelay(1 tick) ◀── responses (success | throttle-reject | overflow-reject) ◀──────┘
                    ▲
                    └── CLIENT.tapOut: timeout probes (δ = 500 ms)  (a register by declaration, or via LoopDelay)
```

CLIENT is the only loopback sampler (in = arrivals; fbIn = responses ∪ probes; state = in-flight attempts). The
throttle is a plain gate with latency 0; the queue's service time is an exact fractional delay; queue depth /
busy fraction come from its `onTick` consumption facts.

**F1's inherent cost — the tick is the resolution of a loop's minimum hop latency.** No hop in tailgate's
response cycle is physically ≥ 1 tick (rejection "returns immediately"; `FULL_JITTER` can be ≈ 0; service time
P(< 1 ms) ≈ 10⁻⁴ but P(< 10 ms) ≈ 19 %), so the register is an *artificial* 1-tick hop on every response. At a 1 s
tick a 50 ms backoff becomes 1.05 s and phase-locking cannot occur; at 10 ms it's a 20 % distortion of the
backoff; at **1 ms** it's ~2 % — negligible and identical across arms. Hence tailgate needs ≈ 1 ms ticks:
70,000 ticks per 70 s trial through ~8 stages; tick overhead ≈ 10–20× the request work (rough estimate: 1–1.5 s
per Stage-1 trial, hours for Stage 2). Everything *else* stays exact (fractional delays on normal planes).

Rejected on this axis: **optimistic re-execution** (let feedback land in the same window, roll back — Commons RNG
state is restorable — and re-run with it merged in order). It's Time Warp: cross-stage rollback protocol,
unbounded rounds. Too complex.

---

## Concept K — a second composition mechanism: the **circuit** (sampler-level composition with an event calendar)

Compose **samplers**, not Pekko stages. A `Circuit` is *one* transducer stage hosting N sampler nodes and a wiring
among them — cycles included — run by an internal **discrete-event calendar** (priority queue keyed by
`(eventTime, intraTick, seq)`). From the outside it is an ordinary component (`FanOutShape2`: in → fwd, cons), so
`Interface.wrap`, `TrialRunner`, `MonteCarlo`, and the accounting sinks all work unchanged.

**Execution per window `k`** (at the barrier `Tick(k+1)`): load the window's buffered external inputs into the
calendar (stable-sorted by time); pop the earliest event; dispatch to its target node (`sample` on the node's
`in` port, `onFeedback` on its `fb` port — the same loopback node contract as F1, with the same `FeedbackEmission`
upgrade); stamp each emitted output at trigger + delay and **route**: internal edges → back into the calendar
(any delay, including 0 — intra-tick loops are exact); external edges → the stage's outlet pending queues;
consumption → the external consumption outlet via a per-node adapter. Continue until the earliest calendar
event is in window `> k`. Release window `k`'s outputs, run every node's `onTick(k+1)` in deterministic node
order, emit `Tick(k+1)`. Residue at `EndOfTime` as today. A per-window event cap guards runaway zero-delay cycles.

**Builder sketch (typed at the DSL, erased inside — cf. the existing `ErasedSampler` precedent):**

```scala
Circuit.builder[Arrival, Nothing, TailgateFact] { b =>
  val client   = b.node(clientSampler)   // Loopback node: in = Arrival, fb = Response | Probe, out = Request, taps = Probe
  val throttle = b.node(throttleGate)    // InterfaceSampler: out = Admit(Request) | Reject(Response)
  val queue    = b.node(queueService)    // ComponentSampler: in = Request, out = Response
  b.input(client.in)
  b.edge(client.out,   throttle.in)
  b.edge(throttle.out, queue.in)  { case Admit(r)     => r    }
  b.edge(throttle.out, client.fb) { case Reject(resp) => resp }
  b.edge(queue.out,    client.fb)
  b.edge(client.taps,  client.fb)                                  // timeout probes: self-edge, delay on the tap
  b.consumption(client)(TailgateFact.Client(_)); b.consumption(queue)(TailgateFact.Queue(_))
}
```

`Interface.wrap` is not needed inside a circuit — a gate is a node and Admit/Reject are edges. Per-node RNGs
(derived from the circuit seed) give tailgate's per-concern streams (spec §9) for free.

**Properties:** ticks stay **1 s**; zero-latency rejections, jitter, service times, timeouts all exact; no register,
no horizon plumbing, no tick-overhead — roughly an order of magnitude faster than F1-lite at 1 ms ticks; phase 11
untouched. Loops *across* circuits would still need F1-lite's register (the two are compatible and share the
`onFeedback` change).

**Costs / tensions:** a second way to wire components (rule of thumb: circuits for tight/sub-tick coupling, the
Pekko graph for pipelines and ≥ tick coupling); erased dispatch inside the builder; and the CLAUDE.md principle
*"every request/response/consumption event is a concrete timed event on the wire"* — inside a circuit the wire is
the calendar, not a Pekko stream, so a **wiretap edge** (route any internal edge's events to the consumption
outlet as well) is the proposed way to keep interactions observable. Bigger build than F1-lite (builder DSL +
calendar + erased dispatch + tests).

---

## Status

2026-09-15: F1 / F1-lite developed and saved; Brian asked to explore Concept K next.

2026-09-15: **Concept K chosen** (Brian). Proof demo agreed: the **MM1 demo** — M/M/1 with Bernoulli feedback,
framed as a paginated client against a single FIFO server, checked against its closed-form (Jackson network)
solution. Rejected demo alternatives: retries against an independently-failing server (closed form, but
load-independent — never tests ordering) and a tailgate-lite (no closed form). Slice roadmap:
`docs/roadmaps/v2-phase13.md`. F1 stays recorded here as the future mechanism for loops *between* circuits.
