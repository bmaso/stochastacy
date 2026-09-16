package stochastacy.examples.mm1

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.Inside.inside
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.sim.SimInstant

/** The FIFO server's exact mechanics: start = max(arrival, free), sojourn = queue wait + service, and the per-tick
 *  integrals of N(t) and busy time computed against hand-worked numbers. */
class ServerNodeSpec extends AnyWordSpec with should.Matchers:

  /** A server whose service time is always 0.25 ticks: `Exponential.draw(rate, rng)` is inverse-transform, so a fixed
   *  draw of `u` gives a fixed service time — this RNG returns the `u` with `-ln(1-u)/rate == 0.25`. */
  private def fixedServiceRng(service: Double, rate: Double): UniformRandomProvider =
    new UniformRandomProvider:
      // `nextLong` is the interface's only abstract method; everything else has a default.
      def nextLong(): Long              = 0L
      override def nextDouble(): Double = 1.0 - math.exp(-service * rate)

  // A service rate of 4 makes each service time a tidy 0.25 ticks; λ comes down to match, since MM1Config rightly
  // refuses an unstable server (at the default λ = 40 this would be ρ = 25). Here λ_eff = 1.0/0.4 = 2.5, ρ = 0.625.
  private val config  = MM1Config(sessionsPerTick = 1.0, serviceRate = 4.0)
  private val service = 0.25
  private val rng     = fixedServiceRng(service, config.serviceRate)
  private val node    = new ServerNode(config)

  private def at(t: Double): SimInstant = SimInstant(math.floor(t).toLong, t - math.floor(t))

  "ServerNode" should {

    "start a request on arrival when idle, and queue it behind the server otherwise" in {
      val first = node.sample(PageRequest(1L, 1, 1.0), at(1.0), node.initialState, rng)
      first.output.delay shouldBe (service +- 1e-9)          // no wait: served immediately
      first.newState.freeAt shouldBe (1.25 +- 1e-9)

      // A second request 0.1 ticks later must wait 0.15 for the server, then take its own 0.25.
      val second = node.sample(PageRequest(2L, 1, 1.1), at(1.1), first.newState, rng)
      second.output.delay shouldBe (0.4 +- 1e-9)
      second.newState.freeAt shouldBe (1.5 +- 1e-9)
      // Compare the fact's field with tolerance: 0.25 + 0.15 is 0.3999999999999999 in binary floating point.
      inside(second.consumption.map(_.event)) { case List(MM1Fact.PageServed(sojourn)) => sojourn shouldBe (0.4 +- 1e-9) }
    }

    "integrate N(t) and busy time exactly over a closed tick window" in {
      // Two requests inside tick 1: [1.0, 1.25] and [1.1, 1.5]. Over the window [1, 2):
      //   ∫N dt = 0.25 + 0.4 = 0.65     busy = the server runs 1.0 → 1.5 = 0.5
      val first  = node.sample(PageRequest(1L, 1, 1.0), at(1.0), node.initialState, rng)
      val second = node.sample(PageRequest(2L, 1, 1.1), at(1.1), first.newState, rng)
      val closed = node.onTick(2L, second.newState)

      inside(closed.consumption.map(_.event)) { case List(MM1Fact.WindowIntegral(window, inSystem, busy)) =>
        window shouldBe 1L
        inSystem shouldBe (0.65 +- 1e-9)
        busy shouldBe (0.5 +- 1e-9)
      }
      closed.newState.open shouldBe empty // both jobs ended inside the window
    }

    "carry an unfinished job into the next window and count only its overlap" in {
      // One request at 1.8 taking 0.25 → [1.8, 2.05]: 0.2 falls in tick 1, 0.05 in tick 2.
      val e       = node.sample(PageRequest(1L, 1, 1.8), at(1.8), node.initialState, rng)
      val closed1 = node.onTick(2L, e.newState)
      inside(closed1.consumption.map(_.event)) { case List(MM1Fact.WindowIntegral(window, inSystem, busy)) =>
        window shouldBe 1L
        inSystem shouldBe (0.2 +- 1e-9)
        busy shouldBe (0.2 +- 1e-9)
      }
      closed1.newState.open should have size 1 // still open: it ends in tick 2

      val closed2 = node.onTick(3L, closed1.newState)
      inside(closed2.consumption.map(_.event)) { case List(MM1Fact.WindowIntegral(window, inSystem, busy)) =>
        window shouldBe 2L
        inSystem shouldBe (0.05 +- 1e-9)
        busy shouldBe (0.05 +- 1e-9)
      }
      closed2.newState.open shouldBe empty
    }

    "emit nothing at the first tick, when no window has closed yet" in {
      node.onTick(1L, node.initialState).consumption shouldBe empty
    }
  }
