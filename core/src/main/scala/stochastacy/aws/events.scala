package stochastacy.aws

import stochastacy.graphs.TimedEvent

/** An AWS service request is a type of timed event. All AWS requests are represented by subtypes of this type. */
trait AWSServiceRequestEvent extends TimedEvent

/** An AWS service response is also a type of timed event. All AWS responses are represented by subtypes
 * of this type. */
trait AWSServiceResponseEvent extends TimedEvent

/**
 * A resource consumption event is a timed event describing a fact of resources consumed during
 * graph execution. */
trait ResourceConsumptionEvent extends TimedEvent

/**
 * A metrics event is a timed event describing a deduced, typically aggregate fact */
trait MetricEvent extends TimedEvent
