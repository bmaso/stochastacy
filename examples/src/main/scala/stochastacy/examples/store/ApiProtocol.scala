package stochastacy.examples.store

/** The client-facing API protocol for the store simulator — the vocabulary a caller uses, one tier
 *  above the datastore protocol. Requests and responses are **timeless** payloads (the wire carries
 *  `Timed[ApiRequest]` / `Timed[ApiResponse]`); the service tier translates them 1:1 to and from the
 *  datastore protocol (see [[IngressSampler]] / [[EgressSampler]]).
 *
 *  `ApiResponse` is a deliberately *thinner* view than [[StoreResponse]]: the client sees what was
 *  returned, not the store's internal work (a query's `evaluated*` counts are dropped). */

// --- requests ---

sealed trait ApiRequest
final case class GetEntity()                extends ApiRequest
final case class CreateEntity(sizeBytes: Long) extends ApiRequest
final case class UpdateEntity(sizeBytes: Long) extends ApiRequest
final case class DeleteEntity()             extends ApiRequest
final case class ListEntities(sel: SelectivityClass, sort: SortMode, page: Pagination) extends ApiRequest
final case class GetReport(sel: SelectivityClass, groupCount: Int, sort: SortMode, page: Pagination) extends ApiRequest

// --- responses ---

sealed trait ApiResponse
final case class EntityResult(found: Boolean, bytes: Long)          extends ApiResponse
final case class EntityWritten(created: Boolean)                    extends ApiResponse
final case class EntityDeleted(deleted: Boolean)                    extends ApiResponse
final case class QueryResponse(returnedItems: Long, returnedBytes: Long) extends ApiResponse
final case class ApiError(kind: String)                            extends ApiResponse
