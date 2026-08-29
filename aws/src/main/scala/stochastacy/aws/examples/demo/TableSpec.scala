package stochastacy.aws.examples.demo

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{BillingMode, DynamoDbRequest, GlobalSecondaryIndex, LocalSecondaryIndex, ReconfigurationSchedule, TableBehavior, TableSummaryState}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.StatelessSampler

/**
 * Everything **one table** contributes to a trial — its identity, initial contents, domain behavior, index
 * set, latency, pricing, system-error rate, and workload. This is the per-table unit shared by the
 * single-table and multi-table harnesses: a [[SingleTableScenario]] yields exactly one, a
 * [[MultiTableScenario]] carries a vector of them, and [[TableLegRunner]] turns one into a `TrialResult`.
 *
 * `arrivals` is self-contained (it bakes in its own tick horizon, exactly as the single-table scenario
 * does); the runner frames it against the ensemble's shared `simulationTicks`.
 */
final case class TableSpec(
  tableName:                     String,
  initialTableState:             TableSummaryState,
  behavior:                      TableBehavior,
  globalSecondaryIndexes:        Vector[GlobalSecondaryIndex],
  localSecondaryIndexes:         Vector[LocalSecondaryIndex],
  initialStorageBytesAllTargets: Long,
  latency:                       StatelessSampler[Double],
  rates:                         Rates,
  systemErrorRate:               Double,
  billingMode:                   BillingMode,
  reconfigurationSchedule:       ReconfigurationSchedule,
  arrivals:                      UniformRandomProvider => Vector[Timed[DynamoDbRequest]],
  ttlPeriodTicks:                Option[Int]                                            = None // item TTL in ticks (None = off)
):
  require(tableName.nonEmpty,               "tableName must be non-empty")
  require(systemErrorRate >= 0.0 && systemErrorRate < 1.0, "systemErrorRate must be in [0, 1)")
