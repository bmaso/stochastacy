package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.aws.transfer.CrossRegionTransferEvent
import stochastacy.sim.TimedElement

/**
 * Shape exposed by `DynamoDbGlobalTable.componentOf`. Per region: one request inlet plus
 * three output streams (response/consumption/metric). Plus one global outlet for cross-region
 * transfer events emitted by the replication coordinator.
 *
 * For N regions: N inlets, 3N+1 outlets total.
 */
final class DynamoDbGlobalTableShape(
                                      val regionRequestInlets: Map[String, Inlet[TimedElement[DynamoDBRequest]]],
                                      val regionResponseOutlets: Map[String, Outlet[TimedElement[DynamoDBResponse]]],
                                      val regionConsumptionOutlets: Map[String, Outlet[TimedElement[DynamoDbConsumptionEvent]]],
                                      val regionMetricOutlets: Map[String, Outlet[TimedElement[TableMetricEvent]]],
                                      val transferEventsOutlet: Outlet[TimedElement[CrossRegionTransferEvent]]
                                    ) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    regionRequestInlets.values.toVector

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    regionResponseOutlets.values.toVector ++
      regionConsumptionOutlets.values.toVector ++
      regionMetricOutlets.values.toVector :+
      transferEventsOutlet

  override def deepCopy(): DynamoDbGlobalTableShape =
    new DynamoDbGlobalTableShape(
      regionRequestInlets = regionRequestInlets.view.mapValues(_.carbonCopy()).toMap,
      regionResponseOutlets = regionResponseOutlets.view.mapValues(_.carbonCopy()).toMap,
      regionConsumptionOutlets = regionConsumptionOutlets.view.mapValues(_.carbonCopy()).toMap,
      regionMetricOutlets = regionMetricOutlets.view.mapValues(_.carbonCopy()).toMap,
      transferEventsOutlet = transferEventsOutlet.carbonCopy()
    )
