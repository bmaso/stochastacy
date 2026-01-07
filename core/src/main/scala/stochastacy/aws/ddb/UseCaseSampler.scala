package stochastacy.aws.ddb

/**
 * This trait describes the stochastic table behavior for GetItem requests against a table whose
 * internal state is represented by an instance of `T <: TableState`.
 *
 * The `T` value is maintained by the system, and is essentially an accumulator modified by
 * "update" requests to the table.
 */
trait UseCaseSampler[T <: TableState]:
  def getItem(request: GetItemRequest, s: T): Option[GetItemSample]
