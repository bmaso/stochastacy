package stochastacy.graphs

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.{FlowShape, Graph}
import stochastacy.aws.ddb.{DynamoDBRequest, DynamoDBResponse, GetItemRequest, GetItemResponse, TableState, UseCaseSampler}

/**
 * A table is implemented as a multi-stage Pekko component graph. Stage 4 of this model
 * is the "data-plane". This stage represents the physical storage of a DDB table. This is
 * the stage that consumes RCUs and WCUs, and maintains the table state with respect to
 * the count and size of table items within the table, etc.
 */
object TableStage4:

  def stage4Table(
                   stateModel: TableState,
                   getItemBehaviors: Map[Any, UseCaseSampler[TableState]]
                 ): Graph[FlowShape[DynamoDBRequest, DynamoDBResponse], NotUsed] =

    Flow[DynamoDBRequest].map {

      case r: GetItemRequest =>
        val behavior =
          getItemBehaviors.getOrElse(
            r.usecase,
            throw new IllegalArgumentException(
              s"No GetItem behavior registered for use-case '${r.usecase}'"
            )
          )

        val sample = behavior.getItem(r, stateModel)

        sample match
          case None =>
            GetItemResponse(
              eventTime = r.eventTime,
              usecase   = r.usecase
            )

          case Some(sample) =>
            GetItemResponse(
              eventTime = r.eventTime,
              usecase   = r.usecase
            )

      case other =>
        throw new IllegalArgumentException(
          s"Stage 4 received unsupported request type: ${other.getClass.getSimpleName}"
        )
    }


