package stochastacy.aws.dynamodb

/**
 * The deterministic mechanics of a single-item operation: given the **stochastic outcome** of an
 * operation (what the domain behavior decided — a read hit/miss, the bytes written, whether an item
 * existed) and the current table state, compute the response, the consumption facts, and the next state.
 *
 * This is deliberately **rng-free**: all randomness lives upstream in the behavior that produces an
 * [[OperationOutcome]] (Slices 2–3). Isolating the mechanics here keeps the capacity/storage/state
 * arithmetic pure and exhaustively testable without a graph, a wire, or a seed.
 */
object TableMechanics:

  /** A read's footprint: how many items/bytes were **evaluated** (what the read is charged for) vs.
   *  **returned** (what the caller received). */
  final case class ReadShape(
    evaluatedItemCount: Long,
    evaluatedBytes:     Long,
    returnedItemCount:  Long,
    returnedBytes:      Long
  )

  /** What a domain behavior decided an operation did — the rng-free input to [[resolve]]. Every outcome
   *  carries whatever `resolve` needs, so `resolve` takes only the outcome and the state; reads carry
   *  their own consistency and (for query/scan) their target. */
  enum OperationOutcome:
    /** A get that returned an item of the given size, or missed (`None`), at `consistency`. */
    case Get(itemBytes: Option[Long], consistency: ReadConsistency)
    /** A put storing `writtenItemBytes`; `previousItemBytes` set when it overwrote an existing item. */
    case Put(writtenItemBytes: Long, previousItemBytes: Option[Long])
    /** An update storing `writtenItemBytes`; `previousItemBytes` empty on an upsert that hit nothing. */
    case Update(writtenItemBytes: Long, previousItemBytes: Option[Long])
    /** A delete of an item of `deletedItemBytes`, or of an absent item (`None`). */
    case Delete(deletedItemBytes: Option[Long])
    /** A query against `target` at `consistency` with the given read shape. */
    case Query(target: DynamoDbTarget, consistency: ReadConsistency, shape: ReadShape)
    /** A scan against `target` at `consistency` with the given read shape. */
    case Scan(target: DynamoDbTarget, consistency: ReadConsistency, shape: ReadShape)

  /** The result of resolving one operation against the table state. */
  final case class Resolution(
    response:    DynamoDbResponse,
    consumption: List[DynamoDbConsumption],
    state:       TableSummaryState
  )

  /** Resolve one operation into its response, consumption facts, and next state. */
  def resolve(outcome: OperationOutcome, state: TableSummaryState): Resolution =
    outcome match
      case OperationOutcome.Get(itemBytes, consistency) =>
        Resolution(
          response    = GetItemResponse(itemFound = itemBytes.isDefined, itemBytes = itemBytes),
          consumption = List(ReadCapacityConsumed(ThroughputMath.readCapacityUnits(itemBytes, consistency), consistency, DynamoDbTarget.Table)),
          state       = state // reads do not change storage
        )

      case OperationOutcome.Put(writtenItemBytes, previousItemBytes) =>
        val next = state.applyWrite(writtenItemBytes, previousItemBytes)
        Resolution(
          response    = PutItemResponse(writtenItemBytes, createdNewItem = previousItemBytes.isEmpty, previousItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(writtenItemBytes), DynamoDbTarget.Table) ::
                          storageDelta(state, next),
          state       = next
        )

      case OperationOutcome.Update(writtenItemBytes, previousItemBytes) =>
        val next = state.applyWrite(writtenItemBytes, previousItemBytes)
        Resolution(
          response    = UpdateItemResponse(writtenItemBytes, createdNewItem = previousItemBytes.isEmpty, previousItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(writtenItemBytes), DynamoDbTarget.Table) ::
                          storageDelta(state, next),
          state       = next
        )

      case OperationOutcome.Delete(deletedItemBytes) =>
        val next = state.applyDelete(deletedItemBytes)
        Resolution(
          response    = DeleteItemResponse(deletedItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(deletedItemBytes.getOrElse(0L)), DynamoDbTarget.Table) ::
                          storageDelta(state, next),
          state       = next
        )

      case OperationOutcome.Query(target, consistency, shape) =>
        Resolution(
          response    = QueryResponse(shape.evaluatedItemCount, shape.evaluatedBytes, shape.returnedItemCount, shape.returnedBytes),
          consumption = List(ReadCapacityConsumed(ThroughputMath.readCapacityUnits(Some(shape.evaluatedBytes), consistency), consistency, target)),
          state       = state // reads do not change storage
        )

      case OperationOutcome.Scan(target, consistency, shape) =>
        Resolution(
          response    = ScanResponse(shape.evaluatedItemCount, shape.evaluatedBytes, shape.returnedItemCount, shape.returnedBytes),
          consumption = List(ReadCapacityConsumed(ThroughputMath.readCapacityUnits(Some(shape.evaluatedBytes), consistency), consistency, target)),
          state       = state
        )

  /** Emit a `StorageBytesDelta` (on the base table) only when the write/delete actually moved the byte total. */
  private def storageDelta(before: TableSummaryState, after: TableSummaryState): List[DynamoDbConsumption] =
    val delta = after.totalItemBytes - before.totalItemBytes
    if delta != 0L then List(StorageBytesDelta(delta, DynamoDbTarget.Table)) else Nil
