package stochastacy.workload

import org.apache.commons.rng.UniformRandomProvider
import stochastacy.aws.dynamodb.{
  DynamoDBRequest, DynamoDbReadTarget, DeleteItemRequest, GetItemRequest, PutItemRequest,
  QueryRequest, ScanRequest, TransactGetItemsRequest, TransactWriteItemsRequest,
  UpdateItemRequest
}
import stochastacy.aws.dynamodb.table.ReadConsistency
import stochastacy.sim.SimTime

/** Which class of simulator response outcome drives a derived flow. */
enum OutcomeFilter:
  case Success
  case Throttled

/** A single named flow within a WorkloadDefinition. Either an independent arrival process
 *  or a derived flow whose rate is computed from another flow's simulator outcomes. */
sealed trait WorkloadFlow:
  def id: String

object WorkloadFlow:

  /** An independent arrival flow with its own rate sampler. */
  final case class Independent(id: String, factory: PacedRequestFactory) extends WorkloadFlow

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
    factory:      RequestShape
  ) extends WorkloadFlow:
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
  ) extends WorkloadFlow:
    require(proportion >= 0.0 && proportion <= 1.0, s"Retry.proportion must be in [0,1], got $proportion")
    require(lagTicks >= 1, s"Retry.lagTicks must be >= 1, got $lagTicks")

/** The DynamoDB request factories. Each variant knows how to mint its own request type,
 *  carrying whatever parameter samplers that request needs.
 *
 *  Still `sealed` — no in-repo code needs to extend it, and sealing keeps the DSL's
 *  `TemplateShape` → bound-form mapping exhaustively checked. Downstream projects extend
 *  `RequestFactory` directly with their own request type rather than adding cases here. */
sealed trait RequestShape extends RequestFactory[DynamoDBRequest]

object RequestShape:
  case object GetItem extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      GetItemRequest(SimTime.of(tick), usecase, intraTick, Some(flowId))

  case object DeleteItem extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      DeleteItemRequest(SimTime.of(tick), usecase, intraTick, Some(flowId))

  case class PutItem(itemBytes: StatelessSampler[Long]) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      PutItemRequest(SimTime.of(tick), usecase, itemBytes.sample(tick, rng, ())._1, intraTick, Some(flowId))

  case class UpdateItem(itemBytes: StatelessSampler[Long]) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      UpdateItemRequest(SimTime.of(tick), usecase, itemBytes.sample(tick, rng, ())._1, intraTick, Some(flowId))

  case class Query(
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      QueryRequest(SimTime.of(tick), usecase, target, readConsistency,
                   intraTick = intraTick, flowId = Some(flowId))

  case class Scan(
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      ScanRequest(SimTime.of(tick), usecase, target, readConsistency,
                  intraTick = intraTick, flowId = Some(flowId))

  case class TransactWriteItems(
    perItemBytes: Vector[StatelessSampler[Long]]
  ) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      TransactWriteItemsRequest(SimTime.of(tick), usecase,
                                perItemBytes.map(_.sample(tick, rng, ())._1), intraTick, Some(flowId))

  case class TransactGetItems(itemCount: StatelessSampler[Int]) extends RequestShape:
    def build(tick: Long, usecase: String, flowId: String,
              rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
      TransactGetItemsRequest(SimTime.of(tick), usecase, itemCount.sample(tick, rng, ())._1, intraTick, Some(flowId))


/** A request factory that also knows its own arrival rate.
 *
 *  Extends `RequestFactory` and delegates `build` to the wrapped factory, so call sites
 *  that only need to mint a request never reach through to `.factory`. Only code that
 *  genuinely needs to *unwrap* — `Retry` resolution copying a source flow's factory —
 *  touches the field directly.
 *
 *  The paced/bare distinction mirrors one the flow ADT already makes: `Independent` flows
 *  carry their own rate; `FollowOn` and `Retry` derive theirs from source outcomes. */
final case class PacedRequestFactory(
  rate:    StatelessSampler[Int],
  factory: RequestShape
) extends RequestFactory[DynamoDBRequest]:
  def build(tick: Long, usecase: String, flowId: String,
            rng: UniformRandomProvider, intraTick: Double): DynamoDBRequest =
    factory.build(tick, usecase, flowId, rng, intraTick)

object PacedRequestFactory:

  def getItem(rate: StatelessSampler[Int]): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.GetItem)

  def putItem(rate: StatelessSampler[Int], itemBytes: StatelessSampler[Long]): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.PutItem(itemBytes))

  def updateItem(rate: StatelessSampler[Int], itemBytes: StatelessSampler[Long]): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.UpdateItem(itemBytes))

  def deleteItem(rate: StatelessSampler[Int]): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.DeleteItem)

  def query(
    rate:            StatelessSampler[Int],
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.Query(target, readConsistency))

  def scan(
    rate:            StatelessSampler[Int],
    target:          DynamoDbReadTarget,
    readConsistency: ReadConsistency = ReadConsistency.EventuallyConsistent
  ): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.Scan(target, readConsistency))

  def transactWriteItems(
    rate:         StatelessSampler[Int],
    perItemBytes: Vector[StatelessSampler[Long]]
  ): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.TransactWriteItems(perItemBytes))

  def transactGetItems(rate: StatelessSampler[Int], itemCount: StatelessSampler[Int]): PacedRequestFactory =
    PacedRequestFactory(rate, RequestShape.TransactGetItems(itemCount))


case class WorkloadDefinition(
  tableName: String,
  usecase:   String,
  flows:     Vector[WorkloadFlow]
):
  /** The independent flows only — what WorkloadRequestStream generates. */
  def independentFlows: Vector[WorkloadFlow.Independent] =
    flows.collect { case f: WorkloadFlow.Independent => f }

  /** The derived flows (follow-on and retry) — what FollowOnTransformerStage handles. */
  def derivedFlows: Vector[WorkloadFlow] =
    flows.filter {
      case _: WorkloadFlow.Independent => false
      case _ => true
    }

object WorkloadDefinition:
  /** Convenience constructor for workloads consisting entirely of independent flows.
   *  Assigns synthetic ids ("flow-0", "flow-1", …) to each flow. */
  def ofIndependent(
    tableName: String,
    usecase:   String,
    requests:  Vector[PacedRequestFactory]
  ): WorkloadDefinition =
    WorkloadDefinition(
      tableName,
      usecase,
      requests.zipWithIndex.map { (paced, i) => WorkloadFlow.Independent(s"flow-$i", paced) }
    )
