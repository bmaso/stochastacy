package stochastacy.aws

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import stochastacy.aws.events.TimeWindowResourceConsumptionEvent

import scala.concurrent.Future

/**
 * An `AWSRegionGraph` is fluent a graph builder for building Stochastacy simulations of AWS-based systems.
 * A Stochastacy simulation is a statistically-driven simulation of a real system AWS system. Once defined,
 * the simulation graph generates a simulation of resources consumed by the system over time, including
 * AWS resources as well as cost. Such a simulation can be used to estimate performance and costs
 * to run the simulated system over time.
 *
 * The simulation consists of a stream of time-window-based consumption events. Each such event is a
 * collection of resource consumptions events organized into discrete, contiguous time windows. The consumption
 * events match the types of resources discovered in the AWS Pricing API.
 *
 * ## Building a Graph
 *
 * The `provisionXYZ` methods are used to declare AWS configurable resources within the graph, such as
 * DDB tables, DDB storage units, SQS queues, ElastiCache caches, etc. AWS resources by default consume nothing
 * but time-based resources. For example, if your simulation includes nothing but an ElasitCache cache that
 * no one writes to or reads from, then the simulation will produce nothing but ElastiCache-Hours consumption
 * events.
 *
 * Almost all AWS resources exist to be interacted with. An interaction will typically generate interaction-
 * specific consumption events. For example, a `GetItem` interaction with a DDB table generates RCU
 * consumption events.
 *
 * Resources receive interactions through resource-specific sinks. For example, a DDB Table has a
 * `GetItem` sink, through which it receives `GetItem` interactions. During the course of building up a
 * graph you can connect your own sources to AWS resource sinks. The graph builder API also presents
 * methods for connecting resources to each other. For example, you can connect a DDB table's change
 * stream (represented in the graph as a source) to a Lambda function.
 *
 * ## Running a Simulation
 *
 * The `runSimulationTo` method will execute the simulation graph you have defined over the time window
 * you define. The simulation will send all consumption events for all resources in the graph to a sink you
 * provide. The simulation's time window is virtualized. It will take orders of magnitude less time
 * to complete the simulation than it would to actually execute an equivalent graph on an AWS cloud or local
 * cloud simulator (such as localstack).
 */
abstract class AWSRegionGraph private (name: String) {
  type Mat

  def runSimulationTo[Mat2, Mat3](sink: Sink[TimeWindowResourceConsumptionEvent, Mat2])(combiner: (Mat, Mat2) => Mat3): Future[Done]
}
