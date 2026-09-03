package stochastacy.aws.examples.hotkey

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome
import stochastacy.aws.dynamodb.{DynamoDbRequest, GetItemRequest, PutItemRequest, ReadConsistency, TableBehavior, TableSummaryState}

/**
 * The hot-key domain: puts overwrite an existing item in place (storage flat), gets read a present item
 * strongly. All the interest is in `partitionAccessFor`, which draws the request's key token from the
 * skewed access distribution — with probability `hotFraction` one of the `hotKeyCount` hot keys (which
 * concentrate onto few partitions), otherwise a distinct cold key spread across `coldKeySpace`.
 */
final class HotKeyBehavior(config: HotKeyConfig) extends TableBehavior:

  def outcomeFor(request: DynamoDbRequest, state: TableSummaryState, rng: UniformRandomProvider, tick: Long): OperationOutcome =
    request match
      case PutItemRequest(bytes) => OperationOutcome.Put(writtenItemBytes = bytes, previousItemBytes = Some(bytes)) // in-place overwrite
      case GetItemRequest        => OperationOutcome.Get(itemBytes = Some(config.itemBytes), consistency = ReadConsistency.StronglyConsistent)
      case other                 => throw new IllegalArgumentException(s"HotKeyBehavior does not handle $other")

  override def partitionAccessFor(request: DynamoDbRequest, rng: UniformRandomProvider): Option[String] =
    if !config.partitionAccessEnabled then None // the table-level-only path (no per-partition modeling)
    else if rng.nextDouble() < config.hotFraction then Some(s"hot-${rng.nextInt(config.hotKeyCount)}")
    else                                               Some(s"cold-${rng.nextInt(config.coldKeySpace)}")
