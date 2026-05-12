package stochastacy.workload

import stochastacy.aws.dynamodb.DynamoDbReadTarget
import stochastacy.aws.dynamodb.table.ReadConsistency

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

  case class TransactGetItems(itemCount: Int) extends RequestShape


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

  def transactGetItems(rate: StatelessSampler[Int], itemCount: Int): RequestShapeDefinition =
    RequestShapeDefinition(rate, RequestShape.TransactGetItems(itemCount))


case class WorkloadDefinition(
  tableName: String,
  usecase:   String,
  requests:  Vector[RequestShapeDefinition]
)
