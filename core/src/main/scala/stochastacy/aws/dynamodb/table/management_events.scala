package stochastacy.aws.dynamodb.table

import stochastacy.sim.{SimTime, TimedEvent}

sealed trait DynamoDbManagementEvent extends TimedEvent

object DynamoDbManagementEvent:
  final case class SwitchBillingMode(
    override val eventTime: SimTime,
    override val usecase: Any,
    newMode: DynamoDbTable.BillingMode
  ) extends DynamoDbManagementEvent
