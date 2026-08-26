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

class ProvisionedPricingSpec extends AnyWordSpec with should.Matchers with BeforeAndAfterAll:

  private given system: ActorSystem = ActorSystem("ProvisionedPricingSpec")
  private given Materializer        = Materializer.matFromSystem
  private given ExecutionContext    = system.dispatcher
  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val rates    = Pricing.phase1Default
  private val ticks    = OrderTrackingConfig.phase1Default.simulationTicks // 30
  private val baseSpec = OrderTrackingConfig.phase1Default.tableSpec       // on-demand, no GSIs

  private def runLeg(spec: TableSpec, seed: Long): TrialResult =
    val Vector(w, t, g) = SeedSequence.derive(seed, 3): @unchecked
    Await.result(TableLegRunner.run(spec, ticks, w, t, g), 60.seconds)

  "Pricing.provisionedCost" should {
    "price reserved capacity-ticks as capacity-hours (ticks ÷ 3600 × hourly rate)" in {
      // 3600 RCU-ticks = 1 RCU-hour; 3600 WCU-ticks = 1 WCU-hour.
      Pricing.provisionedCost(BigInt(3600), BigInt(0), rates) shouldBe rates.provisionedRcuHourlyPrice
      Pricing.provisionedCost(BigInt(0), BigInt(3600), rates) shouldBe rates.provisionedWcuHourlyPrice
      Pricing.provisionedCost(BigInt(0), BigInt(0), rates)    shouldBe BigDecimal(0)
    }
  }

  "A provisioned table (Slice 1: no throttle yet)" should {

    "bill reserved capacity-hours plus storage — no consumption component" in {
      val spec   = baseSpec.copy(billingMode = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 50))
      val result = runLeg(spec, seed = 1L)
      val s      = result.summary
      // base-only (no GSIs): reserved capacity-ticks = capacity × ticks
      s.totalProvisionedReadCapacityUnitTicks  shouldBe BigInt(100 * ticks)
      s.totalProvisionedWriteCapacityUnitTicks shouldBe BigInt(50 * ticks)
      // cost = capacity-hours + storage; consumption is NOT billed under provisioned
      val expected = Pricing.provisionedCost(s.totalProvisionedReadCapacityUnitTicks, s.totalProvisionedWriteCapacityUnitTicks, rates) +
        Pricing.storageCost(s.totalStorageByteTicks, rates)
      s.totalEstimatedCost shouldBe expected
      s.totalReadCapacityUnits should be > BigDecimal(0) // still consumed + reported, just not billed
    }

    "reserve the same capacity regardless of the workload actually consumed" in {
      val spec = baseSpec.copy(billingMode = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 50))
      val a    = runLeg(spec, seed = 1L).summary
      val b    = runLeg(spec, seed = 2L).summary
      // reserved capacity-ticks are consumption-independent (fixed by capacity × ticks) ...
      a.totalProvisionedReadCapacityUnitTicks shouldBe b.totalProvisionedReadCapacityUnitTicks
      // ... even though the consumed capacity differs between the two workloads
      a.totalReadCapacityUnits should not be b.totalReadCapacityUnits
    }

    "reserve capacity per target — base plus every GSI" in {
      val prov    = BillingMode.Provisioned(readCapacityUnits = 100, writeCapacityUnits = 50)
      val withGsi = runLeg(OrderTrackingConfig.indexedDefault.tableSpec.copy(billingMode = prov), seed = 3L).summary // 2 GSIs
      val base    = runLeg(baseSpec.copy(billingMode = prov), seed = 3L).summary                                    // no GSIs
      // base + 2 GSIs = 3× the base-only reservation (independent of the workload)
      withGsi.totalProvisionedReadCapacityUnitTicks shouldBe (base.totalProvisionedReadCapacityUnitTicks * 3)
    }
  }

  "An on-demand table" should {
    "bill consumption + storage exactly as before (no provisioned component)" in {
      val result = runLeg(baseSpec, seed = 1L) // baseSpec is on-demand
      val s      = result.summary
      s.totalProvisionedReadCapacityUnitTicks  shouldBe BigInt(0)
      s.totalProvisionedWriteCapacityUnitTicks shouldBe BigInt(0)
      s.totalEstimatedCost shouldBe Pricing.cost(s.totalReadCapacityUnits, s.totalWriteCapacityUnits, s.totalStorageByteTicks, rates)
    }
  }
