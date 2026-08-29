package stochastacy.aws.examples.payments

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.{DynamoDbRequest, GlobalSecondaryIndex, LocalSecondaryIndex, TableBehavior, TableSummaryState}
import stochastacy.aws.examples.demo.SingleTableScenario
import stochastacy.core.component.Timed

/**
 * A **payments / double-entry ledger** scenario, purpose-built to exercise transactions: a service that
 * moves money between accounts. Each **transfer** is a single `TransactWriteItems` of two writes — a debit
 * and a credit — that must both commit or neither does, and each **balance check** is a `TransactGetItems`
 * reading the same accounts atomically. Because a transaction is a two-phase commit, both cost **2×** the
 * capacity of the equivalent single operations — the point the demo makes.
 *
 * The `useTransactions` flag toggles the *identical* workload between transactional and single-operation
 * form (transfers as `TransactWriteItems` vs. individual `UpdateItem`s; balance checks as
 * `TransactGetItems` vs. individual `GetItem`s), so the demo can bill the same work both ways and show the
 * exact 2× premium.
 *
 *   - the table is pre-loaded with `accountCount` account items of `accountBytes` each;
 *   - a transfer **updates** the accounts in place (same-size overwrite), so storage stays flat and the
 *     comparison isolates the **capacity** premium;
 *   - `transactWriteItemsPerItemBytes` is the transfer's per-item sizes (debit, credit).
 *
 * On-demand billing, no secondary indexes — the scenario isolates the transactional capacity effect.
 */
final case class PaymentsLedgerConfig(
  scenarioId:                     String       = "payments-ledger",
  simulationTicks:                Long         = 1200L,
  trialCount:                     Int          = 100,
  parallelism:                    Int          = 4,
  accountCount:                   Long         = 100000L,
  accountBytes:                   Long         = 400L,
  transfersPerTick:               Double       = 50.0,
  balanceChecksPerTick:           Double       = 30.0,
  transactWriteItemsPerItemBytes: Vector[Long] = Vector(200L, 150L),
  useTransactions:                Boolean      = true
) extends SingleTableScenario:
  require(scenarioId.nonEmpty,                              "scenarioId must be non-empty")
  require(simulationTicks >= 1L,                            "simulationTicks must be at least 1")
  require(trialCount >= 1,                                  "trialCount must be at least 1")
  require(parallelism >= 1,                                "parallelism must be at least 1")
  require(accountCount >= 1L,                               "accountCount must be at least 1")
  require(accountBytes >= 1L,                               "accountBytes must be at least 1")
  require(transfersPerTick >= 0.0,                          "transfersPerTick must be non-negative")
  require(balanceChecksPerTick >= 0.0,                      "balanceChecksPerTick must be non-negative")
  require(transactWriteItemsPerItemBytes.nonEmpty,         "transactWriteItemsPerItemBytes must be non-empty")
  require(transactWriteItemsPerItemBytes.forall(_ > 0L),   "transactWriteItemsPerItemBytes values must be positive")

  /** The number of items a transfer writes (and a balance check reads). */
  def itemsPerTransaction: Int = transactWriteItemsPerItemBytes.size

  def globalSecondaryIndexes: Vector[GlobalSecondaryIndex] = Vector.empty
  def localSecondaryIndexes:  Vector[LocalSecondaryIndex]  = Vector.empty

  /** Pre-loaded with the account population — transfers update these in place. */
  def initialTableState: TableSummaryState = TableSummaryState.initial(accountCount, accountBytes)
  def initialStorageBytesAllTargets: Long  = accountCount * accountBytes

  def behavior: TableBehavior = new PaymentsLedgerBehavior()

  def arrivals(rng: UniformRandomProvider): Vector[Timed[DynamoDbRequest]] =
    PaymentsLedgerWorkload.arrivals(this, rng)

object PaymentsLedgerConfig:
  /** The shipped preset: a transactional ledger. Its `useTransactions = false` twin is the single-write
   *  baseline the demo and the e2e compare against. */
  val default: PaymentsLedgerConfig = PaymentsLedgerConfig()
