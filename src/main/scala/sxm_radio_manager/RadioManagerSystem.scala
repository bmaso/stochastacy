package sxm_radio_manager

import org.apache.pekko.stream.scaladsl.Source
import stochastacy.TimeWindowedEvents
import stochastacy.aws.ddb.{GetItemInteractionBehaviorProfile, Table}

class RadioManagerSystem:

  case class RadioManagerInitialState(n: Int)

  case class RadioManagerTable(initialState: RadioManagerInitialState) extends Table[RadioManagerInitialState](initialState):
    override type RequestInteractionEvents = RadioManagerRequestEvents
    override type ResponseInteractionEvents = RadioManagerResponseEvents

    sealed trait RadioManagerRequestEvents extends TimeWindowedEvents
    sealed trait RadioManagerResponseEvents extends TimeWindowedEvents

    trait GetDeviceRecordByIdRequestEvents extends RadioManagerRequestEvents
    trait GetDeviceRecordByIdResponseEvents extends RadioManagerResponseEvents

