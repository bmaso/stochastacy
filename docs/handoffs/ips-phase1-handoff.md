# IPS Phase 1 Hand-Off

Last updated: 2026-04-19

## Current Position

`ips/phase1` is now centered on a table-only DynamoDB simulator and a runnable demo workflow for the initial public showing.

Implemented slices:

- `TableStage4` support for `GetItem`
- `TableStage4` support for `PutItem`
- `TableStage4` support for `UpdateItem`
- `TableStage4` support for `DeleteItem`
- mutable stochastic-summary table state
- raw DynamoDB consumption events
- additive usage aggregation
- time-based storage usage aggregation from timed event streams
- downstream pricing from usage totals and time-based usage
- Monte Carlo multi-trial execution
- raw per-tick JSONL export
- derived `60s` and `300s` windowed JSONL export
- Postgres staging bridge
- provisioned Grafana dashboard

Phase-1 scope decision:

- indexes are not part of phase 1
- `Query`, `Scan`, and PartiQL queries are deferred to phase 2
- global secondary indexes and local secondary indexes are deferred to phase 2

## Architectural Direction

The implemented design direction is:

- `TableStage4` emits responses, raw consumption events, and metric events
- additive request-priced usage is folded into `DynamoDbUsageTotals`
- duration-based storage usage is derived from timed consumption streams into `DynamoDbTimeBasedUsageTotals`
- pricing is computed downstream from those two usage layers
- demo reporting preserves raw per-tick records and derives windowed records downstream
- Grafana reads staged Postgres-backed records rather than reading raw files directly

## Key Code Locations

- [TableStage4.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStage4.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [UseCaseSampler.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/UseCaseSampler.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [DynamoDbUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)
- [rollup.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/rollup.scala)
- [report.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/report.scala)
- [OrderTrackingPhase2Demo.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2Demo.scala)
- [001-schema.sql](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/postgres/init/001-schema.sql)
- [order-tracking-phase2-dashboard.json](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/grafana/order-tracking-phase2-dashboard.json)

## Key Proof Tests

- [TableStage4GetItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4GetItemSpec.scala)
- [TableStage4PutItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4PutItemSpec.scala)
- [TableStage4UpdateItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4UpdateItemSpec.scala)
- [TableStage4DeleteItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4DeleteItemSpec.scala)
- [TableStage4UsageAggregationIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/usage/TableStage4UsageAggregationIntegrationSpec.scala)
- [TableStage4TimeBasedUsageIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/usage/TableStage4TimeBasedUsageIntegrationSpec.scala)
- [TableStage4PricingIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/pricing/TableStage4PricingIntegrationSpec.scala)
- [OrderTrackingPhase2DemoRunnerSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2DemoRunnerSpec.scala)
- [OrderTrackingPostgresBridgeSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPostgresBridgeSpec.scala)

## Current Operator Workflow

The current demo workflow is:

1. `docker compose up -d`
2. `generate` a batch to JSONL
3. `stage` that batch into Postgres
4. `view` the provisioned Grafana dashboard
5. select a staged `batch_id` and a `Window Size` of `60` or `300`

## Recommended Next Work

Phase 1 is now effectively complete. Remaining work is documentation and operator polish only.

For phase 2:

1. introduce the public **table-and-indexes** component as the new phase-2 composition target
2. add internal index-state ownership and write propagation from the base table into GSIs and LSIs
3. add index-aware read execution, starting with `Query` and then `Scan`
4. extend accounting, pricing, export, and reporting so indexed-table scenarios can produce usage and cost ranges
5. treat [ips-phase2.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/roadmaps/ips-phase2.md) as the canonical planning anchor for future phase-2 work

## Notes For A Fresh Session

- the mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- countable usage is priced from totals, while storage-like duration pricing is derived from timed streams
- raw per-tick records remain the source of truth, while windowed records are derived for reporting and dashboard use
- per-window values are reporting artifacts, not authoritative billed prices
- if the next session starts by discussing indexes, use the revised phase-2 roadmap as the planning anchor
