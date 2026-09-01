package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{DynamoDbRequest, DynamoDbTarget, PutItemRequest, QueryRequest, ReadConsistency, ScanRequest, TransactWriteItemsRequest}
import stochastacy.core.component.Timed
import stochastacy.sim.ticks

class ThermostatWorkloadSpec extends AnyWordSpec with should.Matchers:

  private def run(config: ThermostatConfig, seed: Long): Vector[Timed[DynamoDbRequest]] =
    ThermostatWorkload.arrivals(config, RandomSource.KISS.create(seed))

  /** Telemetry PutItem counts grouped by tick. */
  private def telemetryByTick(arrivals: Vector[Timed[DynamoDbRequest]]): Map[Long, Int] =
    arrivals.collect { case Timed(_: PutItemRequest, t, _, _) => t.ticks }.groupBy(identity).view.mapValues(_.size).toMap

  /** Mean telemetry per tick over the (inclusive) tick window. */
  private def meanPerTick(byTick: Map[Long, Int], from: Long, to: Long): Double =
    (from to to).map(byTick.getOrElse(_, 0)).sum.toDouble / (to - from + 1).toDouble

  // A small, growing fleet with the temporal shaping OFF (multipliers 1.0, no storms), so per-flow means
  // reflect the plain fleet-scaled rate — the constant path that Slice 6 reconciles against.
  private val longConfig = ThermostatConfig(
    initialDeviceCount = 100L, deviceGrowthPerTick = 0.1, simulationTicks = 4000L,
    morningSpikePeakMultiplier = 1.0, eveningSpikePeakMultiplier = 1.0, alertStormProbabilityPerTick = 0.0
  )

  private val customerDevices = DynamoDbTarget.Gsi("customer-devices")
  private val fleetAlerts     = DynamoDbTarget.Gsi("fleet-alerts")

  // A large, constant fleet so per-tick telemetry means are stable and unconfounded by fleet growth.
  // baseline per tick = reportsPerDevicePerTick × fleetSize = 0.033 × 100000 = 3300.
  private val stableFleet = ThermostatConfig(
    initialDeviceCount = 100000L, deviceGrowthPerTick = 0.0, simulationTicks = 600L,
    morningSpikePeakMultiplier = 1.0, eveningSpikePeakMultiplier = 1.0,
    alertStormProbabilityPerTick = 0.0, polarVortexWriteMultiplier = 1.0
  )
  private val baseline = stableFleet.telemetryReportsPerDevicePerTick * stableFleet.initialDeviceCount.toDouble

  "ThermostatWorkload.arrivals (flat, unshaped rate)" should {

    "scale telemetry writes with the (growing) fleet" in {
      val arrivals   = run(longConfig, seed = 1L)
      val telemetry  = arrivals.count(_.event.isInstanceOf[PutItemRequest]).toDouble
      val fleetTicks = (1L to longConfig.simulationTicks).map(longConfig.fleetSize).sum.toDouble
      // total telemetry ≈ reportsPerDevicePerTick × Σ_t fleetSize(t)
      (telemetry / fleetTicks) shouldBe (longConfig.telemetryReportsPerDevicePerTick +- 0.003)
    }

    "emit the customer-support query and fleet-dashboard scan at their constant rates" in {
      val arrivals = run(longConfig, seed = 2L)
      val ticks    = longConfig.simulationTicks.toDouble
      arrivals.count { case Timed(QueryRequest(`customerDevices`, _), _, _, _) => true; case _ => false } / ticks shouldBe (0.5 +- 0.05)
      arrivals.count { case Timed(ScanRequest(`fleetAlerts`, _), _, _, _) => true; case _ => false }        / ticks shouldBe (0.1 +- 0.05)
    }

    "size telemetry items within mean ± variance" in {
      val bytes = run(longConfig, seed = 3L).collect { case Timed(PutItemRequest(b), _, _, _) => b }
      bytes should not be empty
      all(bytes) should (be >= 225L and be <= 375L) // 300 × [0.75, 1.25]
    }

    "read the GSIs eventually consistent, never the base or the LSI" in {
      val reads = run(longConfig, seed = 4L).collect {
        case Timed(q: QueryRequest, _, _, _) => (q.target, q.consistency)
        case Timed(s: ScanRequest, _, _, _)  => (s.target, s.consistency)
      }
      reads should not be empty
      reads.collect { case (DynamoDbTarget.Table, _) => () } shouldBe empty // no base reads at all
      all(reads.map(_._2)) shouldBe ReadConsistency.EventuallyConsistent
      reads.map(_._1).toSet shouldBe Set(customerDevices, fleetAlerts)
    }

    "land every event within [1, simulationTicks] in non-decreasing conceptual-time order, and be deterministic" in {
      val config   = longConfig.copy(simulationTicks = 100L)
      val arrivals = run(config, seed = 5L)
      all(arrivals.map(_.eventTime.ticks)) should (be >= 1L and be <= 100L)
      val times = arrivals.map(a => a.eventTime.ticks.toDouble + a.intraTick)
      times shouldBe times.sorted
      run(config, seed = 5L) shouldBe arrivals
    }
  }

  "ThermostatWorkload.arrivals (temporal shaping)" should {

    "apply the morning triangular spike — ~peakMultiplier at the window midpoint, ~1.5× over the window" in {
      val byTick = telemetryByTick(run(stableFleet.copy(morningSpikePeakMultiplier = 2.0, morningSpikePeakTickRange = (420L, 540L)), seed = 11L))
      // off-window is unshaped
      (meanPerTick(byTick, 100L, 300L) / baseline) shouldBe (1.0 +- 0.05)
      // window average of a triangle peaking at 2.0 is 1.5
      (meanPerTick(byTick, 420L, 540L) / baseline) shouldBe (1.5 +- 0.1)
      // the very centre approaches the full peak
      (meanPerTick(byTick, 478L, 482L) / baseline) shouldBe (2.0 +- 0.15)
    }

    "apply the polar-vortex window multiplier (1 + fraction·(mult−1)) inside its range, and nowhere else" in {
      val byTick = telemetryByTick(run(
        stableFleet.copy(polarVortexWriteMultiplier = 3.0, polarVortexAffectedFraction = 0.5, polarVortexTickRange = (200L, 400L)),
        seed = 12L))
      // in-window multiplier = 1 + 0.5·(3−1) = 2.0
      (meanPerTick(byTick, 200L, 400L) / baseline) shouldBe (2.0 +- 0.1)
      (meanPerTick(byTick, 450L, 550L) / baseline) shouldBe (1.0 +- 0.05)
    }

    "leave the rate flat when the vortex is off (multiplier 1.0), regardless of its range" in {
      val byTick = telemetryByTick(run(stableFleet.copy(polarVortexWriteMultiplier = 1.0, polarVortexTickRange = (200L, 400L)), seed = 13L))
      (meanPerTick(byTick, 200L, 400L) / baseline) shouldBe (1.0 +- 0.05)
    }

    "fire alert-storm bursts that add ~ (multiplier−1)× the base rate, for ~ their expected fraction of ticks" in {
      val stormy = stableFleet.copy(
        simulationTicks = 5000L,
        alertStormProbabilityPerTick = 0.01, alertStormDurationTicks = 20, alertStormWriteMultiplier = 5.0
      )
      val byTick     = telemetryByTick(run(stormy, seed = 14L))
      val perTick    = (1L to stormy.simulationTicks).map(byTick.getOrElse(_, 0).toDouble)
      val stormTicks = perTick.filter(_ > baseline * 1.5)
      val calmTicks  = perTick.filter(_ <= baseline * 1.5)

      stormTicks should not be empty
      // every storm tick carries the full 5× burst (base + 4× base), comfortably above 3× base
      all(stormTicks) should be > (baseline * 3.0)
      // calm ticks sit at the unshaped baseline
      (calmTicks.sum / calmTicks.size) shouldBe (baseline +- baseline * 0.05)
      // long-run active fraction ≈ duration / (duration + 1/probability) = 20 / (20 + 100) ≈ 0.167
      (stormTicks.size.toDouble / perTick.size.toDouble) shouldBe (0.167 +- 0.06)
    }

    "be deterministic under a fixed seed with shaping on" in {
      val shaped = stableFleet.copy(morningSpikePeakMultiplier = 2.0, alertStormProbabilityPerTick = 0.01)
      run(shaped, seed = 15L) shouldBe run(shaped, seed = 15L)
    }

    "emit command dispatches as TransactWriteItems when transactWriteItemsPerItemBytes is set" in {
      val cmd      = stableFleet.copy(simulationTicks = 20L, transactWriteItemsPerItemBytes = Some(Vector(200L, 150L)))
      val arrivals = run(cmd, seed = 1L)
      arrivals.exists { case Timed(_: TransactWriteItemsRequest, _, _, _) => true; case _ => false } shouldBe true
      arrivals.exists { case Timed(_: PutItemRequest, _, _, _)            => true; case _ => false } shouldBe false // no plain puts
    }

    "emit the same items as individual puts when useTransactions is off" in {
      val cmd      = stableFleet.copy(simulationTicks = 20L, transactWriteItemsPerItemBytes = Some(Vector(200L, 150L)), useTransactions = false)
      val arrivals = run(cmd, seed = 1L)
      arrivals.exists { case Timed(_: TransactWriteItemsRequest, _, _, _) => true; case _ => false } shouldBe false
      arrivals.count  { case Timed(_: PutItemRequest, _, _, _)            => true; case _ => false } should be > 0 // singles baseline
    }
  }
