package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

/**
 * The domain's stochastic decision for one operation: given the request and the current table summary,
 * draw what the operation did — a read hit/miss and size, the bytes written, whether an item existed.
 *
 * All operation-level randomness lives here; the [[TableMechanics]] that follow are pure. This is the v2
 * counterpart to the legacy `UseCaseSampler`: a table is generic, and a demo injects its domain by
 * supplying a `TableBehavior`.
 */
trait TableBehavior:
  def outcomeFor(
    request: DynamoDbRequest,
    state:   TableSummaryState,
    rng:     UniformRandomProvider
  ): OperationOutcome
