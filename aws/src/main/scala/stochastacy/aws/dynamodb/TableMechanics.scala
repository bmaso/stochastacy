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

  /** What a domain behavior decided an operation did — the rng-free input to [[resolve]]. */
  enum OperationOutcome:
    /** A read that returned an item of the given size, or missed (`None`). */
    case Get(itemBytes: Option[Long])
    /** A put storing `writtenItemBytes`; `previousItemBytes` set when it overwrote an existing item. */
    case Put(writtenItemBytes: Long, previousItemBytes: Option[Long])
    /** An update storing `writtenItemBytes`; `previousItemBytes` empty on an upsert that hit nothing. */
    case Update(writtenItemBytes: Long, previousItemBytes: Option[Long])
    /** A delete of an item of `deletedItemBytes`, or of an absent item (`None`). */
    case Delete(deletedItemBytes: Option[Long])

  /** The result of resolving one operation against the table state. */
  final case class Resolution(
    response:    DynamoDbResponse,
    consumption: List[DynamoDbConsumption],
    state:       TableSummaryState
  )

  /**
   * Resolve one operation. `consistency` is the table-level read consistency, applied to reads (writes
   * ignore it).
   */
  def resolve(
    outcome:     OperationOutcome,
    consistency: ReadConsistency,
    state:       TableSummaryState
  ): Resolution =
    outcome match
      case OperationOutcome.Get(itemBytes) =>
        Resolution(
          response    = GetItemResponse(itemFound = itemBytes.isDefined, itemBytes = itemBytes),
          consumption = List(ReadCapacityConsumed(ThroughputMath.readCapacityUnits(itemBytes, consistency), consistency)),
          state       = state // reads do not change storage
        )

      case OperationOutcome.Put(writtenItemBytes, previousItemBytes) =>
        val next = state.applyWrite(writtenItemBytes, previousItemBytes)
        Resolution(
          response    = PutItemResponse(writtenItemBytes, createdNewItem = previousItemBytes.isEmpty, previousItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(writtenItemBytes)) ::
                          storageDelta(state, next),
          state       = next
        )

      case OperationOutcome.Update(writtenItemBytes, previousItemBytes) =>
        val next = state.applyWrite(writtenItemBytes, previousItemBytes)
        Resolution(
          response    = UpdateItemResponse(writtenItemBytes, createdNewItem = previousItemBytes.isEmpty, previousItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(writtenItemBytes)) ::
                          storageDelta(state, next),
          state       = next
        )

      case OperationOutcome.Delete(deletedItemBytes) =>
        val next = state.applyDelete(deletedItemBytes)
        Resolution(
          response    = DeleteItemResponse(deletedItemBytes),
          consumption = WriteCapacityConsumed(ThroughputMath.writeCapacityUnits(deletedItemBytes.getOrElse(0L))) ::
                          storageDelta(state, next),
          state       = next
        )

  /** Emit a `StorageBytesDelta` only when the write/delete actually moved the byte total. */
  private def storageDelta(before: TableSummaryState, after: TableSummaryState): List[DynamoDbConsumption] =
    val delta = after.totalItemBytes - before.totalItemBytes
    if delta != 0L then List(StorageBytesDelta(delta)) else Nil
