package stochastacy.aws.dynamodb.table

import org.apache.pekko.stream.{Inlet, Outlet, Shape}
import stochastacy.aws.dynamodb.{DynamoDBRequest, DynamoDBResponse}
import stochastacy.sim.TimedElement

/**
 * Shape exposed by `DynamoDbTable.componentOfManaged`. Adds a management event inlet alongside
 * the standard request inlet, allowing mid-simulation billing mode switches.
 *
 * - `requestIn`: client data-plane requests, same as `componentOf`'s input
 * - `managementIn`: management events (billing mode switches, capacity adjustments)
 * - `responseOut`, `consumptionOut`, `metricOut`: same as `componentOf`'s outputs
 */
final class DynamoDbTableManagedShape(
  val requestIn: Inlet[TimedElement[DynamoDBRequest]],
  val managementIn: Inlet[TimedElement[DynamoDbManagementEvent]],
  val responseOut: Outlet[TimedElement[DynamoDBResponse]],
  val consumptionOut: Outlet[TimedElement[DynamoDbConsumptionEvent]],
  val metricOut: Outlet[TimedElement[TableMetricEvent]]
) extends Shape:

  override val inlets: scala.collection.immutable.Seq[Inlet[?]] =
    Vector(requestIn, managementIn)

  override val outlets: scala.collection.immutable.Seq[Outlet[?]] =
    Vector(responseOut, consumptionOut, metricOut)

  override def deepCopy(): DynamoDbTableManagedShape =
    new DynamoDbTableManagedShape(
      requestIn.carbonCopy(),
      managementIn.carbonCopy(),
      responseOut.carbonCopy(),
      consumptionOut.carbonCopy(),
      metricOut.carbonCopy()
    )
