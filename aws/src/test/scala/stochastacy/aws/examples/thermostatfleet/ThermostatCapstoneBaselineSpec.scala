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
 * Baseline (characterization) gate for the **4-table capstone**, pinning the demo's per-table behavior to an
 * established baseline captured from its own output.
 *
 * **What holds cleanly.** Every table's **read path** (`TotalReadCapacityUnits`) holds within ~2 %, and the two
 * purely on-demand tables (Registry, Alerts) hold within ~7 % on WCU / storage / cost. The write path on
 * saturated on-demand tables reflects overwrites that maintain an unchanged GSI entry as a no-op.
 *
 * **Documented model characteristics — all bounded & directional:**
 *   - **Commands carry a transaction premium** — a transactional write bills its **synchronous LSI** maintenance
 *     2× (AWS-accurate), so its WCU/cost sit ~8 % above a non-transactional baseline.
 *   - **Telemetry storage sits ~43 % below a no-TTL baseline** — TTL frees **base + GSI + LSI** storage.
 *   - **Telemetry cost sits ~72 % below a consumption baseline** — the provisioned reservation is billed by
 *     capacity-hours, not by would-be consumption (the same clean per-tick attribution as the mixed-mode gate).
 *   - **Telemetry WCU sits ~15 % above a no-TTL baseline** — under TTL the fleet's item count is held below
 *     saturation, so a larger fraction of writes are inserts (each maintaining every GSI).
 *
 * Provisioned capacity-ticks / throttle count / PITR cost are provisioned-mode / PITR additions, so they are
 * exercised by `ThermostatCapstoneSpec`, not compared here.
 *
 * **Performance note.** At the baseline scale (5 000 devices × 1440 ticks × 30 trials) the capstone ran in
 * ~24 s (its streaming transducer). Baseline captured 2026-09-01 from the demo's own aggregate-summary
 * `statistic:"mean"` records at that scale (5 k-device default); regenerate by running the demo's Monte Carlo
 * runner and reading those records.
 */
class ThermostatCapstoneBaselineSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatCapstoneBaselineSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  /** The demo's established per-table across-trial means (RCU, WCU, FinalStorageBytes, TotalEstimatedCost). */
  private object Baseline:
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

  "The 4-table capstone, pinned to its baseline," should {

    "hold every table's read path (RCU) to the baseline within ~2 %" in {
      near(rcu("device-registry"),  Baseline.registry("rcu"),  BigDecimal("0.03")) shouldBe true
      near(rcu("device-telemetry"), Baseline.telemetry("rcu"), BigDecimal("0.03")) shouldBe true
      near(rcu("device-commands"),  Baseline.commands("rcu"),  BigDecimal("0.03")) shouldBe true
      near(rcu("device-alerts"),    Baseline.alerts("rcu"),    BigDecimal("0.03")) shouldBe true
    }

    "hold the on-demand Registry and Alerts tables to the baseline within ~8 % (WCU / storage / cost)" in {
      for (t, base) <- Seq(("device-registry", Baseline.registry), ("device-alerts", Baseline.alerts)) do
        near(wcu(t),     base("wcu"),     BigDecimal("0.08")) shouldBe true
        near(storage(t), base("storage"), BigDecimal("0.08")) shouldBe true
        near(cost(t),    base("cost"),    BigDecimal("0.08")) shouldBe true
    }

    "show the Commands transaction premium — WCU/cost above the non-transactional baseline (LSI billed 2×)" in {
      // AWS-accurate: base + synchronous LSI maintenance doubled for a transactional write.
      wcu("device-commands")  should be > Baseline.commands("wcu")
      near(wcu("device-commands"),  Baseline.commands("wcu"),  BigDecimal("0.12")) shouldBe true // bounded ~+8 %
      cost("device-commands") should be > Baseline.commands("cost")
      near(storage("device-commands"), Baseline.commands("storage"), BigDecimal("0.10")) shouldBe true
    }

    "document the Telemetry characteristics — TTL frees index storage, provisioned billing by reservation" in {
      val s = storage("device-telemetry") / Baseline.telemetry("storage")
      s should (be > BigDecimal("0.45") and be < BigDecimal("0.65")) // ~43 % lower: base+GSI+LSI TTL freeing
      val c = cost("device-telemetry") / Baseline.telemetry("cost")
      c should (be > BigDecimal("0.20") and be < BigDecimal("0.40")) // ~72 % lower: provisioned capacity-hours
      near(wcu("device-telemetry"), Baseline.telemetry("wcu"), BigDecimal("0.20")) shouldBe true // bounded ~+15 %
    }
  }
