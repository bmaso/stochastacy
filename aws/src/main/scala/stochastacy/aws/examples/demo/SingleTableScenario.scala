package stochastacy.aws.examples.demo

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GlobalSecondaryIndex, LocalSecondaryIndex, TableBehavior, TableSummaryState}
import stochastacy.core.component.Timed
import stochastacy.core.sampler.{LogNormalSampler, StatelessSampler}

/**
 * Everything a single-table demo supplies to the shared harness ([[SingleTableTrialRunner]] /
 * [[SingleTableMonteCarloRunner]]): the ensemble size, the table's initial state and index set, the
 * domain behavior, and the workload. Implement this once per demo domain (e.g. `OrderTrackingConfig`),
 * and the harness turns it into per-trial results, an across-trial aggregate, and JSONL.
 */
trait SingleTableScenario:
  def scenarioId:      String
  def simulationTicks: Long
  def trialCount:      Int
  def parallelism:     Int

  /** The table's starting summary state. */
  def initialTableState: TableSummaryState

  /** The domain behavior the generic table injects. */
  def behavior: TableBehavior

  def globalSecondaryIndexes: Vector[GlobalSecondaryIndex]
  def localSecondaryIndexes:  Vector[LocalSecondaryIndex]

  /** The pre-loaded storage across all targets (base + indexes) — the accounting seed. */
  def initialStorageBytesAllTargets: Long

  /** The full run's arrivals, in conceptual-time order. */
  def arrivals(rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]]

  /** Per-op service latency (fractional ticks); affects only response timing, never a total. */
  def latency: StatelessSampler[Double] = LogNormalSampler.constant(math.log(0.05), 0.5)

  /** On-demand pricing rates. */
  def rates: Rates = OnDemandPricing.phase1Default

  /** Load-independent per-request failure rate, applied by the harness as an inbound `ChaosGate` on the
   *  table's inlet (0.0 = no gate). A rejected request consumes no capacity and mutates no state. */
  def systemErrorRate: Double = 0.0

  /** This scenario as a single [[TableSpec]] — the per-table unit the shared harness runs. The table name
   *  defaults to the scenario id (single-table output is not table-prefixed, so it is not otherwise used). */
  def tableSpec: TableSpec = TableSpec(
    tableName                     = scenarioId,
    initialTableState             = initialTableState,
    behavior                      = behavior,
    globalSecondaryIndexes        = globalSecondaryIndexes,
    localSecondaryIndexes         = localSecondaryIndexes,
    initialStorageBytesAllTargets = initialStorageBytesAllTargets,
    latency                       = latency,
    rates                         = rates,
    systemErrorRate               = systemErrorRate,
    arrivals                      = arrivals
  )
