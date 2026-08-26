package stochastacy.aws.dynamodb

/**
 * The v2 DynamoDB request/response protocol — **timeless** payloads carried on the v2 wire inside a
 * `Timed[E]` wrapper (which owns `eventTime` / `intraTick` / `usecase`). This is a clean re-creation of
 * the legacy `stochastacy.aws.dynamodb` protocol, not a reuse: the legacy events embed their own timing
 * and are slated for removal once the v2 line reaches parity.
 *
 * Covers the four single-item operations (get / put / update / delete) and the two multi-item read
 * operations (query / scan). The transactional operations arrive with a later demo.
 */
sealed trait DynamoDbRequest

/** Which table or secondary index an operation acts on — the read target of a query/scan and the
 *  dimension every consumption fact is tagged with. A single table means no table name is needed. */
enum DynamoDbTarget:
  case Table
  case Gsi(indexName: String)
  case Lsi(indexName: String)

/** Read a single item by key. Read consistency is a table-level setting the behavior supplies, not a
 *  per-request field. */
case object GetItemRequest extends DynamoDbRequest

/** Write a single item of `itemBytes` bytes (create-or-replace). */
final case class PutItemRequest(itemBytes: Long) extends DynamoDbRequest:
  require(itemBytes > 0L, s"PutItemRequest.itemBytes must be positive, got $itemBytes")

/** Update (upsert) a single item to `itemBytes` bytes. */
final case class UpdateItemRequest(itemBytes: Long) extends DynamoDbRequest:
  require(itemBytes > 0L, s"UpdateItemRequest.itemBytes must be positive, got $itemBytes")

/** Delete a single item by key. */
case object DeleteItemRequest extends DynamoDbRequest

/** Query a partition of `target` at the given consistency (a GSI query is always eventually consistent). */
final case class QueryRequest(target: DynamoDbTarget, consistency: ReadConsistency) extends DynamoDbRequest

/** Scan `target` at the given consistency. */
final case class ScanRequest(target: DynamoDbTarget, consistency: ReadConsistency) extends DynamoDbRequest

sealed trait DynamoDbResponse

/** The non-error response to a GetItem: whether the item existed and, if so, its size. */
final case class GetItemResponse(itemFound: Boolean, itemBytes: Option[Long]) extends DynamoDbResponse

/** The non-error response to a PutItem. `previousItemBytes` is empty when no item was overwritten. */
final case class PutItemResponse(
  storedItemBytes:   Long,
  createdNewItem:    Boolean,
  previousItemBytes: Option[Long]
) extends DynamoDbResponse

/** The non-error response to an UpdateItem. `createdNewItem` marks an upsert that hit no existing item. */
final case class UpdateItemResponse(
  storedItemBytes:   Long,
  createdNewItem:    Boolean,
  previousItemBytes: Option[Long]
) extends DynamoDbResponse

/** The non-error response to a DeleteItem. `deletedItemBytes` is empty when no item was present. */
final case class DeleteItemResponse(deletedItemBytes: Option[Long]) extends DynamoDbResponse

/** The non-error response to a Query: the read shape — how many items/bytes were **evaluated** (what the
 *  read is charged for) vs. **returned** (what the caller received). */
final case class QueryResponse(
  evaluatedItemCount: Long,
  evaluatedBytes:     Long,
  returnedItemCount:  Long,
  returnedBytes:      Long
) extends DynamoDbResponse

/** The non-error response to a Scan — same read-shape fields as a Query. */
final case class ScanResponse(
  evaluatedItemCount: Long,
  evaluatedBytes:     Long,
  returnedItemCount:  Long,
  returnedBytes:      Long
) extends DynamoDbResponse

/** The error response to any request the system-error gate rejects — DynamoDB's `InternalServerError`.
 *  No capacity is consumed and no state is mutated (a rejected request never reaches the table). */
case object SystemErrorResponse extends DynamoDbResponse

/** The error response to a provisioned request throttled because its demand would exceed the table's (or a
 *  GSI's) per-tick provisioned capacity — DynamoDB's `ProvisionedThroughputExceededException`. No capacity
 *  is consumed and no state is mutated. */
case object ThrottledResponse extends DynamoDbResponse
