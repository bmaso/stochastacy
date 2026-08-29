package stochastacy.aws.examples.sessionstore

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.*
import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

/**
 * The session-store domain behavior. A write is **always an insert** — each sign-in mints a new session id
 * (`previousItemBytes = None`), so items accumulate and TTL is what bounds them. A validation read is a
 * strongly-consistent `GetItem` that finds a session of the table's average size (or nothing, when the
 * table is still empty). rng-free — the outcome is fully determined by the request and the summary state.
 */
final class SessionStoreBehavior extends TableBehavior:

  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
    request match
      case PutItemRequest(itemBytes) =>
        OperationOutcome.Put(writtenItemBytes = itemBytes, previousItemBytes = None)
      case GetItemRequest =>
        OperationOutcome.Get(itemBytes = state.averageItemBytes, consistency = ReadConsistency.StronglyConsistent)
      case other =>
        throw new IllegalArgumentException(s"the session-store workload uses put/get, not $other")
