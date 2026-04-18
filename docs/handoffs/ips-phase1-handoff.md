# IPS Phase 1 Hand-Off

Last updated: 2026-04-17

## Current Position

`ips/phase1` is now centered on a table-only DynamoDB simulator for the initial public showing.

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

Phase-1 scope decision:

- indexes are no longer part of phase 1
- `Query`, `Scan`, and PartiQL queries are deferred to phase 2
- global secondary indexes and local secondary indexes are deferred to phase 2

## Architectural Direction

The current design direction is:

- `TableStage4` emits responses, raw consumption events, and metric events
- additive request-priced usage is folded into `DynamoDbUsageTotals`
- duration-based storage usage is derived from timed consumption streams into `DynamoDbTimeBasedUsageTotals`
- pricing is computed downstream from those two usage layers

This keeps execution, accounting, time-based occupancy, and pricing cleanly separated.

## Key Code Locations

- [TableStage4.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStage4.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [sample.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/sample.scala)
- [UseCaseSampler.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/UseCaseSampler.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [DynamoDbUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)

## Key Proof Tests

- [TableStage4GetItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4GetItemSpec.scala)
- [TableStage4PutItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4PutItemSpec.scala)
- [TableStage4UpdateItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4UpdateItemSpec.scala)
- [TableStage4DeleteItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4DeleteItemSpec.scala)
- [TableStage4UsageAggregationIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/usage/TableStage4UsageAggregationIntegrationSpec.scala)
- [TableStage4TimeBasedUsageIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/usage/TableStage4TimeBasedUsageIntegrationSpec.scala)
- [TableStage4PricingIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/pricing/TableStage4PricingIntegrationSpec.scala)

## Recommended Next Work

For phase 1:

1. build one strong end-to-end demo scenario around table operations only
2. add demo-facing output/reporting that summarizes responses, usage, pricing, and timing
3. tighten docs around what is intentionally stochastic rather than key-accurate

For phase 2:

1. add index architecture and table-plus-index composition
2. add `Query`, `Scan`, and PartiQL query support
3. add GSI and LSI modeling

## Notes For A Fresh Session

- the mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- countable usage is priced from totals, while storage-like duration pricing is derived from timed streams
- if the next session starts by discussing indexes, use the new phase-2 roadmap as the planning anchor
