package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.sim.TimedElement

final class DynamoDbTableManagedReplicatedShape(
                                                 val requestIn: Inlet[TimedElement[DynamoDBRequest]],
                                                 val managementIn: Inlet[TimedElement[DynamoDbManagementEvent]],
                                                 val replicatedIn: Inlet[TimedElement[AdmittedRequestSample]],
                                                 val responseOut: Outlet[TimedElement[DynamoDBResponse]],
                                                 val consumptionOut: Outlet[TimedElement[DynamoDbConsumptionEvent]],
                                                 val metricOut: Outlet[TimedElement[TableMetricEvent]],
                                                 val outboundReplicationOut: Outlet[TimedElement[AdmittedRequestSample]]
                                               ) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    Vector(requestIn, managementIn, replicatedIn)

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    Vector(responseOut, consumptionOut, metricOut, outboundReplicationOut)

  override def deepCopy(): DynamoDbTableManagedReplicatedShape =
    new DynamoDbTableManagedReplicatedShape(
      requestIn.carbonCopy(),
      managementIn.carbonCopy(),
      replicatedIn.carbonCopy(),
      responseOut.carbonCopy(),
      consumptionOut.carbonCopy(),
      metricOut.carbonCopy(),
      outboundReplicationOut.carbonCopy()
    )
