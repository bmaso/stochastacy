package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Source}
import org.apache.pekko.stream.{FanOutShape3, FlowShape, Graph}
import stochastacy.aws.{MetricEvent, ResourceConsumptionEvent}
import stochastacy.aws.ddb.{DynamoDBRequest, DynamoDBResponse, GetItemRequest, GetItemResponse, TableState, UseCaseSampler}

/**
 * A table is implemented as a multi-stage Pekko component graph. Stage 4 of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStage4:

  def componentOf(stateModel: TableState,
                  getItemBehaviors: Map[Any, UseCaseSampler[TableState]]):
      Graph[FanOutShape3[DynamoDBRequest, DynamoDBResponse, ResourceConsumptionEvent, MetricEvent], NotUsed] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val requestProcessingFlow =
        b.add(
          Flow[DynamoDBRequest].map {
            case r: GetItemRequest =>
              val behavior =
                getItemBehaviors.getOrElse(
                  r.usecase,
                  throw new IllegalArgumentException(
                    s"No GetItem behavior registered for use-case '${r.usecase}'"
                  )
                )

              behavior.getItem(r, stateModel)

              GetItemResponse(
                eventTime = r.eventTime,
                usecase = r.usecase
              )

            case other =>
              throw new IllegalArgumentException(
                s"Stage 4 received unsupported request type: ${other.getClass.getSimpleName}"
              )
          }
        )

      // Independent empty streams for resource consumption and metrics for now
      val emptyConsumption = b.add(Source.empty[ResourceConsumptionEvent])
      val emptyMetrics = b.add(Source.empty[MetricEvent])

      new FanOutShape3(
        requestProcessingFlow.in,
        requestProcessingFlow.out,
        emptyConsumption.out,
        emptyMetrics.out)
    }