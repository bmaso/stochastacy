package stochastacy.aws.examples.payments

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.{OperationOutcome, TransactWriteItem}

/**
 * The payments-ledger domain behavior. It resolves both the transactional and the single-operation form of
 * the same work, so the two can be billed and compared:
 *
 *   - a **transfer** — `TransactWriteItems` of same-size balance overwrites (storage flat, WCU billed 2×),
 *     or the equivalent `UpdateItem`s (1×);
 *   - a **balance check** — `TransactGetItems` of the accounts (2× strong RCU each), or the equivalent
 *     `GetItem`s (1×).
 *
 * rng-free — every outcome is determined by the request and the summary state.
 */
final class PaymentsLedgerBehavior extends TableBehavior:

  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
    request match
      case TransactWriteItemsRequest(perItemBytes) =>
        // each sub-write overwrites an existing account balance with a same-size item (storage delta 0)
        OperationOutcome.TransactWrite(perItemBytes.map(b => TransactWriteItem(writtenItemBytes = b, previousItemBytes = Some(b))))

      case UpdateItemRequest(itemBytes) =>
        OperationOutcome.Update(writtenItemBytes = itemBytes, previousItemBytes = Some(itemBytes))

      case TransactGetItemsRequest(itemCount) =>
        OperationOutcome.TransactGet(Vector.fill(itemCount)(state.averageItemBytes))

      case GetItemRequest =>
        OperationOutcome.Get(itemBytes = state.averageItemBytes, consistency = ReadConsistency.StronglyConsistent)

      case other =>
        throw new IllegalArgumentException(s"the payments-ledger workload uses transact-write/update and transact-get/get, not $other")
