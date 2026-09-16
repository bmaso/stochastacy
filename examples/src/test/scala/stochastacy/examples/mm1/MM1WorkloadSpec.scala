package stochastacy.examples.mm1

import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.sim.ticks

/** The session arrival process: the right rate, sorted within each tick (which the circuit's conceptual-time dispatch
 *  expects), and reproducible from its seed. */
class MM1WorkloadSpec extends AnyWordSpec with should.Matchers:

  private val config = MM1Config(sessionsPerTick = 40.0, simulationTicks = 400L)

  private def arrivals(seed: Long) = MM1Workload.arrivals(config, RandomSource.KISS.create(seed)).toVector

  "MM1Workload" should {

    "arrive at the configured rate" in {
      val all = arrivals(1L)
      val rate = all.size.toDouble / config.simulationTicks.toDouble
      rate shouldBe (config.sessionsPerTick +- 2.0) // ~16 000 arrivals, so the mean is tight
    }

    "be sorted by conceptual time, and therefore within each tick" in {
      val times = arrivals(2L).map(t => t.eventTime.ticks.toDouble + t.intraTick)
      times shouldBe times.sorted
      arrivals(2L).foreach { t =>
        t.intraTick should (be >= 0.0 and be < 1.0)
        t.eventTime.ticks should (be >= 1L and be <= config.simulationTicks)
      }
    }

    "number sessions consecutively and reproduce exactly from a seed" in {
      val first = arrivals(3L)
      first.map(_.event.id) shouldBe (0L until first.size.toLong).toVector
      arrivals(3L) shouldBe first
      arrivals(4L) should not be first
    }
  }
