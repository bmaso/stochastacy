package stochastacy.examples.store.v2

import stochastacy.core.component.Timed
import stochastacy.sim.SimTime
import stochastacy.examples.store.{Get, StoreRequest}

/** A fully **deterministic** spike-pattern workload for the burst experiment: `pattern(tick)`
 *  `get` requests per tick, arrivals spread evenly across the tick, no RNG. Two runs given the same
 *  pattern receive byte-identical traffic, so a flat cap and a token bucket can be compared on exactly
 *  the same bursts. */
object SpikeWorkload:

  def gets(pattern: Long => Int, simulationTicks: Long): Vector[Timed[StoreRequest]] =
    val b = Vector.newBuilder[Timed[StoreRequest]]
    (1L to simulationTicks).foreach { tick =>
      val n = pattern(tick)
      (0 until n).foreach { i =>
        b += Timed(Get(), SimTime.of(tick), i.toDouble / math.max(1, n), "get")
      }
    }
    b.result()
