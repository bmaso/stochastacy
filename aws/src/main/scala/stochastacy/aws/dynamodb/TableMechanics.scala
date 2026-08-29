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

  /** One sub-write of a transactional write — a put/upsert of `writtenItemBytes`, with `previousItemBytes`
   *  set when it replaced an existing item (its footprint, exactly like a single `Put`). */
  final case class TransactWriteItem(writtenItemBytes: Long, previousItemBytes: Option[Long])

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
    /** A transactional write of several items, applied all-or-nothing (base WCU billed 2× per item). */
    case TransactWrite(items: Vector[TransactWriteItem])
    /** A transactional read of several items (each's found size, `None` if absent) — 2× strong RCU each. */
    case TransactGet(items: Vector[Option[Long]])

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

      case OperationOutcome.TransactWrite(items) =>
        // Thread the base summary through every sub-write; bill each base write at 2× (two-phase commit)
        // and emit its storage delta. Index maintenance (LSI 2×, GSI 1×) is applied by the sampler.
        val (nextState, facts) = items.foldLeft((state, List.empty[DynamoDbConsumption])) {
          case ((st, acc), TransactWriteItem(writtenItemBytes, previousItemBytes)) =>
            val next = st.applyWrite(writtenItemBytes, previousItemBytes)
            val wcu  = WriteCapacityConsumed(
              ThroughputMath.writeCapacityUnits(writtenItemBytes) * ThroughputMath.transactionalWriteMultiplier(DynamoDbTarget.Table),
              DynamoDbTarget.Table
            )
            (next, acc ++ (wcu :: storageDelta(st, next)))
        }
        Resolution(TransactWriteItemsResponse(items.size), facts, nextState)

      case OperationOutcome.TransactGet(items) =>
        Resolution(
          response    = TransactGetItemsResponse(items),
          consumption = items.map(b => ReadCapacityConsumed(ThroughputMath.transactionalReadCapacityUnits(b), ReadConsistency.StronglyConsistent, DynamoDbTarget.Table)).toList,
          state       = state // reads do not change storage
        )

  /** Emit a `StorageBytesDelta` (on the base table) only when the write/delete actually moved the byte total. */
  private def storageDelta(before: TableSummaryState, after: TableSummaryState): List[DynamoDbConsumption] =
    val delta = after.totalItemBytes - before.totalItemBytes
    if delta != 0L then List(StorageBytesDelta(delta, DynamoDbTarget.Table)) else Nil
