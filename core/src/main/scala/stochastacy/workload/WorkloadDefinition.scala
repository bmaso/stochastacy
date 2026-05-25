package stochastacy.workload

import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.aws.dynamodb.table.ReadConsistency

/** Which class of simulator response outcome drives a derived flow. */
enum OutcomeFilter:
  case Success
  case Throttled

/** A single named flow within a WorkloadDefinition. Either an independent arrival process
 *  or a derived flow whose rate is computed from another flow's simulator outcomes. */
sealed trait FlowDefinition:
  def id: String

object FlowDefinition:

  /** An independent arrival flow with its own rate sampler. */
  final case class Independent(id: String, defn: RequestShapeDefinition) extends FlowDefinition

  /** A derived flow whose per-tick request count = Binomial(sourceOutcomeCount, proportion).
   *  Driven by the `outcome` class of `sourceFlowId` within workload `sourceId`,
   *  emitted `lagTicks` ticks after the observed outcome tick. */
  final case class FollowOn(
    id:           String,
    sourceId:     String,
    sourceFlowId: String,
    outcome:      OutcomeFilter,
    proportion:   Double,
    lagTicks:     Int,
    shape:        RequestShape
  ) extends FlowDefinition:
    require(proportion >= 0.0 && proportion <= 1.0, s"FollowOn.proportion must be in [0,1], got $proportion")
    require(lagTicks >= 1, s"FollowOn.lagTicks must be >= 1, got $lagTicks")

  /** Shorthand for FollowOn(outcome=Throttled) where the derived request type mirrors the source.
   *  Models AWS SDK automatic retry-on-throttle. The request shape is resolved at bind time
   *  by copying the source flow's RequestShape. */
  final case class Retry(
    id:           String,
    sourceId:     String,
    sourceFlowId: String,
    proportion:   Double,
    lagTicks:     Int
  ) extends FlowDefinition:
    require(proportion >= 0.0 && proportion <= 1.0, s"Retry.proportion must be in [0,1], got $proportion")
    require(lagTicks >= 1, s"Retry.lagTicks must be >= 1, got $lagTicks")

sealed trait RequestShape

object RequestShape:
  case object GetItem  extends RequestShape
  case object DeleteItem extends RequestShape

  case class PutItem(itemBytes: StatelessSampler[Long]) extends RequestShape
  case class UpdateItem(itemBytes: StatelessSampler[Long]) extends RequestShape

  case class Query(
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ) extends RequestShape

  case class Scan(
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ) extends RequestShape

  case class TransactWriteItems(
    perItemBytes: Vector[StatelessSampler[Long]]
  ) extends RequestShape

  case class TransactGetItems(itemCount: StatelessSampler[Int]) extends RequestShape


case class RequestShapeDefinition(
  rate:  StatelessSampler[Int],
  shape: RequestShape
)

object RequestShapeDefinition:

  def getItem(rate: StatelessSampler[Int]): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.GetItem)

  def putItem(rate: StatelessSampler[Int], itemBytes: StatelessSampler[Long]): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.PutItem(itemBytes))

  def updateItem(rate: StatelessSampler[Int], itemBytes: StatelessSampler[Long]): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.UpdateItem(itemBytes))

  def deleteItem(rate: StatelessSampler[Int]): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.DeleteItem)

  def query(
    rate:            StatelessSampler[Int],
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.Query(target, readConsistency))

  def scan(
    rate:            StatelessSampler[Int],
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.Scan(target, readConsistency))

  def transactWriteItems(
    rate:         StatelessSampler[Int],
    perItemBytes: Vector[StatelessSampler[Long]]
  ): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.TransactWriteItems(perItemBytes))

  def transactGetItems(rate: StatelessSampler[Int], itemCount: StatelessSampler[Int]): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.TransactGetItems(itemCount))


case class WorkloadDefinition(
  tableName: String,
  usecase:   String,
  flows:     Vector[FlowDefinition]
):
  /** The independent flows only — what WorkloadRequestStream generates. */
  def independentFlows: Vector[FlowDefinition.Independent] =
    flows.collect { case f: FlowDefinition.Independent => f }

  /** The derived flows (follow-on and retry) — what FollowOnTransformerStage handles. */
  def derivedFlows: Vector[FlowDefinition] =
    flows.filter {
      case _: FlowDefinition.Independent => false
      case _ => true
    }

object WorkloadDefinition:
  /** Convenience constructor for workloads consisting entirely of independent flows.
   *  Assigns synthetic ids ("flow-0", "flow-1", …) to each flow. */
  def ofIndependent(
    tableName: String,
    usecase:   String,
    requests:  Vector[RequestShapeDefinition]
  ): WorkloadDefinition =
    WorkloadDefinition(
      tableName,
      usecase,
      requests.zipWithIndex.map { (defn, i) => FlowDefinition.Independent(s"flow-$i", defn) }
    )
