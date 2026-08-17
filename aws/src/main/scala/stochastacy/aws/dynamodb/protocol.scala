package stochastacy.aws.dynamodb

/**
 * The v2 DynamoDB request/response protocol — **timeless** payloads carried on the v2 wire inside a
 * `Timed[E]` wrapper (which owns `eventTime` / `intraTick` / `usecase`). This is a clean re-creation of
 * the legacy `stochastacy.aws.dynamodb` protocol, not a reuse: the legacy events embed their own timing
 * and are slated for removal once the v2 line reaches parity.
 *
 * Phase-1 covers the four single-item operations (get / put / update / delete). Query / Scan and the
 * transactional operations arrive when Order-Tracking Phase-2 does.
 */
sealed trait DynamoDbRequest

/** Read a single item by key. Read consistency is a table-level setting, not a per-request field. */
case object GetItemRequest extends DynamoDbRequest

/** Write a single item of `itemBytes` bytes (create-or-replace). */
final case class PutItemRequest(itemBytes: Long) extends DynamoDbRequest:
  require(itemBytes > 0L, s"PutItemRequest.itemBytes must be positive, got $itemBytes")

/** Update (upsert) a single item to `itemBytes` bytes. */
final case class UpdateItemRequest(itemBytes: Long) extends DynamoDbRequest:
  require(itemBytes > 0L, s"UpdateItemRequest.itemBytes must be positive, got $itemBytes")

/** Delete a single item by key. */
case object DeleteItemRequest extends DynamoDbRequest

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
