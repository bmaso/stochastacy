package stochastacy.aws.ddb

/**
 * This trait describes the stochastic model for a GetItem use-case against a table whose internal
 * state is represented by an instance of `TableState`.
 *
 * The `TableState` value is maintained by the system, and is essentially an accumulator modified by
 * "update" requests to the table. For a GetItem request's stochastic behavior the state might only
 * include an estimated count of unique primary keys in the table, affecting `getItemSuccess` response
 * such that a "more full" table would have a higher hit ratio than a less full one. When each request
 * is independent of the others then `NullState` should be used as a placeholder.
 */
trait GetItemUseCaseStochasticModel[TableState]:

  /** For each GetItem request, is there a "hit" on the primary/secondary key? */
  def getItemSuccess(s: TableState): Boolean

  /** For successful GetItem requests, this is the stochastic source of record sizes in bytes. */
  def getItemRecordSizeBytes(s: TableState): Int
