package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.core.sampler.LogNormalSampler

class ReconfigurationSpec extends AnyWordSpec with should.Matchers:

  import ReconfigurationEvent.*
  private val prov250 = BillingMode.Provisioned(250, 125)
  private val prov100 = BillingMode.Provisioned(100, 333)

  private val mixed = ReconfigurationSchedule(Vector(
    ScheduledReconfiguration(400L, SwitchBillingMode(prov250)),
    ScheduledReconfiguration(800L, UpdateProvisionedCapacity(prov100))
  ))

  "ReconfigurationSchedule.validate" should {
    "accept a valid on-demand → provisioned → update schedule" in {
      mixed.validate(BillingMode.OnDemand, simulationTicks = 1200L) shouldBe Right(mixed)
    }
    "reject two billing-mode switches within the cooldown" in {
      val s = ReconfigurationSchedule(Vector(
        ScheduledReconfiguration(400L, SwitchBillingMode(prov250)),
        ScheduledReconfiguration(500L, SwitchBillingMode(BillingMode.OnDemand))
      ))
      s.validate(BillingMode.OnDemand, 1200L).isLeft shouldBe true
    }
    "reject a capacity update while on-demand" in {
      val s = ReconfigurationSchedule(Vector(ScheduledReconfiguration(400L, UpdateProvisionedCapacity(prov100))))
      s.validate(BillingMode.OnDemand, 1200L).isLeft shouldBe true
    }
    "reject entries past the horizon" in {
      mixed.validate(BillingMode.OnDemand, simulationTicks = 700L).isLeft shouldBe true
    }
  }

  "ReconfigurationSchedule.billingModeAt" should {
    "return the mode in force at each tick across the boundaries" in {
      mixed.billingModeAt(399L, BillingMode.OnDemand) shouldBe BillingMode.OnDemand
      mixed.billingModeAt(400L, BillingMode.OnDemand) shouldBe prov250
      mixed.billingModeAt(799L, BillingMode.OnDemand) shouldBe prov250
      mixed.billingModeAt(800L, BillingMode.OnDemand) shouldBe prov100
      mixed.billingModeAt(1200L, BillingMode.OnDemand) shouldBe prov100
    }
  }

  "A table with a reconfiguration schedule" should {
    val putBehavior = new TableBehavior:
      def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
        request match
          case PutItemRequest(bytes) => OperationOutcome.Put(bytes, None)
          case other                 => throw new IllegalArgumentException(s"unexpected $other")
    val rng: UniformRandomProvider = RandomSource.KISS.create(1L)

    "start on-demand (uncapped), then throttle after switching to a tight provisioned ceiling" in {
      val schedule = ReconfigurationSchedule(Vector(
        ScheduledReconfiguration(5L, SwitchBillingMode(BillingMode.Provisioned(100, 2))), // base 2 WCU/tick
        ScheduledReconfiguration(9L, UpdateProvisionedCapacity(BillingMode.Provisioned(100, 4))) // widen to 4
      ))
      val s = new DynamoDbTable.DynamoDbTableSampler(DynamoDbTable.Config(
        initialState = TableSummaryState.empty, behavior = putBehavior,
        latency = LogNormalSampler.constant(math.log(0.01), 0.0),
        billingMode = BillingMode.OnDemand, reconfigurationSchedule = schedule
      ))
      var st = s.initialState
      def put() = { val e = s.sample(PutItemRequest(1024L), st, rng); st = e.newState; e }

      // on-demand: no cap — 5 writes in a tick all admit
      (1 to 5).foreach(_ => put().output.event shouldBe a[PutItemResponse])

      st = s.onTick(5L, st) // switch to Provisioned(2 WCU)
      put().output.event shouldBe a[PutItemResponse] // 1
      put().output.event shouldBe a[PutItemResponse] // 2
      put().output.event shouldBe ThrottledResponse  // 3rd over the 2 WCU ceiling

      st = s.onTick(9L, st) // capacity widened to 4 WCU
      (1 to 4).foreach(_ => put().output.event shouldBe a[PutItemResponse]) // 4 now fit
      put().output.event shouldBe ThrottledResponse                        // 5th over the 4 WCU ceiling
    }
  }
