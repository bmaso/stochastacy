package stochastacy.aws

import stochastacy.graphs.TimedEvent

/** An AWS service request is a type of timed event. All AWS requests are represented by subtypes of this type. */
trait AWSServiceRequestEvent extends TimedEvent

/** An AWS service response is also a type of timed event. All AWS responses are represented by subtypes
 * of this type. */
trait AWSServiceResponseEvent extends TimedEvent
  