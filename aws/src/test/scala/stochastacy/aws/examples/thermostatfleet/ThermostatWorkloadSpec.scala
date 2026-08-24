package stochastacy.aws.examples.thermostatfleet

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{DynamoDbRequest, DynamoDbTarget, PutItemRequest, QueryRequest, ReadConsistency, ScanRequest}
import stochastacy.core.component.Timed
import stochastacy.sim.ticks

class ThermostatWorkloadSpec extends AnyWordSpec with should.Matchers:

  private def run(config: ThermostatConfig, seed: Long): Vector[Timed[DynamoDbRequest]] =
    ThermostatWorkload.arrivals(config, RandomSource.KISS.create(seed))

  // A small, growing fleet over many ticks so per-flow means are stable and the vector stays manageable.
  private val longConfig = ThermostatConfig(initialDeviceCount = 100L, deviceGrowthPerTick = 0.1, simulationTicks = 4000L)

  private val customerDevices = DynamoDbTarget.Gsi("customer-devices")
  private val fleetAlerts     = DynamoDbTarget.Gsi("fleet-alerts")

  "ThermostatWorkload.arrivals" should {

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
