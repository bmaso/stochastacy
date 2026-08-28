package stochastacy.aws.examples.thermostatfleet

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.{BillingMode, ReconfigurationEvent, ReconfigurationSchedule, ScheduledReconfiguration}
import stochastacy.aws.examples.demo.*

class ThermostatMixedModeSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThermostatMixedModeSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  // A small mixed-mode config: on-demand, then a deliberately tight provisioned WCU from tick 10, widened at 20.
  private val mixed = ThermostatConfig(
    scenarioId = "mixed-test", simulationTicks = 30L, trialCount = 3, parallelism = 2,
    initialDeviceCount = 500L, deviceGrowthPerTick = 0.0,
    reconfigurationSchedule = ReconfigurationSchedule(Vector(
      ScheduledReconfiguration(10L, ReconfigurationEvent.SwitchBillingMode(BillingMode.Provisioned(1000L, 5L))),
      ScheduledReconfiguration(20L, ReconfigurationEvent.UpdateProvisionedCapacity(BillingMode.Provisioned(1000L, 50L)))
    ))
  )

  private def run(scenario: ThermostatConfig, seed: Long): MonteCarloResult =
    Await.result(new SingleTableMonteCarloRunner().run(scenario, seed), 90.seconds)

  private def mean(result: MonteCarloResult, metric: String): Option[BigDecimal] =
    result.aggregateSummary.collectFirst { case AggregateSummaryValue(`metric`, AggregateStatistic.Mean, v) => v }

  "The Thermostat-fleet mixed-mode demo, end to end," should {

    "reserve provisioned capacity and throttle once the tight ceiling takes effect" in {
      val result = run(mixed, seed = 1L)
      mean(result, "TotalProvisionedWriteCapacityUnitTicks").map(_ should be > BigDecimal(0))
      mean(result, "TotalThrottledRequests").map(_ should be > BigDecimal(0)) // heavy writes vs a 5 WCU base ceiling
      // consumed capacity is still reported alongside the provisioned reservation
      mean(result, "TotalWriteCapacityUnits").map(_ should be > BigDecimal(0))
    }

    "surface the provisioned/throttle metrics only for a provisioned ensemble (on-demand is unchanged)" in {
      val provisioned = run(mixed, seed = 2L)
      provisioned.aggregateSummary.map(_.metric) should contain("TotalThrottledRequests")

      val onDemand = run(mixed.copy(scenarioId = "od-test", reconfigurationSchedule = ReconfigurationSchedule.empty), seed = 2L)
      onDemand.aggregateSummary.map(_.metric) should not contain "TotalThrottledRequests"
      onDemand.aggregateSummary.map(_.metric) should not contain "TotalProvisionedWriteCapacityUnitTicks"
    }

    "be reproducible under a fixed seed" in {
      run(mixed, seed = 7L) shouldBe run(mixed, seed = 7L)
    }
  }
