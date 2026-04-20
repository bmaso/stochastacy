package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.{DeleteItemRequest, GetItemRequest, PutItemRequest, QueryRequest, UpdateItemRequest}

/**
 * This trait describes the stochastic table behavior for requests against a table whose
 * internal state is represented by an instance of `T <: TableState`.
 *
 * The `T` value is maintained by the system, and is essentially an accumulator modified by
 * "update" requests to the table.
 */
trait UseCaseSampler[T <: TableState]:
  /** @returns a sample representing a `GetItem` hit, or `None` for a miss. */
  def getItem(request: GetItemRequest, s: T): Option[GetItemSample] =
    throw new UnsupportedOperationException(s"GetItem is not supported for use-case '${request.usecase}'")

  def query(request: QueryRequest, s: T): QuerySample =
    throw new UnsupportedOperationException(s"Query is not supported for use-case '${request.usecase}'")

  def putItem(request: PutItemRequest, s: T): PutItemSample =
    throw new UnsupportedOperationException(s"PutItem is not supported for use-case '${request.usecase}'")

  def updateItem(request: UpdateItemRequest, s: T): UpdateItemSample =
    throw new UnsupportedOperationException(s"UpdateItem is not supported for use-case '${request.usecase}'")

  def deleteItem(request: DeleteItemRequest, s: T): DeleteItemSample =
    throw new UnsupportedOperationException(s"DeleteItem is not supported for use-case '${request.usecase}'")
