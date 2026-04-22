# IPS Hand-Off

Last updated: 2026-04-21

## Current Position

The project currently centers on a DynamoDB simulator that supports:

- base-table `GetItem`, `PutItem`, `UpdateItem`, and `DeleteItem`
- base-table and index-targeted `Query`
- base-table and index-targeted `Scan`
- a public `DynamoDbTable` table-and-indexes component
- internal GSI and LSI execution units
- internal index-state ownership and write propagation
- raw DynamoDB consumption events
- additive usage aggregation
- time-based storage usage aggregation from timed event streams
- downstream pricing from usage totals and time-based usage
- Monte Carlo multi-trial execution
- raw per-tick JSONL export
- derived `60s` and `300s` windowed JSONL export
- Postgres staging for demo records
- a provisioned Grafana dashboard
- overall demo reporting plus per-GSI consumed read/write reporting

The current runnable demo surface is the order-tracking phase-2 demo.

## Architectural Direction

The implemented design direction is:

- `TableStage4` remains the storage-facing execution core
- `DynamoDbTable` is the public table-and-indexes graph component
- GSIs and LSIs are represented as internal execution units, not separately wired public graph components
- additive request-priced usage is folded into `DynamoDbUsageTotals`
- duration-based storage usage is derived from timed consumption streams into `DynamoDbTimeBasedUsageTotals`
- pricing is computed downstream from those two usage layers
- demo reporting preserves raw per-tick records and derives windowed records downstream
- visible demo output preserves:
  - overall read and write capacity
  - per-GSI read and write capacity
  - overall-only storage and total cost
- Grafana reads staged Postgres-backed records rather than reading raw files directly

## Key Code Locations

- [DynamoDbTable.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/DynamoDbTable.scala)
- [TableStage4.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/TableStage4.scala)
- [state.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/state.scala)
- [UseCaseSampler.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/UseCaseSampler.scala)
- [op_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/op_events.scala)
- [consumption_events.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/table/consumption_events.scala)
- [DynamoDbUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbUsageTotals.scala)
- [DynamoDbTimeBasedUsageTotals.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/usage/DynamoDbTimeBasedUsageTotals.scala)
- [DynamoDbPricing.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/aws/dynamodb/pricing/DynamoDbPricing.scala)
- [model.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/model.scala)
- [rollup.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/rollup.scala)
- [report.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/main/scala/stochastacy/demo/report.scala)
- [OrderTrackingSingleTrialRunner.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingSingleTrialRunner.scala)
- [OrderTrackingPhase2Demo.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/main/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2Demo.scala)
- [001-schema.sql](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/postgres/init/001-schema.sql)
- [order-tracking-phase2-dashboard.json](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/grafana/order-tracking-phase2-dashboard.json)

## Key Proof Tests

- [TableStage4GetItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4GetItemSpec.scala)
- [TableStage4PutItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4PutItemSpec.scala)
- [TableStage4UpdateItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4UpdateItemSpec.scala)
- [TableStage4DeleteItemSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4DeleteItemSpec.scala)
- [TableStage4QuerySpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4QuerySpec.scala)
- [TableStage4ScanSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/TableStage4ScanSpec.scala)
- [DynamoDbTableComponentSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/table/DynamoDbTableComponentSpec.scala)
- [TableStage4PricingIntegrationSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/core/src/test/scala/stochastacy/aws/dynamodb/pricing/TableStage4PricingIntegrationSpec.scala)
- [OrderTrackingPhase2DemoRunnerSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPhase2DemoRunnerSpec.scala)
- [OrderTrackingPostgresBridgeSpec.scala](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/examples/src/test/scala/stochastacy/examples/ordertracking/OrderTrackingPostgresBridgeSpec.scala)

## Current Operator Workflow

The current demo workflow is:

1. `docker compose up -d`
2. `generate` a batch to JSONL through `OrderTrackingPhase2Bridge`
3. `stage` that batch into Postgres
4. `view` the provisioned Grafana dashboard
5. select a staged `batch_id`, a `Window Size` of `60` or `300`, and a `GSI Index Name` when inspecting per-GSI panels

## Recommended Next Work

The main remaining work is:

1. phase-2 demo finalization and polish on top of the current indexed-table demo path
2. any remaining documentation cleanup needed to reflect the now-canonical phase-2 demo surface
3. the narrow PartiQL parser/classification stub if that phase-2 item is still desired

Treat [ips-phase2.md](/Users/bmaso/projects/aws-cost-estimation/grafana-visualization/stochastacy/docs/roadmaps/ips-phase2.md) as the canonical planning anchor for ongoing simulator work.

## Notes For A Fresh Session

- the mutable table state is intentionally stochastic-summary-oriented, not key-accurate
- countable usage is priced from totals, while storage-like duration pricing is derived from timed streams
- raw per-tick records remain the source of truth, while windowed records are derived for reporting and dashboard use
- per-window values are reporting artifacts, not authoritative billed prices
- visible per-GSI reporting is for read/write capacity only; storage and cost remain overall-only in the demo
- if the next session starts with planning work, use the current handoff plus the phase-2 roadmap together rather than relying on older phase-specific demo notes
