package stochastacy.aws.dynamodb

import org.apache.commons.rng.UniformRandomProvider

import stochastacy.aws.dynamodb.TableMechanics.OperationOutcome

/**
 * The domain's stochastic decision for one operation: given the request, the current table summary (of the
 * target the request hits), and the current `tick`, draw what the operation did — a read hit/miss and
 * size, the bytes written, whether an item existed.
 *
 * All operation-level randomness lives here; the [[TableMechanics]] that follow are pure. This is the v2
 * counterpart to the legacy `UseCaseSampler`: a table is generic, and a demo injects its domain by
 * supplying a `TableBehavior`. `tick` lets a behavior be time-dependent (e.g. a fleet that grows over
 * time); tick-independent behaviors ignore it.
 */
trait TableBehavior:
  def outcomeFor(
    request: DynamoDbRequest,
    state:   TableSummaryState,
    rng:     UniformRandomProvider,
    tick:    Long
  ): OperationOutcome

  /**
   * The base-table **partition access** for a request: a key token, hashed to a physical partition for
   * hot-partition throttling. `None` (the default) means the domain does not model key access, so no
   * per-partition throttling applies — every existing behavior stays byte-identical. A hot-key workload
   * overrides this to draw a key from a (possibly skewed) access distribution. The default must not consume
   * `rng` (so it does not perturb existing scenarios).
   */
  def partitionAccessFor(request: DynamoDbRequest, rng: UniformRandomProvider): Option[String] = None
