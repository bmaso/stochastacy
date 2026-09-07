package stochastacy.aws.examples.hotreplica

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

/**
 * Reconciliation gate for the hot-replica demo's **reconcile arm** against the legacy
 * `thermostat-fleet-multi-region` demo (`ThermostatFleetScenarioConfig.multiRegionDefault`). The v2 arm reproduces
 * the legacy per-region telemetry tables — matched fleet sizes + growth (1800/+0.15, 900/+0.075, 300/+0.025),
 * per-region pricing, and on-demand billing. **RCU and WCU reconcile cleanly** (pinned), which validates that the
 * workload and the replication *volume* reproduce the legacy.
 *
 * **Storage and total cost are documented, bounded divergences — not hard-pinned** (guarded against regression):
 *   - **storage** diverges by a uniform ~16 % because of a **summary-model saturation-pollution** limitation: a
 *     region's insert-vs-overwrite heuristic reads state that includes replicated inbound items, so the converged
 *     per-region population deviates from the true key-space union — differently for v2 and the legacy. It is
 *     bounded and its **cost impact is negligible** (storage cost ≪ capacity cost). See "Known discrepancies" in
 *     `specs/aws-component-catalog.md`.
 *   - **cost** is *higher* in v2 because v2 prices rWCU at the **AWS-correct rate** (rWRU = WRU, per the AWS docs),
 *     whereas the legacy underprices rWCU at a flat rate matching no AWS rate; the gap is largest where rWCU
 *     dominates (ap-southeast). v2 is the more accurate side, so this is documented, not reconciled. (v2 also
 *     charges **no** cross-region transfer, which AWS does not bill for global-table replication — the legacy does.)
 *
 * Also documented, not pinned: the per-link `ReplicationLatency` / `PendingReplicationCount` coupling to rWCU
 * depletion (the *depletion* arm's showcase, which the legacy decouples), and the omitted ~0.1 % system-error
 * `ChaosGate` (it cannot sit inside the Global Table graph; absorbed in the WCU tolerance).
 *
 * The legacy code is unreferenceable from this module, so we compare against a **captured** baseline: the per-region
 * across-trial means of the legacy demo's `Region:<r>:…` trial-summary records.
 */
class HotReplicaReconciliationSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("HotReplicaReconciliationSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  import HotReplicaConfig.{UsEast, EuWest, ApSoutheast}

  /**
   * Per-region across-trial means from the legacy `thermostat-fleet-multi-region` demo at 1200 ticks × 20 trials
   * (a reduced ensemble — these aggregate-over-1200-ticks means are stable at 20). Captured 2026-09-07 via:
   *   sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate \
   *          --output /tmp/legacy-mr20.jsonl --mode multi-region --trial-count 20 --parallelism 8'
   * then averaging the `Region:<r>:{TotalReadCapacityUnits,TotalWriteCapacityUnits,FinalStorageBytes,
   * TotalEstimatedCost}` trial-summary records across trials. Regenerate if the legacy multi-region demo changes
   * before it is deleted.
   */
  private val LegacyBaseline: Map[String, Map[String, BigDecimal]] = Map(
    UsEast -> Map(
      "TotalReadCapacityUnits"  -> BigDecimal("746.35"),
      "TotalWriteCapacityUnits" -> BigDecimal("306071.4"),
      "FinalStorageBytes"       -> BigDecimal("2895433.4"),
      "TotalEstimatedCost"      -> BigDecimal("0.576772")
    ),
    EuWest -> Map(
      "TotalReadCapacityUnits"  -> BigDecimal("736.05"),
      "TotalWriteCapacityUnits" -> BigDecimal("151031.25"),
      "FinalStorageBytes"       -> BigDecimal("2930000.5"),
      "TotalEstimatedCost"      -> BigDecimal("0.555094")
    ),
    ApSoutheast -> Map(
      "TotalReadCapacityUnits"  -> BigDecimal("746.15"),
      "TotalWriteCapacityUnits" -> BigDecimal("49399.25"),
      "FinalStorageBytes"       -> BigDecimal("2895736.75"),
      "TotalEstimatedCost"      -> BigDecimal("0.525011")
    )
  )

  private val config  = HotReplicaConfig.reconcileDefault(simulationTicks = 1200L, trialCount = 20, parallelism = 8)
  private val regions = Vector(UsEast, EuWest, ApSoutheast)

  // Pinned dimensions — RCU/WCU reconcile cleanly (workload + replication volume).
  private val RcuTol = BigDecimal("0.08")
  private val WcuTol = BigDecimal("0.06")
  // Documented, bounded divergences (guarded against regression, not reconciled — see the class scaladoc and
  // the "Known discrepancies" section of specs/aws-component-catalog.md).
  private val StorageDivergenceBound = BigDecimal("0.25") // summary-model saturation-pollution (~16 %, negligible cost)
  private val CostDivergenceBound    = BigDecimal("0.70") // v2 prices rWCU at the AWS-correct WCU rate (legacy underprices)

  private lazy val result = Await.result(new HotReplicaMonteCarloRunner().run(config, masterSeed = 20260907L), 20.minutes)

  private def v2(region: String): (BigDecimal, BigDecimal, BigDecimal, BigDecimal) =
    val g = result.regions.find(_.regionName == region).getOrElse(fail(s"missing region $region"))
    (BigDecimal(g.meanRcu), BigDecimal(g.meanWcu), BigDecimal(g.meanFinalStorageBytes), g.meanTotalCost)

  private def legacy(region: String, metric: String): BigDecimal =
    LegacyBaseline(region)(metric)

  private def relDiff(actual: BigDecimal, expected: BigDecimal): BigDecimal =
    if expected == 0 then (if actual == 0 then BigDecimal(0) else BigDecimal(1)) else (actual - expected).abs / expected.abs

  "The hot-replica reconcile arm" should {
    "match the legacy per-region mean read capacity units within tolerance" in {
      regions.foreach { r =>
        withClue(s"$r RCU: ") { relDiff(v2(r)._1, legacy(r, "TotalReadCapacityUnits")) should be <= RcuTol }
      }
    }
    "match the legacy per-region mean write capacity units within tolerance" in {
      regions.foreach { r =>
        withClue(s"$r WCU: ") { relDiff(v2(r)._2, legacy(r, "TotalWriteCapacityUnits")) should be <= WcuTol }
      }
    }
    "keep the storage divergence within its documented bound (summary-model saturation-pollution)" in {
      regions.foreach { r =>
        withClue(s"$r storage: ") { relDiff(v2(r)._3, legacy(r, "FinalStorageBytes")) should be <= StorageDivergenceBound }
      }
    }
    "keep the cost divergence within its documented bound (v2 prices rWCU at the AWS-correct WCU rate)" in {
      regions.foreach { r =>
        withClue(s"$r cost: ") { relDiff(v2(r)._4, legacy(r, "TotalEstimatedCost")) should be <= CostDivergenceBound }
      }
    }
    "report the measured per-region gaps for transparency" in {
      regions.foreach { r =>
        val (rcu, wcu, storage, cost) = v2(r)
        info(f"$r%-14s RCU ${(relDiff(rcu, legacy(r, "TotalReadCapacityUnits")) * 100).toDouble}%+.1f%%  " +
             f"WCU ${(relDiff(wcu, legacy(r, "TotalWriteCapacityUnits")) * 100).toDouble}%+.1f%%  " +
             f"storage ${(relDiff(storage, legacy(r, "FinalStorageBytes")) * 100).toDouble}%+.1f%%  " +
             f"cost ${(relDiff(cost, legacy(r, "TotalEstimatedCost")) * 100).toDouble}%+.1f%%")
      }
      succeed
    }
  }
