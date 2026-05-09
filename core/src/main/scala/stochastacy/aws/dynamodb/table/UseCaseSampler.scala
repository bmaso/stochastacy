package stochastacy.aws.dynamodb.table

import stochastacy.aws.dynamodb.{DeleteItemRequest, GetItemRequest, PutItemRequest, QueryRequest, ScanRequest, TransactGetItemsRequest, TransactWriteItemsRequest, UpdateItemRequest}

/** Bundles table state with the current simulation tick for time-aware samplers. */
final case class SamplerContext[T <: TableState](state: T, currentTick: Long)

/**
 * This trait describes the stochastic table behavior for requests against a table whose
 * internal state is represented by an instance of `T <: TableState`.
 *
 * The `T` value is maintained by the system, and is essentially an accumulator modified by
 * "update" requests to the table.
 */
trait UseCaseSampler[T <: TableState]:
  /** @returns a sample representing a `GetItem` hit or miss. */
  def getItem(request: GetItemRequest, ctx: SamplerContext[T]): GetItemSample =
    throw new UnsupportedOperationException(s"GetItem is not supported for use-case '${request.usecase}'")

  def query(request: QueryRequest, ctx: SamplerContext[T]): QuerySample =
    throw new UnsupportedOperationException(s"Query is not supported for use-case '${request.usecase}'")

  def scan(request: ScanRequest, ctx: SamplerContext[T]): ScanSample =
    throw new UnsupportedOperationException(s"Scan is not supported for use-case '${request.usecase}'")

  def putItem(request: PutItemRequest, ctx: SamplerContext[T]): PutItemSample =
    throw new UnsupportedOperationException(s"PutItem is not supported for use-case '${request.usecase}'")

  def updateItem(request: UpdateItemRequest, ctx: SamplerContext[T]): UpdateItemSample =
    throw new UnsupportedOperationException(s"UpdateItem is not supported for use-case '${request.usecase}'")

  def deleteItem(request: DeleteItemRequest, ctx: SamplerContext[T]): DeleteItemSample =
    throw new UnsupportedOperationException(s"DeleteItem is not supported for use-case '${request.usecase}'")

  def transactWriteItems(request: TransactWriteItemsRequest, ctx: SamplerContext[T]): TransactWriteItemsSample =
    throw new UnsupportedOperationException(s"TransactWriteItems is not supported for use-case '${request.usecase}'")

  def transactGetItems(request: TransactGetItemsRequest, ctx: SamplerContext[T]): TransactGetItemsSample =
    throw new UnsupportedOperationException(s"TransactGetItems is not supported for use-case '${request.usecase}'")
