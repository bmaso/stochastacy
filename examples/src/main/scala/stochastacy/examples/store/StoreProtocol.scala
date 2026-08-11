package stochastacy.examples.store

import stochastacy.sim.{SimTime, TimedEvent}

/** The datastore protocol for the v2/phase0 store simulator.
 *
 *  Three operation families with three distinct cost signatures — point (O(1)), list (evaluated
 *  cost is mode-dependent, including the deep-offset cliff), and report (evaluates the whole
 *  matched set, returns little). Variety within a family is expressed as parameters, not as a
 *  proliferation of types.
 *
 *  Requests are self-timed `TimedEvent`s (they arrive on the wire). Responses and consumption are
 *  *timeless* payloads — the schedule-and-release transducer stamps their timing (see
 *  `stochastacy.core.component.Timed`). `usecase` is left as the base `Any` label for now; the
 *  behavior-driving intent for queries rides as the typed `sel` field (fully typing
 *  `TimedEvent.usecase` is deferred — see docs/roadmaps/v2-phase0.md). */

// --- request parameters (client intent) ---

/** Query intent = use-case. Each class names a *selectivity law*, realized against current state
 *  by `StoreSampler` (the enum itself stays a pure tag). */
enum SelectivityClass:
  case PointLookup    // ~constant COUNT, independent of entity count
  case CategoryFilter // ~constant FRACTION of entity count
  case FullScan       // ≈ all entities

enum SortMode:
  case Unordered, IndexOrdered, RequiresSort

enum Pagination:
  case Offset(pageIndex: Int, pageSize: Int)
  case Keyset(pageSize: Int)

// --- requests ---

sealed trait StoreRequest extends TimedEvent

final case class Get(
  eventTime:              SimTime,
  override val intraTick: Double = 0.0,
  usecase:                Any    = "get"
) extends StoreRequest

final case class Put(
  sizeBytes:              Long,
  eventTime:              SimTime,
  override val intraTick: Double = 0.0,
  usecase:                Any    = "put"
) extends StoreRequest

final case class Delete(
  eventTime:              SimTime,
  override val intraTick: Double = 0.0,
  usecase:                Any    = "delete"
) extends StoreRequest

final case class ListQuery(
  sel:                    SelectivityClass,
  sort:                   SortMode,
  page:                   Pagination,
  eventTime:              SimTime,
  override val intraTick: Double = 0.0,
  usecase:                Any    = "list"
) extends StoreRequest

final case class ReportQuery(
  sel:                    SelectivityClass,
  groupCount:             Int,
  sort:                   SortMode,
  page:                   Pagination,
  eventTime:              SimTime,
  override val intraTick: Double = 0.0,
  usecase:                Any    = "report"
) extends StoreRequest

// --- response payloads (timeless) ---

sealed trait StoreResponse
final case class GetResult(hit: Boolean, bytes: Long)             extends StoreResponse
final case class WriteResult(created: Boolean)                    extends StoreResponse
final case class DeleteResult(deleted: Boolean)                   extends StoreResponse
final case class QueryResult(
  returnedItems:  Long,
  returnedBytes:  Long,
  evaluatedItems: Long,
  evaluatedBytes: Long
) extends StoreResponse
final case class ErrorResult(kind: String)                        extends StoreResponse

// --- consumption payloads (timeless) ---

sealed trait Consumption
final case class WorkPerformed(items: Long, bytes: Long) extends Consumption // scan/eval cost proxy
final case class DataReturned(items: Long, bytes: Long)  extends Consumption // egress proxy
final case class StorageDelta(bytesDelta: Long)          extends Consumption // storage change
final case class RequestServiced(latencyTicks: Double)   extends Consumption // observed servicing latency

// --- bounded summary state (no per-key maps) ---

final case class StoreState(entityCount: Long, totalBytes: Long):
  def meanBytes: Long = if entityCount <= 0L then 0L else totalBytes / entityCount
