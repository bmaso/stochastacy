package stochastacy.aws.examples.payments

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

/** The payments-ledger transactions demo end to end: the transactional workload bills ≈2× the capacity of
 *  the identical single-operation workload (two-phase commit), storage stays flat (same-size overwrites),
 *  and the run is reproducible. Small/fast — the full-scale run lives in the `@main` demo. */
class PaymentsLedgerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("PaymentsLedgerSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val txn = PaymentsLedgerConfig(
    scenarioId = "ledger-txn-test", simulationTicks = 40L, trialCount = 6, parallelism = 3,
    accountCount = 10000L, accountBytes = 400L, transfersPerTick = 40.0, balanceChecksPerTick = 25.0,
    transactWriteItemsPerItemBytes = Vector(200L, 150L), useTransactions = true
  )
  private val singles = txn.copy(scenarioId = "ledger-singles-test", useTransactions = false)

  private def run(scenario: PaymentsLedgerConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(scenario, seed), 120.seconds)

  private def mean(result: MonteCarloResult, metric: String): BigDecimal =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  "The payments-ledger transactions demo, end to end," should {

    "bill transactional writes ≈2× the equivalent single writes" in {
      val txnWcu     = mean(run(txn, seed = 1L), "TotalWriteCapacityUnits")
      val singlesWcu = mean(run(singles, seed = 1L), "TotalWriteCapacityUnits")
      singlesWcu should be > BigDecimal(0)
      (txnWcu / singlesWcu) shouldBe (BigDecimal(2) +- BigDecimal("0.06")) // two-phase commit premium
    }

    "bill transactional reads ≈2× the equivalent single reads" in {
      val txnRcu     = mean(run(txn, seed = 2L), "TotalReadCapacityUnits")
      val singlesRcu = mean(run(singles, seed = 2L), "TotalReadCapacityUnits")
      singlesRcu should be > BigDecimal(0)
      (txnRcu / singlesRcu) shouldBe (BigDecimal(2) +- BigDecimal("0.06"))
    }

    "keep storage flat — transfers overwrite same-size balances" in {
      val result       = run(txn, seed = 3L)
      val finalStorage = mean(result, "FinalStorageBytes")
      val initial      = BigDecimal(txn.initialStorageBytesAllTargets)
      (finalStorage - initial).abs should be <= (initial * BigDecimal("0.01"))
    }

    "be reproducible under a fixed seed" in {
      run(txn, seed = 7L) shouldBe run(txn, seed = 7L)
    }
  }
