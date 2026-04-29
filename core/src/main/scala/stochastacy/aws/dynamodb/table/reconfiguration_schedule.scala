package stochastacy.aws.dynamodb.table

import stochastacy.sim.ticks

final case class ReconfigurationSchedule(events: Vector[DynamoDbManagementEvent]):
  require(
    events.map(_.eventTime.ticks).zip(events.drop(1).map(_.eventTime.ticks)).forall { case (left, right) => left < right },
    "ReconfigurationSchedule events must be ordered by strictly increasing eventTime"
  )
  require(
    events.forall(_.eventTime.ticks > 0L),
    "ReconfigurationSchedule event ticks must be positive"
  )

  def validateAgainst(
                       initialBillingMode: DynamoDbTable.BillingMode,
                       simulationTicks: Long
                     ): Either[String, ReconfigurationSchedule] =
    if simulationTicks < 1L then
      Left(s"simulationTicks must be at least 1, got $simulationTicks")
    else if events.exists(_.eventTime.ticks > simulationTicks) then
      Left(s"ReconfigurationSchedule event ticks must be <= simulationTicks ($simulationTicks)")
    else
      validateContext(initialBillingMode).map(_ => this)

  private def validateContext(initialBillingMode: DynamoDbTable.BillingMode): Either[String, Unit] =
    events.foldLeft[Either[String, ReconfigurationSchedule.ScheduleState]](
      Right(ReconfigurationSchedule.ScheduleState(initialBillingMode, None))
    ) {
      case (left @ Left(_), _) => left
      case (Right(state), event) =>
        event match
          case switch: DynamoDbManagementEvent.SwitchBillingMode =>
            state.lastSwitchTick match
              case Some(lastTick) if switch.eventTime.ticks - lastTick < ReconfigurationSchedule.BillingModeSwitchCooldownTicks =>
                Left(
                  s"ReconfigurationSchedule SwitchBillingMode events must be separated by at least ${ReconfigurationSchedule.BillingModeSwitchCooldownTicks} ticks"
                )
              case _ =>
                Right(ReconfigurationSchedule.ScheduleState(switch.newMode, Some(switch.eventTime.ticks)))

          case update: DynamoDbManagementEvent.UpdateProvisionedCapacity =>
            state.billingMode match
              case _: DynamoDbTable.BillingMode.Provisioned =>
                Right(state.copy(billingMode = update.newCapacity))
              case _ =>
                Left("ReconfigurationSchedule UpdateProvisionedCapacity events require the table to be in provisioned billing mode at that tick")
    }.map(_ => ())

object ReconfigurationSchedule:
  val BillingModeSwitchCooldownTicks: Long = 86400L

  val empty: ReconfigurationSchedule = ReconfigurationSchedule(Vector.empty)

  private final case class ScheduleState(
                                          billingMode: DynamoDbTable.BillingMode,
                                          lastSwitchTick: Option[Long]
                                        )
