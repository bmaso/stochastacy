package stochastacy.aws.dynamodb.table

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import stochastacy.sim.SimTime

class ReconfigurationScheduleSpec extends AnyWordSpec with should.Matchers:

  "ReconfigurationSchedule" should {
    "accept an ordered valid schedule" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(10L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(10L, 10L)
          ),
          DynamoDbManagementEvent.UpdateProvisionedCapacity(
            SimTime.of(20L),
            "scale-up",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      schedule.validateAgainst(DynamoDbTable.BillingMode.OnDemand(), 100L) shouldBe Right(schedule)
    }

    "reject duplicate ticks" in {
      the[IllegalArgumentException] thrownBy
        ReconfigurationSchedule(
          Vector(
            DynamoDbManagementEvent.SwitchBillingMode(
              SimTime.of(10L),
              "switch-1",
              DynamoDbTable.BillingMode.Provisioned(10L, 10L)
            ),
            DynamoDbManagementEvent.UpdateProvisionedCapacity(
              SimTime.of(10L),
              "scale-up",
              DynamoDbTable.BillingMode.Provisioned(20L, 20L)
            )
          )
        )
    }

    "reject out-of-order ticks" in {
      the[IllegalArgumentException] thrownBy
        ReconfigurationSchedule(
          Vector(
            DynamoDbManagementEvent.SwitchBillingMode(
              SimTime.of(20L),
              "switch",
              DynamoDbTable.BillingMode.Provisioned(10L, 10L)
            ),
            DynamoDbManagementEvent.UpdateProvisionedCapacity(
              SimTime.of(10L),
              "scale-up",
              DynamoDbTable.BillingMode.Provisioned(20L, 20L)
            )
          )
        )
    }

    "reject ticks beyond the simulation horizon" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(101L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(10L, 10L)
          )
        )
      )

      schedule.validateAgainst(DynamoDbTable.BillingMode.OnDemand(), 100L) match
        case Left(message) => message should include("<= simulationTicks")
        case Right(_) => fail("expected validation failure for out-of-horizon tick")
    }

    "reject mode switches inside the cooldown window" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(10L),
            "switch-1",
            DynamoDbTable.BillingMode.Provisioned(10L, 10L)
          ),
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(100L),
            "switch-2",
            DynamoDbTable.BillingMode.OnDemand()
          )
        )
      )

      schedule.validateAgainst(DynamoDbTable.BillingMode.OnDemand(), 1000L) match
        case Left(message) => message should include("separated by at least")
        case Right(_) => fail("expected validation failure for cooldown violation")
    }

    "reject UpdateProvisionedCapacity before any switch to provisioned when starting on-demand" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.UpdateProvisionedCapacity(
            SimTime.of(10L),
            "scale-up",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      schedule.validateAgainst(DynamoDbTable.BillingMode.OnDemand(), 100L) match
        case Left(message) => message should include("provisioned billing mode")
        case Right(_) => fail("expected validation failure for invalid capacity update")
    }

    "accept UpdateProvisionedCapacity after a valid switch to provisioned" in {
      val schedule = ReconfigurationSchedule(
        Vector(
          DynamoDbManagementEvent.SwitchBillingMode(
            SimTime.of(10L),
            "switch",
            DynamoDbTable.BillingMode.Provisioned(10L, 10L)
          ),
          DynamoDbManagementEvent.UpdateProvisionedCapacity(
            SimTime.of(20L),
            "scale-up",
            DynamoDbTable.BillingMode.Provisioned(20L, 20L)
          )
        )
      )

      schedule.validateAgainst(DynamoDbTable.BillingMode.OnDemand(), 100L) shouldBe Right(schedule)
    }
  }
