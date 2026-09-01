package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

/** The Commands table's transactions end to end: a device-command dispatch written as an atomic
 *  `TransactWriteItems` bills the **2× premium on the doubled targets** (base table + synchronous LSI) over
 *  the same items done as singles; the async GSI maintenance is billed 1× in both, so the doubling shows on
 *  the base+LSI portion. */
class ThermostatCommandsSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatCommandsSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // Write-focused command traffic (no queries/scans, shaping off, variance off → deterministic WCU per write).
  private val txn = ThermostatConfig(
    scenarioId = "cmd-txn-test", simulationTicks = 50L, trialCount = 6, parallelism = 3,
    initialDeviceCount = 2000L, deviceGrowthPerTick = 0.0, telemetryReportsPerDevicePerTick = 0.05,
    telemetryItemBytesVariance = 0.0, morningSpikePeakMultiplier = 1.0, eveningSpikePeakMultiplier = 1.0,
    alertStormProbabilityPerTick = 0.0, customerSupportQueryRatePerTick = 0.0, fleetDashboardScanRatePerTick = 0.0,
    systemErrorRate = 0.0, transactWriteItemsPerItemBytes = Some(Vector(200L, 150L)), useTransactions = true
  )
  private val singles = txn.copy(scenarioId = "cmd-singles-test", useTransactions = false)

  private def run(scenario: ThermostatConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(scenario, seed), 120.seconds)

  private def mean(result: MonteCarloResult, metric: String): BigDecimal =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }.getOrElse(BigDecimal(0))

  /** Base-table + LSI write capacity (the transaction-doubled portion): total WCU minus every GSI's WCU. */
  private def baseLsiWcu(result: MonteCarloResult): BigDecimal =
    val gsi = txn.globalSecondaryIndexes.map(g => mean(result, s"GSI:${g.indexName}:TotalWriteCapacityUnits")).sum
    mean(result, "TotalWriteCapacityUnits") - gsi

  "The Thermostat-fleet commands table, end to end," should {

    "bill the base+LSI write capacity ≈2× the equivalent single writes" in {
      val txnResult     = run(txn, seed = 1L)
      val singlesResult = run(singles, seed = 1L)
      baseLsiWcu(singlesResult) should be > BigDecimal(0)
      (baseLsiWcu(txnResult) / baseLsiWcu(singlesResult)) shouldBe (BigDecimal(2) +- BigDecimal("0.08"))
    }

    "bill GSI maintenance the same either way (transactions do not double async GSI back-fill)" in {
      val txnResult     = run(txn, seed = 2L)
      val singlesResult = run(singles, seed = 2L)
      val gsi           = "customer-devices"
      val ratio = mean(txnResult, s"GSI:$gsi:TotalWriteCapacityUnits") / mean(singlesResult, s"GSI:$gsi:TotalWriteCapacityUnits")
      ratio shouldBe (BigDecimal(1) +- BigDecimal("0.08"))
    }

    "be reproducible under a fixed seed" in {
      run(txn, seed = 7L) shouldBe run(txn, seed = 7L)
    }
  }
