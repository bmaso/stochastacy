# Stochastacy: MVP Definition & Go-to-Market Plan

*Service Coverage Roadmap · Audience MVPs · Phase Plan · Monetization*

*Reconstructed from planning session — May 2026*

---

## Part 1 — Service Coverage Roadmap

AWS services are prioritized by the ratio of simulation value to implementation effort. Each tier represents a cohesive set that, once complete, meaningfully expands the set of real-world architectures users can model. Services within a tier are listed in priority order.

### Tier 1 — The Serverless Stack (60–70% of architect use cases)

DynamoDB (complete) plus the four services below form a complete, self-sufficient serverless application. Every service in Tier 1 connects naturally to the others. Lambda is the highest-priority next component: it pairs immediately with DynamoDB and enables the most common serverless architecture question architects want answered.

| # | Service | Simulation value & key behaviors |
|---|---------|----------------------------------|
| 1 | **Lambda** | Highest priority. Pairs directly with DynamoDB. Cold start frequency (a queuing problem driven by inter-arrival time vs. execution duration) and concurrency scaling ramp (~500 new concurrent executions/min) are the key non-trivial behaviors. Memory/duration cost trade-off is a first-class simulation output. |
| 2 | **API Gateway** | Natural front-door for Lambda. Throttling (per-route and account-level), latency percentiles, and REST vs. HTTP API cost differences are the interesting simulation targets. |
| 3 | **SQS** | Async decoupling between Lambda functions. Queue depth during traffic bursts, batch size / Lambda concurrency interaction, and DLQ overflow are high-value outputs. Essential for the ThermoFleet demo's telemetry shock-absorber pattern. |
| 4 | **S3** | Storage and event-trigger layer. Cost model is simpler than the others (volume + requests), but firmware OTA rollout bursts and the archive-vs-retain trade-off make it worth modeling. Weakest member of Tier 1; could be deferred to Tier 2. |

### Tier 2 — Operations & Caching Layer (DevOps audience readiness)

Tier 2 services appear in production architectures alongside the Tier 1 set and are essential for the DevOps/Platform Engineer audience. ElastiCache has a strong argument for promotion to Tier 1: "should I put Redis in front of DynamoDB, and what will it actually save me?" is one of the most common architecture questions in this space.

| # | Service | Simulation value & key behaviors |
|---|---------|----------------------------------|
| 1 | **ElastiCache (Redis)** | Cache hit/miss ratio, eviction policy, and the DynamoDB read relief question. Consider promoting to Tier 1 based on design partner feedback. |
| 2 | **CloudWatch / X-Ray** | Observability cost: metric ingestion volume, log retention, trace sampling rates. Relevant for FinOps audience as observability can be a surprise cost driver. |
| 3 | **ECS / Fargate** | Container workloads. "Lambda vs. Fargate for this workload" is a very common architect question. Requires CPU/memory allocation and spot vs. on-demand modeling. |
| 4 | **RDS (Aurora Serverless)** | Relational database tier. Serverless v2 ACU scaling and the I/O-optimized pricing mode are the interesting simulation targets. |
| 5 | **Kinesis Data Streams** | High-throughput event streaming. Shard count, enhanced fan-out, and extended retention pricing. Relevant for IoT and analytics workloads. |

### Tier 3 — Specialized Services (enterprise & vertical audiences)

Tier 3 services appear in specific verticals or at higher engineering maturity. Build these based on demand signals from Tier 1/2 users.

- Step Functions — orchestration cost for multi-Lambda workflows
- EventBridge — event routing fan-out pricing
- Cognito — user pool MAU and token refresh pricing
- SNS — notification fan-out cost (pub/sub topology)
- Secrets Manager / Parameter Store — secret rotation and retrieval pricing
- WAF / Shield — request-volume-based security layer pricing
- CloudFront — CDN edge cost, cache hit ratio, data transfer
- MSK (Managed Kafka) — broker instance + storage pricing

---

## Part 2 — MVPs by Audience

Each audience has a distinct minimum feature threshold. Reaching it earlier enables earlier adoption. The audiences form a natural value chain: architects and DevOps engineers produce simulation results → FinOps teams consume them for forecasting → leaders make decisions based on them.

### Software Architects

**MVP threshold:** CLI + Tier 1 services (Lambda, API Gateway, SQS, S3 + existing DynamoDB)

Minimum feature set to achieve meaningful adoption:
- Can model a complete serverless architecture end-to-end
- Scenario comparison: two architecture variants side-by-side
- Monte Carlo output: cost distribution (p10/p50/p90) and throttle probability
- JSONL or CSV export (feeds into their own dashboards)
- Docs and examples good enough to reach "wow" moment in ≤15 minutes

*Strategic note: Architects feel the pain most directly and have self-serve discovery behavior. They become internal champions who unlock FinOps and leadership deals. The bottom-up PLG motion is the most realistic path to early traction.*

### DevOps / Platform Engineers

**MVP threshold:** CLI + Tier 1 + Tier 2 (ElastiCache, ECS/Fargate) + Terraform import

Minimum feature set to achieve meaningful adoption:
- Terraform import: point at existing infra, simulator fills in table/Lambda config
- Cost-vs-performance Pareto output (e.g., Lambda memory vs. duration vs. cost)
- CI/CD integration: run simulation on PR and post cost delta comment
- Structured JSON output for programmatic consumption
- Kubernetes / ECS workload support (Tier 2)

*Strategic note: Terraform import is the single highest-leverage feature for DevOps adoption. It removes the friction of describing an existing system from scratch, replacing it with "point and shoot." This feature alone could meaningfully accelerate DevOps adoption beyond the architect audience.*

### FinOps / Cloud Finance

**MVP threshold:** Web UI (non-negotiable) + scenario comparison + export to Excel/PDF

Minimum feature set to achieve meaningful adoption:
- Web UI: non-technical users cannot use a CLI — this is a hard gate
- Scenario comparison with side-by-side cost breakdowns and charts
- Export to Excel and PDF for inclusion in finance reports
- Budget variance: "actual vs. simulated" reconciliation view
- Chargeback / showback: cost attribution by team or service

*Strategic note: FinOps teams respond to outputs, not inputs. They need something they can put in front of a CFO. A CLI that produces JSONL is not it. The web UI is a prerequisite for this audience, not a nice-to-have.*

### Engineering Leadership

**MVP threshold:** Enterprise plumbing: SSO, audit log, executive dashboards, team workspaces

Minimum feature set to achieve meaningful adoption:
- SSO / SAML integration (procurement blocker without it)
- Audit log: who ran what simulation, when
- Team workspaces: shared scenario libraries across an org
- Executive summary view: cost trends, risk flags, recommendation summaries
- SLA and support tier (procurement requires this for enterprise contracts)

*Strategic note: Leadership mostly needs the enterprise plumbing to remove procurement blockers after a champion has already sold internally. You rarely cold-sell leadership. The sequence is always: individual user → internal champion → leadership buy-in.*

---

## Part 3 — Integrated Phase Plan

Six phases spanning approximately 18 months. Each phase has parallel development milestones and launch/community actions — you never build in silence, but you also never launch something that isn't genuinely useful yet. The key structural principle: every meaningful development milestone has a corresponding content or community action.

| Phase | Timeline | Development milestones | Launch & community actions |
|-------|----------|------------------------|---------------------------|
| **Phase 0 — Now** | 0–1 month | DynamoDB simulator (complete). Thermostat Fleet demo. JSONL/Postgres/Grafana pipeline. | Design partner outreach: approach 3–5 companies with large AWS bills. GitHub repo public (MIT or Apache 2.0). First technical blog post: "Why we built a stochastic AWS simulator instead of using the pricing calculator." |
| **Phase 1 — Lambda** | Months 1–3 | Lambda simulator: cold start model, concurrency scaling ramp, memory/duration trade-off. ThermoFleet demo extended to include Lambda functions (Telemetry Handler, Processor, Archiver). "Lambda vs. Fargate cost comparison" as first cross-service scenario. | Publish "How cold starts actually affect your Lambda bill" (benchmarked against real data). HackerNews / r/aws launch. Collect design partner feedback on Lambda model accuracy. First design partner revenue (target: $10–25k). |
| **Phase 2 — API Gateway + SQS** | Months 3–5 | API Gateway simulator: per-route throttling, REST vs. HTTP API cost. SQS simulator: queue depth model, Lambda batch size interaction, DLQ overflow. Terraform import MVP: read existing Lambda/DDB config from Terraform state. CI/CD integration: GitHub Action that posts cost delta on PR. | DevOps audience activation. Blog: "We added Terraform import — here's what we learned." AWS re:Invent submission (or equivalent conference). Consulting firm outreach: 2–3 targeted APN boutique firms. |
| **Phase 3 — S3 + ElastiCache** | Months 5–8 | S3 simulator: request pricing, OTA firmware rollout burst model, archive cost. ElastiCache (Redis) simulator: cache hit/miss ratio, eviction, DynamoDB read relief. Full ThermoFleet demo: all five Tier 1 services working together. Web UI MVP: scenario input form, Monte Carlo output charts, CSV/PDF export. | FinOps audience activation. Case study: "Simulating a thermostat fleet on AWS — what we found." Launch Product Hunt. Pro tier launch ($49/month individual, $199/month team). |
| **Phase 4 — Enterprise Plumbing** | Months 8–12 | SSO / SAML integration. Team workspaces and shared scenario libraries. Audit log. Executive summary dashboard. ECS/Fargate simulator (Tier 2). SLA definition and support tier. | Enterprise sales motion activation. Work with consulting firm partners on white-label arrangements. Target: first $10k ARR enterprise contract. AWS ISV Accelerate application. |
| **Phase 5 — Scale** | Months 12–18 | Remaining Tier 2 services (RDS Aurora Serverless, Kinesis, CloudWatch). Multi-account / organization-level simulation. API for programmatic scenario generation (for consulting firm white-label). Accuracy benchmarking program: compare simulation output to real AWS bills. | Department and enterprise license tier launch. Co-sell through AWS ISV Accelerate. Referral / affiliate program for consulting firms. Conference speaking circuit (re:Invent, AWS Summit, FinOps X). |

---

## Part 4 — Monetization Progression

Four transition points from free to enterprise. Each is designed so the upgrade feels inevitable rather than pressured — the user hits a genuine limit and the next tier is the obvious next step.

### Free / Open Source

**Includes:** CLI, DynamoDB + Lambda + API Gateway simulators, local JSONL output, unlimited personal use.

**Buyer profile:** Architect discovers the tool, hits "wow" moment, shares internally. Organic growth and word-of-mouth. GitHub stars and forks as social proof.

**Sales motion:** None — this is the acquisition layer.

**Upgrade trigger:** User wants scenario comparison, CSV export, or to share results with a colleague who doesn't use a CLI.

### Pro ($49–99/month individual)

**Includes:** Web UI, scenario comparison, CSV/PDF export, S3 + SQS + ElastiCache simulators, CI/CD integration, email support.

**Buyer profile:** Individual architect or DevOps engineer who has validated the tool on free tier and wants shareable output for design reviews.

**Sales motion:** Self-serve credit card. No sales involvement.

**Upgrade trigger:** Team wants shared access; manager asks for a team account.

### Team ($199–499/month, up to 10 seats)

**Includes:** Everything in Pro + team workspaces, shared scenario library, admin console, Terraform import, priority support.

**Buyer profile:** Engineering team that uses the tool regularly for architecture decisions and wants a shared library of approved scenarios.

**Sales motion:** Self-serve or light inside sales (one call to configure team workspace).

**Upgrade trigger:** Team grows beyond 10 seats or needs SSO for IT compliance.

### Enterprise (custom, $2k–10k+/month)

**Includes:** Everything in Team + SSO/SAML, audit log, executive dashboards, white-label option, SLA, dedicated CSM, custom service coverage (Tier 3 services on request).

**Buyer profile:** Large organization where procurement requires SSO, audit, and a signed contract. Usually driven by an internal champion who started on Pro or Team.

**Sales motion:** AE-led deal, often with consulting firm co-sell or AWS co-sell via ISV Accelerate.

**Upgrade trigger:** Renewal with expanded scope, or M&A that brings in new teams who want to use the tool.

---

## Appendix — The Consulting Firm Channel

AWS boutique consulting firms (50–200 person APN-registered shops) are a viable second distribution channel once the direct PLG motion has traction. The HVAC contractor analogy applies: firms have established trust relationships with clients, recurring engagements, and strong incentive to bring tools that differentiate their work.

### Three channel models

| Model | Description | Trade-offs | Timing |
|-------|-------------|------------|--------|
| **A — Tool for consultants** | Firm licenses stochastacy, uses it internally, client sees outputs only. | Simple to sell and operate. No path from engagement to direct client subscription. | Best fit for early relationships before white-label is built. |
| **B — White-labeled client deliverable** | Firm licenses + skins stochastacy; client gets portal access as part of engagement. | Higher value. Requires SSO, white-labeling, per-client tenant provisioning. | Closest to the HVAC contractor model. Target for Phase 4+. |
| **C — Referral / affiliate** | Firm refers clients to sign up directly; earns referral fee. | Preserves direct client relationship. Some firms resist (want to be indispensable). | Low-effort to set up. Works alongside options A or B. |

Recommended sequence: establish direct user credibility (Phases 1–2) before approaching consulting firms. Target firms will ask "who else is using this?" A base of 200+ individual architects makes that conversation much easier than pitching a prototype with no user base.
