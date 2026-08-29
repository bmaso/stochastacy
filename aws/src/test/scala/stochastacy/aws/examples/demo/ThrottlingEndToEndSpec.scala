package stochastacy.aws.examples.demo

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.BillingMode
import stochastacy.aws.examples.ordertracking.OrderTrackingConfig
import stochastacy.core.run.SeedSequence

class ThrottlingEndToEndSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ThrottlingEndToEndSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val ticks    = OrderTrackingConfig.phase1Default.simulationTicks
  private val baseSpec = OrderTrackingConfig.phase1Default.tableSpec // ~2.4 writes/tick, no GSIs

  private def throttled(spec: TableSpec): Long =
    val Vector(w, t, g) = SeedSequence.derive(1L, 3): @unchecked
    Await.result(TableLegRunner.run(spec, ticks, w, t, g), 60.seconds).summary.totalThrottledRequests

  "Throttling, end to end through the accounting," should {

    "throttle some requests under a tight provisioned write ceiling" in {
      val tight = baseSpec.copy(billingMode = BillingMode.Provisioned(readCapacityUnits = 1000, writeCapacityUnits = 1))
      throttled(tight) should be > 0L
    }

    "throttle nothing under a loose provisioned ceiling" in {
      val loose = baseSpec.copy(billingMode = BillingMode.Provisioned(readCapacityUnits = 100000, writeCapacityUnits = 100000))
      throttled(loose) shouldBe 0L
    }

    "throttle nothing under on-demand billing" in {
      throttled(baseSpec) shouldBe 0L
    }
  }
