package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.{GetItemRequest, PutItemRequest}

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

  def putItem(request: PutItemRequest, s: T): PutItemSample =
    throw new UnsupportedOperationException(s"PutItem is not supported for use-case '${request.usecase}'")
