package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.examples.demo.*

/**
 * Reconciliation gate for the **4-table capstone** against a captured legacy `ThermostatFleetCapstoneConfig`
 * baseline. The legacy is unreferenceable from this module, so we compare against pinned per-table means.
 *
 * **What reconciles cleanly.** Every table's **read path** (`TotalReadCapacityUnits`) matches within ~2 %, and
 * the two purely on-demand tables (Registry, Alerts) reconcile within ~7 % on WCU / storage / cost. The v2
 * write path runs a few percent *below* the legacy on saturated on-demand tables (an overwrite maintains an
 * unchanged GSI entry as a no-op, which v2 resolves slightly more often).
 *
 * **Documented divergences — the "we improved the model" cases (phase-2/6 pattern), all bounded & directional:**
 *   - **Commands WCU/cost run ~8 % *above* the legacy** — a transactional write bills its **synchronous LSI**
 *     maintenance 2× (AWS-accurate, phase 8), where the legacy billed all index maintenance 1×.
 *   - **Telemetry storage runs ~43 % *below* the legacy** — v2 TTL frees **base + GSI + LSI** storage, where the
 *     legacy freed only the base table (phase 7).
 *   - **Telemetry cost runs ~72 % *below* the legacy** — v2 bills the provisioned reservation by capacity-hours,
 *     not by would-be consumption (the same clean per-tick attribution as the phase-6 mixed-mode gate).
 *   - **Telemetry WCU runs ~15 % *above* the legacy** — under TTL the fleet's item count is held below
 *     saturation, so a larger fraction of writes are inserts (each maintaining every GSI), interacting with the
 *     more-complete v2 TTL model.
 *
 * Provisioned capacity-ticks / throttle count / PITR cost are v2 summary additions the legacy summary does not
 * emit, so they are exercised by `ThermostatCapstoneSpec`, not compared here.
 *
 * **Performance note.** At the reconcile scale (5 000 devices × 1440 ticks × 30 trials) the v2 capstone ran in
 * ~24 s vs the legacy's ~165 s — v2 is ~7× faster (its streaming transducer vs the legacy multi-stage pipeline).
 * Baseline captured 2026-09-01 via `ThermostatFleetBridge generate --mode capstone --trial-count 30
 * --simulation-ticks 1440` (5 k-device default).
 */
class ThermostatCapstoneReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatCapstoneReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /** Captured legacy per-table across-trial means (RCU, WCU, FinalStorageBytes, TotalEstimatedCost). */
  private object Legacy:
    val registry  = Map("rcu" -> BigDecimal("3192.95"), "wcu" -> BigDecimal("31210.57"), "storage" -> BigDecimal("4810122.27"), "cost" -> BigDecimal("0.03981222"))
    val telemetry = Map("rcu" -> BigDecimal("350.62"),  "wcu" -> BigDecimal("718158.10"), "storage" -> BigDecimal("10693833.73"), "cost" -> BigDecimal("0.89778762"))
    val commands  = Map("rcu" -> BigDecimal("3620.45"), "wcu" -> BigDecimal("93380.40"),  "storage" -> BigDecimal("12709207.77"), "cost" -> BigDecimal("0.11763225"))
    val alerts    = Map("rcu" -> BigDecimal("899.13"),  "wcu" -> BigDecimal("307939.03"), "storage" -> BigDecimal("6100735.70"),  "cost" -> BigDecimal("0.38515006"))

  private lazy val result: MultiTableMonteCarloResult =
    Await.result(new MultiTableMonteCarloRunner().run(ThermostatMultiTableConfig.capstone(5000L).withEnsemble(30, 1440, 4), masterSeed = 20260418L), 20.minutes)

  private def mean(tableName: String, metric: String): BigDecimal =
    result.perTable.find(_.tableName == tableName)
      .flatMap(_.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v })
      .getOrElse(fail(s"missing aggregate mean for $tableName / $metric"))

  private def rcu(t: String)     = mean(t, "TotalReadCapacityUnits")
  private def wcu(t: String)     = mean(t, "TotalWriteCapacityUnits")
  private def storage(t: String) = mean(t, "FinalStorageBytes")
  private def cost(t: String)    = mean(t, "TotalEstimatedCost")

  /** `actual` within `tol` (fractional) of `expected`. */
  private def near(actual: BigDecimal, expected: BigDecimal, tol: BigDecimal): Boolean =
    expected != 0 && (actual / expected - 1).abs <= tol

  "The 4-table capstone, reconciled against the legacy," should {

    "reconcile every table's read path (RCU) within ~2 %" in {
      near(rcu("device-registry"),  Legacy.registry("rcu"),  BigDecimal("0.03")) shouldBe true
      near(rcu("device-telemetry"), Legacy.telemetry("rcu"), BigDecimal("0.03")) shouldBe true
      near(rcu("device-commands"),  Legacy.commands("rcu"),  BigDecimal("0.03")) shouldBe true
      near(rcu("device-alerts"),    Legacy.alerts("rcu"),    BigDecimal("0.03")) shouldBe true
    }

    "reconcile the on-demand Registry and Alerts tables within ~8 % (WCU / storage / cost)" in {
      for (t, base) <- Seq(("device-registry", Legacy.registry), ("device-alerts", Legacy.alerts)) do
        near(wcu(t),     base("wcu"),     BigDecimal("0.08")) shouldBe true
        near(storage(t), base("storage"), BigDecimal("0.08")) shouldBe true
        near(cost(t),    base("cost"),    BigDecimal("0.08")) shouldBe true
    }

    "show the Commands transaction premium — WCU/cost above the legacy (LSI billed 2×)" in {
      // v2 is AWS-accurate: base + synchronous LSI maintenance doubled; the legacy billed indexes 1×.
      wcu("device-commands")  should be > Legacy.commands("wcu")
      near(wcu("device-commands"),  Legacy.commands("wcu"),  BigDecimal("0.12")) shouldBe true // bounded ~+8 %
      cost("device-commands") should be > Legacy.commands("cost")
      near(storage("device-commands"), Legacy.commands("storage"), BigDecimal("0.10")) shouldBe true
    }

    "document the Telemetry divergences — TTL frees index storage, provisioned billing by reservation" in {
      val s = storage("device-telemetry") / Legacy.telemetry("storage")
      s should (be > BigDecimal("0.45") and be < BigDecimal("0.65")) // ~43 % lower: base+GSI+LSI TTL freeing
      val c = cost("device-telemetry") / Legacy.telemetry("cost")
      c should (be > BigDecimal("0.20") and be < BigDecimal("0.40")) // ~72 % lower: provisioned capacity-hours
      near(wcu("device-telemetry"), Legacy.telemetry("wcu"), BigDecimal("0.20")) shouldBe true // bounded ~+15 %
    }
  }
