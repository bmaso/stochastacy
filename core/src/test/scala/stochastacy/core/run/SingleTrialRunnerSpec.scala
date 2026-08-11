package stochastacy.core.run

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.core.component.{Emission, RequestResponseSampler, ResidueSummary, ScheduleReleaseTransducer, Scheduled}
import stochastacy.core.stream.TickFraming
import stochastacy.sim.{SimTime, TimedEvent}

class SingleTrialRunnerSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("SingleTrialRunnerSpec")
  override def afterAll(): Unit = system.terminate()

  private final case class Req(eventTime: SimTime, override val intraTick: Double = 0.0, usecase: Any = "r")
      extends TimedEvent

  // Counts requests via Int state; one in-horizon response + consumption per request.
  private val sampler = new RequestResponseSampler[Int, Req, String, String]:
    def initialState: Int = 0
    def sample(req: Req, s: Int, rng: UniformRandomProvider): Emission[Int, String, String] =
      Emission(s + 1, Scheduled("resp", 0.0), List(Scheduled("cons", 0.0)))

  "SingleTrialRunner" should {
    "run a source through a component and yield a TrialResult with final state and duration" in {
      val reqs      = Vector(Req(SimTime.of(1)), Req(SimTime.of(2)), Req(SimTime.of(2)))
      val source    = TickFraming.frameSource(reqs.iterator, 5L)
      val component = ScheduleReleaseTransducer.componentOf(sampler, RandomSource.KISS.create(1L))

      val result = Await.result(SingleTrialRunner.run(source, component, 5L), 5.seconds)

      result.finalState shouldBe 3
      result.durationTicks shouldBe 5L
      result.residue shouldBe ResidueSummary.empty // latency 0 → nothing post-horizon
    }
  }
