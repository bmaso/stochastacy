package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.TimedElement

final class DynamoDbGlobalTableManagedShape(
                                             val regionRequestInlets: Map[String, Inlet[TimedElement[DynamoDBRequest]]],
                                             val managementIn: Inlet[TimedElement[DynamoDbManagementEvent]],
                                             val regionResponseOutlets: Map[String, Outlet[TimedElement[DynamoDBResponse]]],
                                             val regionConsumptionOutlets: Map[String, Outlet[TimedElement[DynamoDbConsumptionEvent]]],
                                             val regionMetricOutlets: Map[String, Outlet[TimedElement[TableMetricEvent]]],
                                             val transferEventsOutlet: Outlet[TimedElement[CrossRegionTransferEvent]]
                                           ) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    regionRequestInlets.values.toVector :+ managementIn

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    regionResponseOutlets.values.toVector ++
      regionConsumptionOutlets.values.toVector ++
      regionMetricOutlets.values.toVector :+
      transferEventsOutlet

  override def deepCopy(): DynamoDbGlobalTableManagedShape =
    new DynamoDbGlobalTableManagedShape(
      regionRequestInlets = regionRequestInlets.view.mapValues(_.carbonCopy()).toMap,
      managementIn = managementIn.carbonCopy(),
      regionResponseOutlets = regionResponseOutlets.view.mapValues(_.carbonCopy()).toMap,
      regionConsumptionOutlets = regionConsumptionOutlets.view.mapValues(_.carbonCopy()).toMap,
      regionMetricOutlets = regionMetricOutlets.view.mapValues(_.carbonCopy()).toMap,
      transferEventsOutlet = transferEventsOutlet.carbonCopy()
    )
