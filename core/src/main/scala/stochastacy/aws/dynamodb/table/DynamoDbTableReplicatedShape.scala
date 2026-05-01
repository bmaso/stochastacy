package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.sim.TimedElement

/**
 * Shape exposed by `DynamoDbTable.componentOfReplicated`. Adds an inbound port for replicated
 * writes (which bypass the destination region's admission) and an outbound port that emits
 * validated admitted samples for the replication coordinator to fan out to peer regions.
 *
 * - `requestIn`: client requests, same as `componentOf`'s input
 * - `replicatedIn`: replicated writes from the coordinator (already validated at origin)
 * - `responseOut`, `consumptionOut`, `metricOut`: same as `componentOf`'s outputs
 * - `outboundReplicationOut`: validated admitted samples (writes that succeeded locally) that
 *   the coordinator subscribes to in order to fan out to peer regions
 */
final class DynamoDbTableReplicatedShape(
                                          val requestIn: Inlet[TimedElement[DynamoDBRequest]],
                                          val replicatedIn: Inlet[TimedElement[AdmittedRequestSample]],
                                          val responseOut: Outlet[TimedElement[DynamoDBResponse]],
                                          val consumptionOut: Outlet[TimedElement[DynamoDbConsumptionEvent]],
                                          val metricOut: Outlet[TimedElement[TableMetricEvent]],
                                          val outboundReplicationOut: Outlet[TimedElement[AdmittedRequestSample]]
                                        ) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    Vector(requestIn, replicatedIn)

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    Vector(responseOut, consumptionOut, metricOut, outboundReplicationOut)

  override def deepCopy(): DynamoDbTableReplicatedShape =
    new DynamoDbTableReplicatedShape(
      requestIn.carbonCopy(),
      replicatedIn.carbonCopy(),
      responseOut.carbonCopy(),
      consumptionOut.carbonCopy(),
      metricOut.carbonCopy(),
      outboundReplicationOut.carbonCopy()
    )
